package com.example.pipeline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.AppConfig
import com.example.data.model.CostEstimate
import com.example.data.model.PipelineStatus
import com.example.data.model.ProductionMode
import com.example.data.model.ResearchReport
import com.example.data.model.SceneStatus
import com.example.data.model.ThumbnailStyle
import com.example.data.model.UsageLog
import com.example.data.model.VideoCreationRequest
import com.example.data.model.VideoProject
import com.example.data.model.VideoScene
import com.example.data.repository.FirestoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PipelineCoordinator"
private const val CHANNEL_ID = "sarvcut_renders"

class VideoPipelineCoordinator(
    private val context: Context,
    private val repository: FirestoreRepository,
    private val researchProvider: ResearchProvider = GeminiResearchAdapter(),
    private val scriptProvider: ScriptProvider = GeminiScriptAdapter(),
    private val visualProvider: VisualProvider = GeminiImageAdapter(),
    private val voiceProvider: VoiceProvider = HybridVoiceAdapter(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun estimateCost(
        durationSeconds: Int,
        mode: ProductionMode,
        remainingBudgetUsd: Double
    ): CostEstimate {
        val scenes = when {
            durationSeconds <= 45 -> 4
            durationSeconds <= 90 -> 5
            durationSeconds <= 180 -> 7
            durationSeconds <= 420 -> 10
            else -> 12
        }
        val images = if (mode == ProductionMode.LOW_COST_IMAGE_ONLY) {
            ((scenes + 1) / 2).coerceAtLeast(2)
        } else {
            scenes + 1 // +1 dedicated thumbnail art
        }
        val tokens = 3200 + scenes * 650
        val rawCost = (tokens * 0.0000008) + (images * 0.025) + (scenes * 0.004)
        val roundedCost = ((rawCost * 1000).toInt() / 1000.0).coerceAtLeast(0.02)
        return CostEstimate(
            estimatedScenes = scenes,
            estimatedImages = images,
            estimatedTokens = tokens,
            estimatedCostUsd = roundedCost,
            exceedsBudget = roundedCost > remainingBudgetUsd,
            remainingBudgetUsd = remainingBudgetUsd
        )
    }

    fun startNewVideoJob(
        userId: String,
        request: VideoCreationRequest,
        appConfig: AppConfig,
        remainingBudgetUsd: Double,
        autoSaveToDownloads: Boolean,
        onJobCreated: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val cleanTopic = request.topic.trim()
        if (cleanTopic.length < 3) {
            onError("Please enter a topic of at least 3 characters.")
            return
        }
        val boundedDuration = request.targetDurationSeconds.coerceIn(30, appConfig.maxDurationSeconds.toInt())
        val estimate = estimateCost(boundedDuration, request.productionMode, remainingBudgetUsd)
        if (estimate.exceedsBudget) {
            onError(
                "Estimated cost ($${"%.3f".format(estimate.estimatedCostUsd)}) exceeds your remaining budget " +
                    "($${"%.2f".format(remainingBudgetUsd)}). Switch to Eco Image-Based mode or raise your spending limit."
            )
            return
        }

        val projectId = "proj_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val jobId = "job_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val project = VideoProject(
            id = projectId,
            userId = userId,
            jobId = jobId,
            title = cleanTopic.take(90),
            topic = cleanTopic,
            format = request.format.name,
            aspectRatio = request.aspectRatio.wireValue,
            language = request.language,
            translatedLanguage = request.translatedLanguage?.takeIf { it.isNotBlank() && it != request.language },
            targetDurationSeconds = boundedDuration.toLong(),
            voiceGender = request.voiceGender.name,
            voiceStyle = request.voiceStyle.name,
            productionMode = request.productionMode.name,
            status = PipelineStatus.QUEUED.name,
            currentStage = "Queued for autonomous pipeline",
            progressPercent = 2L,
            estimatedCostUsd = estimate.estimatedCostUsd,
            actualCostUsd = 0.0,
            thumbnailStyle = request.thumbnailStyle.name
        )

        scope.launch {
            val createResult = repository.createProject(project)
            if (createResult.isFailure) {
                onError(createResult.exceptionOrNull()?.localizedMessage ?: "Failed to create project record.")
                return@launch
            }
            onJobCreated(projectId)
            launchPipelineExecution(
                userId = userId,
                project = project,
                request = request.copy(targetDurationSeconds = boundedDuration),
                appConfig = appConfig,
                autoSaveToDownloads = autoSaveToDownloads
            )
        }
    }

    fun retryProjectJob(
        userId: String,
        project: VideoProject,
        appConfig: AppConfig,
        burnSubtitles: Boolean,
        addAmbientMusic: Boolean,
        autoSaveToDownloads: Boolean
    ) {
        if (activeJobs.containsKey(project.id)) return
        val req = VideoCreationRequest(
            topic = project.topic,
            format = project.formatEnum(),
            aspectRatio = project.aspectEnum(),
            language = project.language,
            translatedLanguage = project.translatedLanguage,
            targetDurationSeconds = project.targetDurationSeconds.toInt(),
            voiceGender = com.example.data.model.VoiceGender.fromRaw(project.voiceGender),
            voiceStyle = com.example.data.model.VoiceStyle.fromRaw(project.voiceStyle),
            productionMode = project.productionModeEnum(),
            thumbnailStyle = project.thumbnailStyleEnum(),
            burnSubtitles = burnSubtitles,
            addAmbientMusic = addAmbientMusic
        )
        launchPipelineExecution(userId, project, req, appConfig, autoSaveToDownloads)
    }

    fun cancelProjectJob(projectId: String) {
        activeJobs.remove(projectId)?.cancel()
        scope.launch {
            repository.updateProjectFields(
                projectId,
                mapOf(
                    "status" to PipelineStatus.CANCELLED.name,
                    "currentStage" to "Cancelled by user",
                    "errorMessage" to "Job cancelled before completion."
                )
            )
        }
    }

    private fun launchPipelineExecution(
        userId: String,
        project: VideoProject,
        request: VideoCreationRequest,
        appConfig: AppConfig,
        autoSaveToDownloads: Boolean
    ) {
        val projectId = project.id
        val projectDir = File(context.filesDir, "sarvcut/$projectId").apply { mkdirs() }
        var runningCost = 0.0

        val job = scope.launch {
            try {
                // Stage 1 & 2: Research & Fact Verification
                repository.updateProjectFields(
                    projectId,
                    mapOf(
                        "status" to PipelineStatus.RESEARCHING.name,
                        "currentStage" to "Searching web & gathering sources",
                        "progressPercent" to 10L,
                        "errorMessage" to com.google.firebase.firestore.FieldValue.delete()
                    )
                )
                val research = researchProvider.researchAndVerify(
                    topic = request.topic,
                    language = request.language,
                    useSearchGrounding = appConfig.searchGroundingEnabled
                )
                val researchCost = 0.012
                runningCost += researchCost
                recordUsage(userId, projectId, project.title, "RESEARCHING", "gemini-3.8-flash+search", research.estimatedTokens, researchCost, "SUCCESS", null)

                updateStage(projectId, PipelineStatus.VERIFYING, "Verifying facts across ${research.sources.size} sources", 22)
                val reportId = "rep_${projectId}"
                repository.saveResearchReport(
                    ResearchReport(
                        id = reportId,
                        userId = userId,
                        projectId = projectId,
                        topic = request.topic,
                        summary = research.summary,
                        verificationNotes = research.verificationNotes,
                        conflictingInfo = research.conflictingInfo,
                        sources = research.sources
                    )
                )
                repository.updateProjectFields(
                    projectId,
                    mapOf(
                        "researchSummary" to research.summary.take(19000),
                        "verificationVerdict" to research.verificationNotes.take(4500),
                        "uncertaintyFlags" to research.conflictingInfo?.take(4500),
                        "actualCostUsd" to runningCost
                    )
                )

                // Stage 3 & 4: Script & Scene Planning
                updateStage(projectId, PipelineStatus.SCRIPTING, "Writing ${request.format.label} script & scene plan", 34)
                val script = scriptProvider.writeScriptAndScenes(
                    topic = request.topic,
                    research = research,
                    format = request.format,
                    language = request.language,
                    translatedLanguage = request.translatedLanguage,
                    targetDurationSeconds = request.targetDurationSeconds,
                    aspectRatio = request.aspectRatio
                )
                val scriptCost = 0.01
                runningCost += scriptCost
                recordUsage(userId, projectId, script.title, "SCRIPTING", "gemini-3.8-flash", script.estimatedTokens, scriptCost, "SUCCESS", null)

                repository.updateProjectFields(
                    projectId,
                    mapOf(
                        "title" to script.title.trim().ifBlank { project.title }.take(200),
                        "scriptIntro" to script.intro.take(2900),
                        "scriptOutro" to script.outro.take(2900),
                        "sceneCount" to script.scenes.size.toLong(),
                        "actualCostUsd" to runningCost
                    )
                )

                val sceneModels = script.scenes.map { draft ->
                    val scene = VideoScene(
                        id = "scene_${draft.index}",
                        userId = userId,
                        projectId = projectId,
                        sceneIndex = draft.index.toLong(),
                        heading = draft.heading,
                        narration = draft.narration,
                        translatedNarration = draft.translatedNarration,
                        visualPrompt = draft.visualPrompt,
                        subtitleText = draft.subtitleText,
                        durationSeconds = draft.durationSeconds.toLong(),
                        status = SceneStatus.PLANNED.name
                    )
                    repository.upsertScene(projectId, scene)
                    scene
                }

                // Stage 5: AI Voice Generation per scene
                updateStage(projectId, PipelineStatus.VOICE_GEN, "Generating voice narration for ${sceneModels.size} scenes", 46)
                val wavFiles = mutableMapOf<String, File>()
                val updatedDurations = mutableMapOf<String, Int>()
                for ((idx, scene) in sceneModels.withIndex()) {
                    val spokenText = scene.translatedNarration?.takeIf { it.isNotBlank() } ?: scene.narration
                    val voiceLang = request.translatedLanguage?.takeIf { it.isNotBlank() } ?: request.language
                    val outWav = File(projectDir, "voice_${idx}.wav")
                    val voiceRes = voiceProvider.synthesizeVoiceToWav(
                        text = spokenText,
                        language = voiceLang,
                        gender = request.voiceGender,
                        style = request.voiceStyle,
                        outFile = outWav,
                        preferGeminiTts = appConfig.geminiTtsEnabled
                    )
                    wavFiles[scene.id] = voiceRes.wavFile
                    val actualSec = ((voiceRes.durationMillis / 1000L).toInt() + 1)
                        .coerceIn(scene.durationSeconds.toInt().coerceIn(2, 180), 180)
                    updatedDurations[scene.id] = actualSec
                    repository.updateSceneFields(
                        projectId,
                        scene.id,
                        mapOf(
                            "localAudioPath" to voiceRes.wavFile.absolutePath,
                            "durationSeconds" to actualSec.toLong(),
                            "status" to SceneStatus.VOICE_READY.name
                        )
                    )
                    val pct = 46 + ((idx + 1) * 14 / sceneModels.size.coerceAtLeast(1))
                    updateStage(projectId, PipelineStatus.VOICE_GEN, "Voice ready: Scene ${idx + 1}/${sceneModels.size}", pct)
                }
                val ttsCost = sceneModels.size * 0.004
                runningCost += ttsCost
                recordUsage(userId, projectId, script.title, "VOICE_GEN", "gemini-tts/android-tts", (sceneModels.size * 400).toLong(), ttsCost, "SUCCESS", null)

                // Stage 6: AI Visual Generation (Nano Banana)
                if (!appConfig.nanoBananaEnabled) {
                    throw IllegalStateException("AI Image Generation is disabled by admin provider config.")
                }
                updateStage(projectId, PipelineStatus.VISUAL_GEN, "Generating AI visuals with Nano Banana", 62)
                val imageFiles = mutableMapOf<String, File>()
                var reusedFile: File? = null
                for ((idx, scene) in sceneModels.withIndex()) {
                    val imgOut = File(projectDir, "scene_${idx}.jpg")
                    val cachedReuse = reusedFile
                    val finalImg = if (request.productionMode == ProductionMode.LOW_COST_IMAGE_ONLY && idx % 2 == 1 && cachedReuse != null) {
                        cachedReuse
                    } else {
                        val generated = visualProvider.generateSceneBitmap(
                            prompt = "${scene.visualPrompt}. Style: ${request.format.label}, ultra sharp cinematic lighting, no overlaid text.",
                            aspectRatio = request.aspectRatio,
                            outFile = imgOut
                        )
                        reusedFile = generated
                        runningCost += 0.025
                        generated
                    }
                    imageFiles[scene.id] = finalImg
                    repository.updateSceneFields(
                        projectId,
                        scene.id,
                        mapOf(
                            "localImagePath" to finalImg.absolutePath,
                            "status" to SceneStatus.COMPLETED.name
                        )
                    )
                    val pct = 62 + ((idx + 1) * 12 / sceneModels.size.coerceAtLeast(1))
                    updateStage(projectId, PipelineStatus.VISUAL_GEN, "Visual ready: Scene ${idx + 1}/${sceneModels.size}", pct)
                }
                recordUsage(
                    userId, projectId, script.title, "VISUAL_GEN", "gemini-nano-banana-2.1",
                    (imageFiles.values.distinct().size * 1200).toLong(),
                    imageFiles.values.distinct().size * 0.025,
                    "SUCCESS", null
                )

                // Stage 7 & 8: Automatic MP4 Video Editing & Subtitle Synchronization
                val renderInputs = sceneModels.mapIndexed { idx, scene ->
                    val wav = wavFiles[scene.id]
                    val img = imageFiles[scene.id] ?: throw IllegalStateException("Missing scene visual $idx")
                    RenderSceneInput(
                        index = idx,
                        heading = scene.heading,
                        subtitleText = scene.subtitleText,
                        imageFile = img,
                        audioWavFile = wav,
                        durationSeconds = updatedDurations[scene.id] ?: scene.durationSeconds.toInt()
                    )
                }
                val mp4File = File(projectDir, "${projectId}.mp4")
                val renderResult = NativeMp4VideoEngine.renderMp4(
                    context = context,
                    projectTitle = script.title,
                    formatLabel = request.format.label,
                    languageLabel = request.translatedLanguage ?: request.language,
                    aspectRatio = request.aspectRatio,
                    scenes = renderInputs,
                    burnSubtitles = request.burnSubtitles,
                    addAmbientMusic = request.addAmbientMusic,
                    sourceCount = research.sources.size,
                    outFile = mp4File,
                    onProgress = { pct, label ->
                        updateStage(projectId, PipelineStatus.RENDERING, label, pct)
                    }
                )

                // Stage 9: Automatic Thumbnail Generation
                updateStage(projectId, PipelineStatus.THUMBNAIL_GEN, "Generating custom thumbnail", 96)
                val thumbFile = File(projectDir, "thumbnail.jpg")
                ThumbnailEngine.generateThumbnail(
                    context = context,
                    title = script.title,
                    formatLabel = request.format.label,
                    sourceImageFile = imageFiles[sceneModels.first().id],
                    aspectRatio = request.aspectRatio,
                    style = request.thumbnailStyle,
                    outFile = thumbFile
                )

                val downloadUri = if (autoSaveToDownloads) {
                    try {
                        NativeMp4VideoEngine.exportMp4ToDownloads(context, renderResult.mp4File, script.title)
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-save to Downloads skipped: ${e.message}")
                        null
                    }
                } else null

                repository.updateProjectFields(
                    projectId,
                    mapOf(
                        "status" to PipelineStatus.COMPLETED.name,
                        "currentStage" to "Ready (${renderResult.width}x${renderResult.height} MP4)",
                        "progressPercent" to 100L,
                        "actualCostUsd" to runningCost,
                        "localVideoPath" to renderResult.mp4File.absolutePath,
                        "localThumbnailPath" to thumbFile.absolutePath,
                        "exportedDownloadUri" to downloadUri,
                        "videoFileSizeBytes" to renderResult.fileSizeBytes,
                        "renderedDurationSeconds" to renderResult.totalDurationSeconds.toLong()
                    )
                )
                repository.incrementUserUsage(userId, runningCost, 1L)
                sendCompletionNotification(script.title, renderResult.totalDurationSeconds)
            } catch (e: CancellationException) {
                Log.i(TAG, "Pipeline job cancelled for $projectId")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline failed for $projectId", e)
                val errMsg = e.localizedMessage ?: "Video production failed."
                recordUsage(userId, projectId, project.title, "PIPELINE_ERROR", "sarvcut-worker", 0L, 0.0, "FAILED", errMsg)
                repository.updateProjectFields(
                    projectId,
                    mapOf(
                        "status" to PipelineStatus.FAILED.name,
                        "currentStage" to "Failed — tap Retry to resume",
                        "actualCostUsd" to runningCost,
                        "errorMessage" to errMsg.take(1800)
                    )
                )
            } finally {
                activeJobs.remove(projectId)
            }
        }
        activeJobs[projectId] = job
    }

    private suspend fun updateStage(projectId: String, status: PipelineStatus, label: String, progress: Int) {
        repository.updateProjectFields(
            projectId,
            mapOf(
                "status" to status.name,
                "currentStage" to label.take(120),
                "progressPercent" to progress.coerceIn(0, 100).toLong()
            )
        )
    }

    private suspend fun recordUsage(
        userId: String,
        projectId: String,
        title: String,
        stage: String,
        model: String,
        tokens: Long,
        costUsd: Double,
        status: String,
        error: String?
    ) {
        repository.logUsage(
            UsageLog(
                id = "log_${UUID.randomUUID().toString().replace("-", "").take(16)}",
                userId = userId,
                projectId = projectId,
                projectTitle = title,
                stage = stage,
                modelName = model,
                estimatedTokens = tokens,
                costUsd = costUsd,
                status = status,
                errorMessage = error
            )
        )
    }

    private fun sendCompletionNotification(title: String, durationSec: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "SARVCUT Video Exports", NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Video Ready: ${title.take(40)}")
                .setContentText("Your ${durationSec}s MP4 video finished rendering in SARVCUT.")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(title.hashCode(), notif)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show completion notification: ${e.message}")
        }
    }
}
