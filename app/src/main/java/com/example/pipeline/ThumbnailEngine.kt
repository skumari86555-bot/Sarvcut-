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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.model.AspectRatioOption
import com.example.data.model.ThumbnailStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ThumbnailEngine {

    suspend fun generateThumbnail(
        context: Context,
        title: String,
        formatLabel: String,
        sourceImageFile: File?,
        aspectRatio: AspectRatioOption,
        style: ThumbnailStyle,
        outFile: File
    ): File = withContext(Dispatchers.Default) {
        val width = if (aspectRatio == AspectRatioOption.YOUTUBE_16_9) 1280 else 720
        val height = if (aspectRatio == AspectRatioOption.YOUTUBE_16_9) 720 else 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Background artwork or studio backdrop
        val sourceBitmap = sourceImageFile?.takeIf { it.exists() }?.let {
            BitmapFactory.decodeFile(it.absolutePath)
        }
        if (sourceBitmap != null) {
            drawCenterCropBitmap(canvas, sourceBitmap, width, height)
            sourceBitmap.recycle()
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.parseColor("#0D0A1A"), Color.parseColor("#2A1859"), Color.parseColor("#09090D")),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        val displayTypeface = try {
            ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }

        // 2. Style-specific compositing
        when (style) {
            ThumbnailStyle.BOLD_CINEMA -> {
                val scrim = Paint().apply {
                    shader = LinearGradient(
                        0f, height * 0.25f, 0f, height.toFloat(),
                        Color.TRANSPARENT, Color.argb(235, 9, 9, 13), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
                drawPillBadge(canvas, "SARVCUT · $formatLabel", 56f, 56f, Color.parseColor("#8B5CF6"), displayTypeface)
                drawHeadline(canvas, title, 56f, height * 0.56f, width - 112, height * 0.105f, Color.WHITE, displayTypeface)
            }
            ThumbnailStyle.EDITORIAL_SPLIT -> {
                val panelPaint = Paint().apply { color = Color.argb(235, 13, 13, 20) }
                canvas.drawRect(0f, 0f, width * 0.52f, height.toFloat(), panelPaint)
                val accentLine = Paint().apply { color = Color.parseColor("#8B5CF6"); strokeWidth = 10f }
                canvas.drawLine(width * 0.52f, 0f, width * 0.52f, height.toFloat(), accentLine)
                drawPillBadge(canvas, formatLabel, 48f, 64f, Color.parseColor("#22D3EE"), displayTypeface, Color.BLACK)
                drawHeadline(canvas, title, 48f, height * 0.28f, (width * 0.44f).toInt(), height * 0.085f, Color.WHITE, displayTypeface)
            }
            ThumbnailStyle.NEON_PULSE -> {
                val overlay = Paint().apply { color = Color.argb(135, 14, 10, 28) }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
                val border = Paint().apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 14f
                    color = Color.parseColor("#A78BFA")
                }
                canvas.drawRect(24f, 24f, width - 24f, height - 24f, border)
                drawPillBadge(canvas, "AI PRODUCTION", 64f, 64f, Color.parseColor("#8B5CF6"), displayTypeface)
                drawHeadline(canvas, title.uppercase(), 64f, height * 0.48f, width - 128, height * 0.095f, Color.parseColor("#F4F4F8"), displayTypeface)
            }
            ThumbnailStyle.MINIMAL_DOC -> {
                val scrim = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, height.toFloat(),
                        Color.argb(110, 9, 9, 13), Color.argb(215, 9, 9, 13), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
                drawPillBadge(canvas, formatLabel, 60f, height - 240f, Color.WHITE, displayTypeface, Color.BLACK)
                drawHeadline(canvas, title, 60f, height - 170f, width - 120, height * 0.075f, Color.WHITE, displayTypeface)
            }
        }

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            val fmt = if (outFile.name.endsWith(".png", ignoreCase = true)) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            bitmap.compress(fmt, 94, fos)
        }
        bitmap.recycle()
        outFile
    }

    suspend fun exportThumbnailToDownloads(
        context: Context,
        sourceFile: File,
        fileNameWithoutExt: String,
        asPng: Boolean
    ): String = withContext(Dispatchers.IO) {
        val ext = if (asPng) "png" else "jpg"
        val mime = if (asPng) "image/png" else "image/jpeg"
        val bmp = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalStateException("Thumbnail image not found.")
        val displayName = "${fileNameWithoutExt.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(50)}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SARVCUT")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create Downloads entry.")
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bmp.recycle()
            uri.toString()
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SARVCUT")
            dir.mkdirs()
            val dest = File(dir, displayName)
            FileOutputStream(dest).use { out ->
                bmp.compress(if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
            }
            bmp.recycle()
            dest.absolutePath
        }
    }

    private fun drawCenterCropBitmap(canvas: Canvas, src: Bitmap, dstW: Int, dstH: Int) {
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
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
    }

    private fun drawPillBadge(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        bgColor: Int,
        typeface: Typeface,
        textColor: Int = Color.WHITE
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 26f
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        val textW = textPaint.measureText(text)
        val rect = RectF(x, y, x + textW + 44f, y + 52f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(rect, 26f, 26f, bgPaint)
        canvas.drawText(text, x + 22f, y + 35f, textPaint)
    }

    private fun drawHeadline(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Int,
        textSizePx: Float,
        color: Int,
        typeface: Typeface
    ) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSizePx
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
            setShadowLayer(12f, 0f, 4f, Color.argb(200, 0, 0, 0))
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxWidth.coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
