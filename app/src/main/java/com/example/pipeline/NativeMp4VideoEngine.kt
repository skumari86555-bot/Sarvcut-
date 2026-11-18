package com.example.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.model.AspectRatioOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class RenderSceneInput(
    val index: Int,
    val heading: String,
    val subtitleText: String,
    val imageFile: File,
    val audioWavFile: File?,
    val durationSeconds: Int
)

data class Mp4RenderOutput(
    val mp4File: File,
    val width: Int,
    val height: Int,
    val totalDurationSeconds: Int,
    val fileSizeBytes: Long
)

object NativeMp4VideoEngine {

    private const val FPS = 24
    private const val SAMPLE_RATE = 44100
    private const val INTRO_SECONDS = 2
    private const val OUTRO_SECONDS = 2

    suspend fun renderMp4(
        context: Context,
        projectTitle: String,
        formatLabel: String,
        languageLabel: String,
        aspectRatio: AspectRatioOption,
        scenes: List<RenderSceneInput>,
        burnSubtitles: Boolean,
        addAmbientMusic: Boolean,
        sourceCount: Int,
        outFile: File,
        onProgress: suspend (Int, String) -> Unit
    ): Mp4RenderOutput = withContext(Dispatchers.Default) {
        require(scenes.isNotEmpty()) { "At least one scene is required to render MP4." }
        outFile.parentFile?.mkdirs()

        val (targetW, targetH) = chooseSupportedAvcResolution(aspectRatio)
        val tempVideoFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}_v.mp4")
        val tempAudioFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}_a.mp4")
        tempVideoFile.delete()
        tempAudioFile.delete()

        val sceneTimings = computeSceneTimings(scenes)
        val totalSeconds = INTRO_SECONDS + sceneTimings.sumOf { it.durationSec } + OUTRO_SECONDS

        try {
            onProgress(76, "Encoding H.264 frames (${targetW}x${targetH})")
            encodeVideoTrack(
                context = context,
                projectTitle = projectTitle,
                formatLabel = formatLabel,
                languageLabel = languageLabel,
                width = targetW,
                height = targetH,
                sceneTimings = sceneTimings,
                burnSubtitles = burnSubtitles,
                sourceCount = sourceCount,
                tempVideoFile = tempVideoFile,
                onFrameProgress = { pct ->
                    onProgress(76 + (pct * 12 / 100), "Rendering video frames ($pct%)")
                }
            )

            onProgress(89, "Mixing voiceover & AAC audio")
            encodeAudioTrack(
                sceneTimings = sceneTimings,
                totalSeconds = totalSeconds,
                addAmbientMusic = addAmbientMusic,
                tempAudioFile = tempAudioFile
            )

            onProgress(93, "Muxing 1080p MP4 container")
            muxTracksIntoMp4(tempVideoFile, tempAudioFile, outFile)

            require(outFile.exists() && outFile.length() > 1024L) {
                "MP4 export produced an empty file."
            }

            Mp4RenderOutput(
                mp4File = outFile,
                width = targetW,
                height = targetH,
                totalDurationSeconds = totalSeconds,
                fileSizeBytes = outFile.length()
            )
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
        }
    }

    suspend fun exportMp4ToDownloads(
        context: Context,
        mp4File: File,
        title: String
    ): String = withContext(Dispatchers.IO) {
        require(mp4File.exists()) { "MP4 file not found on disk." }
        val safeName = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(48).ifBlank { "sarvcut_video" }
        val displayName = "${safeName}_${System.currentTimeMillis() / 1000}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SARVCUT")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create Downloads MediaStore entry.")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(mp4File).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SARVCUT")
            dir.mkdirs()
            val dest = File(dir, displayName)
            mp4File.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    private data class SceneTiming(
        val scene: RenderSceneInput,
        val startSec: Int,
        val durationSec: Int
    )

    private fun computeSceneTimings(scenes: List<RenderSceneInput>): List<SceneTiming> {
        var cursor = INTRO_SECONDS
        return scenes.map { scene ->
            val wavSec = scene.audioWavFile?.let { estimateWavDurationSeconds(it) } ?: 0
            val dur = maxOf(scene.durationSeconds, wavSec + 1).coerceIn(4, 120)
            val item = SceneTiming(scene, cursor, dur)
            cursor += dur
            item
        }
    }

    private fun chooseSupportedAvcResolution(aspectRatio: AspectRatioOption): Pair<Int, Int> {
        val candidates = if (aspectRatio == AspectRatioOption.YOUTUBE_16_9) {
            listOf(1920 to 1080, 1280 to 720, 960 to 540, 640 to 360)
        } else {
            listOf(1080 to 1920, 720 to 1280, 540 to 960, 360 to 640)
        }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            } catch (_: Exception) {
                null
            } ?: continue
            for ((w, h) in candidates) {
                if (caps.isSizeSupported(w, h)) return w to h
            }
        }
        return if (aspectRatio == AspectRatioOption.YOUTUBE_16_9) 1280 to 720 else 720 to 1280
    }

    private suspend fun encodeVideoTrack(
        context: Context,
        projectTitle: String,
        formatLabel: String,
        languageLabel: String,
        width: Int,
        height: Int,
        sceneTimings: List<SceneTiming>,
        burnSubtitles: Boolean,
        sourceCount: Int,
        tempVideoFile: File,
        onFrameProgress: suspend (Int) -> Unit
    ) {
        val totalSeconds = INTRO_SECONDS + sceneTimings.sumOf { it.durationSec } + OUTRO_SECONDS
        val totalFrames = totalSeconds * FPS

        val displayTypeface = try {
            ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
        val bodyTypeface = try {
            ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) ?: Typeface.SANS_SERIF
        } catch (_: Exception) {
            Typeface.SANS_SERIF
        }

        // Pre-decode & pre-scale scene bitmaps slightly larger (1.12x) for smooth Ken Burns motion
        val scaledBitmaps = sceneTimings.map { timing ->
            val raw = BitmapFactory.decodeFile(timing.scene.imageFile.absolutePath)
            val preScaleW = (width * 1.12f).roundToInt()
            val preScaleH = (height * 1.12f).roundToInt()
            if (raw != null) {
                val cropped = createCenterCroppedBitmap(raw, preScaleW, preScaleH)
                if (cropped !== raw) raw.recycle()
                cropped
            } else {
                createFallbackCinemaBitmap(preScaleW, preScaleH, timing.scene.heading)
            }
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1080 && height >= 1080) 4_500_000 else 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBitmap)
        val argbPixels = IntArray(width * height)
        var trackIndex = -1
        var muxerStarted = false

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            var lastReportedPct = -1

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        if (frameIndex >= totalFrames) {
                            encoder.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                frameIndex * 1_000_000L / FPS,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            drawFrame(
                                canvas = canvas,
                                width = width,
                                height = height,
                                frameIndex = frameIndex,
                                totalFrames = totalFrames,
                                projectTitle = projectTitle,
                                formatLabel = formatLabel,
                                languageLabel = languageLabel,
                                sceneTimings = sceneTimings,
                                scaledBitmaps = scaledBitmaps,
                                burnSubtitles = burnSubtitles,
                                sourceCount = sourceCount,
                                displayTypeface = displayTypeface,
                                bodyTypeface = bodyTypeface
                            )

                            frameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
                            val ptsUs = frameIndex * 1_000_000L / FPS
                            val bytesWritten = writeArgbToCodecInput(encoder, inIdx, argbPixels, width, height)
                            encoder.queueInputBuffer(inIdx, 0, bytesWritten, ptsUs, 0)

                            frameIndex++
                            val pct = (frameIndex * 100) / totalFrames.coerceAtLeast(1)
                            if (pct >= lastReportedPct + 10) {
                                lastReportedPct = pct
                                onFrameProgress(pct)
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIdx)
                        if (encoded != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            scaledBitmaps.forEach { it.recycle() }
            frameBitmap.recycle()
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private fun writeArgbToCodecInput(
        encoder: MediaCodec,
        inputIndex: Int,
        argb: IntArray,
        width: Int,
        height: Int
    ): Int {
        val image = try {
            encoder.getInputImage(inputIndex)
        } catch (_: Exception) {
            null
        }
        if (image != null && image.planes.size >= 3) {
            try {
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val yBuf = yPlane.buffer
                val uBuf = uPlane.buffer
                val vBuf = vPlane.buffer
                val yRowStride = yPlane.rowStride
                val yPixStride = yPlane.pixelStride
                val uRowStride = uPlane.rowStride
                val uPixStride = uPlane.pixelStride
                val vRowStride = vPlane.rowStride
                val vPixStride = vPlane.pixelStride

                for (j in 0 until height) {
                    val yRowOffset = j * yRowStride
                    val uvRowOffsetU = (j / 2) * uRowStride
                    val uvRowOffsetV = (j / 2) * vRowStride
                    val argbRowOffset = j * width
                    for (i in 0 until width) {
                        val px = argb[argbRowOffset + i]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        yBuf.put(yRowOffset + i * yPixStride, y.coerceIn(0, 255).toByte())

                        if ((j and 1) == 0 && (i and 1) == 0) {
                            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                            val uvCol = i / 2
                            uBuf.put(uvRowOffsetU + uvCol * uPixStride, u.coerceIn(0, 255).toByte())
                            vBuf.put(uvRowOffsetV + uvCol * vPixStride, v.coerceIn(0, 255).toByte())
                        }
                    }
                }
                return width * height * 3 / 2
            } finally {
                image.close()
            }
        }

        // Fallback planar I420 direct buffer write
        val buf = encoder.getInputBuffer(inputIndex) ?: return 0
        buf.clear()
        val ySize = width * height
        val uvSize = ySize / 4
        var yPos = 0
        var uPos = ySize
        var vPos = ySize + uvSize
        for (j in 0 until height) {
            val row = j * width
            for (i in 0 until width) {
                val px = argb[row + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                buf.put(yPos++, y.coerceIn(0, 255).toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    buf.put(uPos++, u.coerceIn(0, 255).toByte())
                    buf.put(vPos++, v.coerceIn(0, 255).toByte())
                }
            }
        }
        return width * height * 3 / 2
    }

    private fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameIndex: Int,
        totalFrames: Int,
        projectTitle: String,
        formatLabel: String,
        languageLabel: String,
        sceneTimings: List<SceneTiming>,
        scaledBitmaps: List<Bitmap>,
        burnSubtitles: Boolean,
        sourceCount: Int,
        displayTypeface: Typeface,
        bodyTypeface: Typeface
    ) {
        val timeSec = frameIndex.toFloat() / FPS
        val introEnd = INTRO_SECONDS.toFloat()
        val outroStart = (totalFrames.toFloat() / FPS) - OUTRO_SECONDS.toFloat()

        canvas.drawColor(Color.parseColor("#09090D"))

        when {
            timeSec < introEnd -> {
                val progress = (timeSec / introEnd).coerceIn(0f, 1f)
                val alpha = (min(1f, progress * 2.2f) * 255).toInt()
                drawIntroCard(canvas, width, height, projectTitle, formatLabel, languageLabel, alpha, displayTypeface, bodyTypeface)
            }
            timeSec >= outroStart -> {
                val progress = ((timeSec - outroStart) / OUTRO_SECONDS).coerceIn(0f, 1f)
                val alpha = ((1f - progress * 0.4f) * 255).toInt()
                drawOutroCard(canvas, width, height, sourceCount, alpha, displayTypeface, bodyTypeface)
            }
            else -> {
                val activeIdx = sceneTimings.indexOfFirst {
                    timeSec >= it.startSec && timeSec < it.startSec + it.durationSec
                }.let { if (it >= 0) it else sceneTimings.lastIndex }

                val activeTiming = sceneTimings[activeIdx]
                val sceneLocalTime = (timeSec - activeTiming.startSec).coerceAtLeast(0f)
                val sceneProgress = (sceneLocalTime / activeTiming.durationSec.toFloat()).coerceIn(0f, 1f)

                // Ken Burns smooth zoom & drift
                val bmp = scaledBitmaps[activeIdx]
                drawKenBurnsBitmap(canvas, bmp, width, height, sceneProgress, activeIdx, 255)

                // 0.4s cross-fade from previous scene
                if (activeIdx > 0 && sceneLocalTime < 0.4f) {
                    val prevAlpha = ((1f - (sceneLocalTime / 0.4f)) * 255).toInt().coerceIn(0, 255)
                    drawKenBurnsBitmap(canvas, scaledBitmaps[activeIdx - 1], width, height, 1f, activeIdx - 1, prevAlpha)
                }

                // Bottom cinematic scrim for legibility
                val scrimPaint = Paint().apply {
                    shader = LinearGradient(
                        0f, height * 0.58f, 0f, height.toFloat(),
                        Color.TRANSPARENT, Color.argb(210, 9, 9, 13), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), scrimPaint)

                // Chapter indicator pill
                drawChapterChip(canvas, activeIdx + 1, activeTiming.scene.heading, width, displayTypeface)

                // Synchronized burned-in subtitle segment
                if (burnSubtitles && activeTiming.scene.subtitleText.isNotBlank()) {
                    val activeSubtitleChunk = pickSubtitleChunk(activeTiming.scene.subtitleText, sceneProgress)
                    drawSubtitleBar(canvas, width, height, activeSubtitleChunk, bodyTypeface)
                }
            }
        }
    }

    private fun drawKenBurnsBitmap(
        canvas: Canvas,
        bmp: Bitmap,
        width: Int,
        height: Int,
        progress: Float,
        sceneIdx: Int,
        alpha: Int
    ) {
        val zoomFactor = 1f + (progress * 0.08f)
        val cropW = (bmp.width / zoomFactor).toInt().coerceAtMost(bmp.width)
        val cropH = (bmp.height / zoomFactor).toInt().coerceAtMost(bmp.height)
        val maxShiftX = (bmp.width - cropW).coerceAtLeast(0)
        val maxShiftY = (bmp.height - cropH).coerceAtLeast(0)
        val left = if (sceneIdx % 2 == 0) (maxShiftX * progress).toInt() else (maxShiftX * (1f - progress)).toInt()
        val top = (maxShiftY * 0.5f).toInt()
        val srcRect = Rect(left, top, left + cropW, top + cropH)
        val dstRect = Rect(0, 0, width, height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    private fun drawIntroCard(
        canvas: Canvas,
        width: Int,
        height: Int,
        title: String,
        formatLabel: String,
        languageLabel: String,
        alpha: Int,
        displayTypeface: Typeface,
        bodyTypeface: Typeface
    ) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.parseColor("#09090D"), Color.parseColor("#1C1038"), Color.parseColor("#09090D")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B5CF6")
            this.alpha = alpha
        }
        val barW = width * 0.14f
        canvas.drawRoundRect(
            RectF((width - barW) / 2f, height * 0.34f, (width + barW) / 2f, height * 0.34f + 8f),
            4f, 4f, badgePaint
        )

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            textSize = (min(width, height) * 0.068f).coerceIn(32f, 76f)
            typeface = Typeface.create(displayTypeface, Typeface.BOLD)
        }
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, tp, (width * 0.82f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()
        canvas.save()
        canvas.translate(width * 0.09f, height * 0.39f)
        titleLayout.draw(canvas)
        canvas.restore()

        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A78BFA")
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = (min(width, height) * 0.028f).coerceIn(18f, 30f)
            typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
        }
        canvas.drawText(
            "SARVCUT STUDIO  ·  ${formatLabel.uppercase()}  ·  ${languageLabel.uppercase()}",
            width / 2f,
            height * 0.72f,
            metaPaint
        )
    }

    private fun drawOutroCard(
        canvas: Canvas,
        width: Int,
        height: Int,
        sourceCount: Int,
        alpha: Int,
        displayTypeface: Typeface,
        bodyTypeface: Typeface
    ) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.parseColor("#09090D"), Color.parseColor("#18132B"), Color.parseColor("#09090D")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = (min(width, height) * 0.065f).coerceIn(30f, 72f)
            typeface = Typeface.create(displayTypeface, Typeface.BOLD)
        }
        canvas.drawText("SARVCUT", width / 2f, height * 0.46f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A1A1B5")
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = (min(width, height) * 0.028f).coerceIn(18f, 30f)
            typeface = bodyTypeface
        }
        val sourceLine = if (sourceCount > 0) {
            "Verified with $sourceCount grounded web sources"
        } else {
            "Produced autonomously with SARVCUT AI"
        }
        canvas.drawText(sourceLine, width / 2f, height * 0.55f, subPaint)
    }

    private fun drawChapterChip(
        canvas: Canvas,
        chapterNumber: Int,
        heading: String,
        width: Int,
        displayTypeface: Typeface
    ) {
        val label = "${chapterNumber.toString().padStart(2, '0')} · ${heading.uppercase().take(28)}"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (width * 0.018f).coerceIn(16f, 26f)
            typeface = Typeface.create(displayTypeface, Typeface.BOLD)
        }
        val tw = textPaint.measureText(label)
        val padH = 22f
        val rect = RectF(36f, 36f, 36f + tw + padH * 2, 36f + textPaint.textSize * 2.1f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 18, 19, 26)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(180, 139, 92, 246)
        }
        canvas.drawRoundRect(rect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)
        canvas.drawText(label, rect.left + padH, rect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun pickSubtitleChunk(fullSubtitle: String, sceneProgress: Float): String {
        val words = fullSubtitle.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 10) return fullSubtitle.trim()
        val chunkSize = 9
        val totalChunks = (words.size + chunkSize - 1) / chunkSize
        val chunkIdx = (sceneProgress * totalChunks).toInt().coerceIn(0, totalChunks - 1)
        val start = chunkIdx * chunkSize
        val end = min(words.size, start + chunkSize)
        return words.subList(start, end).joinToString(" ")
    }

    private fun drawSubtitleBar(
        canvas: Canvas,
        width: Int,
        height: Int,
        text: String,
        bodyTypeface: Typeface
    ) {
        if (text.isBlank()) return
        val fontSize = (min(width, height) * 0.036f).coerceIn(20f, 40f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            typeface = Typeface.create(bodyTypeface, Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
        }
        val maxW = (width * 0.84f).toInt().coerceAtLeast(100)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()

        val boxH = layout.height + 28f
        val topY = height - boxH - (height * 0.06f)
        val bgRect = RectF(width * 0.07f, topY - 12f, width * 0.93f, topY + layout.height + 16f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 9, 9, 13)
        }
        canvas.drawRoundRect(bgRect, 18f, 18f, bgPaint)
        canvas.save()
        canvas.translate(width * 0.08f, topY)
        layout.draw(canvas)
        canvas.restore()
    }

    private suspend fun encodeAudioTrack(
        sceneTimings: List<SceneTiming>,
        totalSeconds: Int,
        addAmbientMusic: Boolean,
        tempAudioFile: File
    ) {
        val totalSamples = totalSeconds * SAMPLE_RATE
        val masterPcm = ShortArray(totalSamples)

        // Optional gentle studio ambient pad (-22 dB)
        if (addAmbientMusic) {
            val freqs = doubleArrayOf(130.81, 196.00, 261.63, 311.13) // C minor 7 warm pad
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = 0.5 + 0.5 * sin(2.0 * PI * 0.12 * t)
                var sampleSum = 0.0
                for (f in freqs) {
                    sampleSum += sin(2.0 * PI * f * t)
                }
                val v = (sampleSum / freqs.size) * 900.0 * (0.4 + 0.6 * envelope)
                masterPcm[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Mix each scene's synthesized voiceover WAV at exact scene start timestamp
        for (timing in sceneTimings) {
            coroutineContext.ensureActive()
            val wavFile = timing.scene.audioWavFile ?: continue
            if (!wavFile.exists()) continue
            val sceneSamples = decodeWavTo44100Mono(wavFile) ?: continue
            val startSample = timing.startSec * SAMPLE_RATE
            val maxWrite = min(sceneSamples.size, (timing.durationSec * SAMPLE_RATE))
            for (i in 0 until maxWrite) {
                val dstIdx = startSample + i
                if (dstIdx >= totalSamples) break
                val mixed = (masterPcm[dstIdx] * 0.45f).toInt() + sceneSamples[i].toInt()
                masterPcm[dstIdx] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tempAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sampleOffset = 0
            var inputDone = false
            var outputDone = false
            val chunkSamples = 2048

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)
                        if (sampleOffset >= totalSamples) {
                            encoder.queueInputBuffer(
                                inIdx, 0, 0,
                                sampleOffset * 1_000_000L / SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else if (buf != null) {
                            buf.clear()
                            val count = min(chunkSamples, totalSamples - sampleOffset)
                            val maxByBuf = buf.remaining() / 2
                            val actualCount = min(count, maxByBuf)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until actualCount) {
                                buf.putShort(masterPcm[sampleOffset + i])
                            }
                            val ptsUs = sampleOffset * 1_000_000L / SAMPLE_RATE
                            encoder.queueInputBuffer(inIdx, 0, actualCount * 2, ptsUs, 0)
                            sampleOffset += actualCount
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIdx)
                        if (encoded != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private fun muxTracksIntoMp4(videoMp4: File, audioMp4: File, outMp4: File) {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false

        try {
            vExt.setDataSource(videoMp4.absolutePath)
            aExt.setDataSource(audioMp4.absolutePath)
            vExt.selectTrack(0)
            aExt.selectTrack(0)

            val vTrack = muxer.addTrack(vExt.getTrackFormat(0))
            val aTrack = muxer.addTrack(aExt.getTrackFormat(0))
            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                buf.clear()
                val size = vExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = vExt.sampleTime
                info.flags = if ((vExt.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(vTrack, buf, info)
                vExt.advance()
            }

            while (true) {
                buf.clear()
                val size = aExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = aExt.sampleTime
                info.flags = if ((aExt.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(aTrack, buf, info)
                aExt.advance()
            }
        } finally {
            try { vExt.release() } catch (_: Exception) {}
            try { aExt.release() } catch (_: Exception) {}
            try {
                if (started) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private fun decodeWavTo44100Mono(wavFile: File): ShortArray? {
        val bytes = wavFile.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null

        var channels = 1
        var srcRate = 24000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(pos + 4).coerceAtLeast(0)
            if (chunkId == "fmt " && pos + 24 <= bytes.size) {
                channels = bb.getShort(pos + 10).toInt().coerceIn(1, 2)
                srcRate = bb.getInt(pos + 12).coerceIn(8000, 96000)
                bitsPerSample = bb.getShort(pos + 22).toInt()
            } else if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = min(chunkSize, bytes.size - dataOffset)
                break
            }
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        if (dataOffset < 0 || dataSize <= 0 || bitsPerSample != 16) return null

        val frameSize = channels * 2
        val srcFrames = dataSize / frameSize
        if (srcFrames <= 0) return null

        val monoSrc = ShortArray(srcFrames)
        for (i in 0 until srcFrames) {
            val base = dataOffset + i * frameSize
            monoSrc[i] = if (channels == 1) {
                bb.getShort(base)
            } else {
                val l = bb.getShort(base).toInt()
                val r = bb.getShort(base + 2).toInt()
                ((l + r) / 2).toShort()
            }
        }

        if (srcRate == SAMPLE_RATE) return monoSrc

        val dstFrames = ((srcFrames.toLong() * SAMPLE_RATE) / srcRate).toInt().coerceAtLeast(1)
        val resampled = ShortArray(dstFrames)
        val ratio = srcRate.toDouble() / SAMPLE_RATE.toDouble()
        for (i in 0 until dstFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, srcFrames - 1)
            val frac = srcPos - idx0
            val sample = monoSrc[idx0] * (1.0 - frac) + monoSrc[idx1] * frac
            resampled[i] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return resampled
    }

    private fun estimateWavDurationSeconds(wavFile: File): Int {
        if (!wavFile.exists() || wavFile.length() <= 44L) return 0
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return 0
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = bb.getInt(28).coerceAtLeast(16000)
        return ((bytes.size - 44) / byteRate).coerceAtLeast(1)
    }

    private fun createCenterCroppedBitmap(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val srcRect = if (srcRatio > dstRatio) {
            val cropW = (src.height * dstRatio).toInt()
            val x = (src.width - cropW) / 2
            Rect(x, 0, x + cropW, src.height)
        } else {
            val cropH = (src.width / dstRatio).toInt()
            val y = (src.height - cropH) / 2
            Rect(0, y, src.width, y + cropH)
        }
        canvas.drawBitmap(src, srcRect, Rect(0, 0, dstW, dstH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun createFallbackCinemaBitmap(w: Int, h: Int, heading: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#12131A"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B5CF6")
            textAlign = Paint.Align.CENTER
            textSize = 48f
        }
        c.drawText(heading.take(40), w / 2f, h / 2f, p)
        return bmp
    }
}
