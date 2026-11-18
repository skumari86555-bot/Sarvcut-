package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class VideoFormat(val label: String, val description: String) {
    DOCUMENTARY("Documentary", "Investigative depth, verified facts, cinematic pacing"),
    EDUCATIONAL("Educational", "Structured lessons, clear takeaways, visual diagrams"),
    EXPLAINER("Explainer", "Crisp breakdown of complex topics for general audiences"),
    STORYTELLING("Storytelling", "Narrative arc, emotional hooks, dramatic scene flow");

    companion object {
        fun fromRaw(raw: String?): VideoFormat =
            entries.firstOrNull { it.name == raw } ?: DOCUMENTARY
    }
}

enum class AspectRatioOption(val wireValue: String, val label: String, val widthPx: Int, val heightPx: Int) {
    YOUTUBE_16_9("16:9", "16:9 YouTube", 1920, 1080),
    SHORTS_9_16("9:16", "9:16 Shorts", 1080, 1920);

    companion object {
        fun fromWire(raw: String?): AspectRatioOption =
            entries.firstOrNull { it.wireValue == raw } ?: YOUTUBE_16_9
    }
}

enum class VoiceGender(val label: String) {
    FEMALE("Female"),
    MALE("Male");

    companion object {
        fun fromRaw(raw: String?): VoiceGender =
            entries.firstOrNull { it.name == raw } ?: FEMALE
    }
}

enum class VoiceStyle(val label: String, val speechRate: Float, val pitchShift: Float) {
    CINEMATIC("Cinematic", 0.96f, 0.96f),
    ENERGETIC("Energetic", 1.12f, 1.06f),
    CALM("Calm", 0.90f, 0.98f),
    AUTHORITATIVE("Authoritative", 1.0f, 0.90f);

    companion object {
        fun fromRaw(raw: String?): VoiceStyle =
            entries.firstOrNull { it.name == raw } ?: CINEMATIC
    }
}

enum class ProductionMode(val label: String, val subtitle: String, val costMultiplier: Double) {
    STANDARD_HYBRID("Standard Studio", "Per-scene AI visuals + motion editing + 1080p MP4", 1.0),
    LOW_COST_IMAGE_ONLY("Eco Image-Based", "Fewer image calls, smart frame reuse, ~45% lower cost", 0.55);

    companion object {
        fun fromRaw(raw: String?): ProductionMode =
            entries.firstOrNull { it.name == raw } ?: STANDARD_HYBRID
    }
}

enum class PipelineStatus(val label: String, val isTerminal: Boolean) {
    QUEUED("Queued", false),
    RESEARCHING("Researching Web", false),
    VERIFYING("Verifying Facts", false),
    SCRIPTING("Writing Script", false),
    VOICE_GEN("Synthesizing Voice", false),
    VISUAL_GEN("Generating Visuals", false),
    RENDERING("Rendering 1080p MP4", false),
    THUMBNAIL_GEN("Creating Thumbnail", false),
    COMPLETED("Completed", true),
    FAILED("Failed", true),
    CANCELLED("Cancelled", true);

    companion object {
        fun fromRaw(raw: String?): PipelineStatus =
            entries.firstOrNull { it.name == raw } ?: QUEUED
    }
}

enum class SceneStatus {
    PLANNED,
    VOICE_READY,
    VISUAL_READY,
    COMPLETED,
    FAILED;

    companion object {
        fun fromRaw(raw: String?): SceneStatus =
            entries.firstOrNull { it.name == raw } ?: PLANNED
    }
}

enum class ThumbnailStyle(val label: String) {
    BOLD_CINEMA("Bold Cinema"),
    EDITORIAL_SPLIT("Editorial Split"),
    NEON_PULSE("Neon Pulse"),
    MINIMAL_DOC("Minimal Doc");

    companion object {
        fun fromRaw(raw: String?): ThumbnailStyle =
            entries.firstOrNull { it.name == raw } ?: BOLD_CINEMA
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val spendingLimitUsd: Double = 25.0,
    val totalSpentUsd: Double = 0.0,
    val totalVideosCreated: Long = 0L,
    val defaultAspectRatio: String = "16:9",
    val defaultLanguage: String = "English",
    val preferLowCostImageMode: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VideoProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val jobId: String = "",
    val title: String = "",
    val topic: String = "",
    val format: String = VideoFormat.DOCUMENTARY.name,
    val aspectRatio: String = AspectRatioOption.YOUTUBE_16_9.wireValue,
    val language: String = "English",
    val translatedLanguage: String? = null,
    val targetDurationSeconds: Long = 60L,
    val voiceGender: String = VoiceGender.FEMALE.name,
    val voiceStyle: String = VoiceStyle.CINEMATIC.name,
    val productionMode: String = ProductionMode.STANDARD_HYBRID.name,
    val status: String = PipelineStatus.QUEUED.name,
    val currentStage: String = "Queued",
    val progressPercent: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val actualCostUsd: Double = 0.0,
    val researchSummary: String? = null,
    val verificationVerdict: String? = null,
    val uncertaintyFlags: String? = null,
    val scriptIntro: String? = null,
    val scriptOutro: String? = null,
    val thumbnailStyle: String = ThumbnailStyle.BOLD_CINEMA.name,
    val localVideoPath: String? = null,
    val localThumbnailPath: String? = null,
    val exportedDownloadUri: String? = null,
    val videoFileSizeBytes: Long = 0L,
    val renderedDurationSeconds: Long = 0L,
    val sceneCount: Long = 0L,
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun formatEnum(): VideoFormat = VideoFormat.fromRaw(format)
    fun aspectEnum(): AspectRatioOption = AspectRatioOption.fromWire(aspectRatio)
    fun statusEnum(): PipelineStatus = PipelineStatus.fromRaw(status)
    fun thumbnailStyleEnum(): ThumbnailStyle = ThumbnailStyle.fromRaw(thumbnailStyle)
    fun productionModeEnum(): ProductionMode = ProductionMode.fromRaw(productionMode)
}

@Keep
@IgnoreExtraProperties
data class VideoScene(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val sceneIndex: Long = 0L,
    val heading: String = "",
    val narration: String = "",
    val translatedNarration: String? = null,
    val visualPrompt: String = "",
    val subtitleText: String = "",
    val durationSeconds: Long = 10L,
    val status: String = SceneStatus.PLANNED.name,
    val localImagePath: String? = null,
    val localAudioPath: String? = null,
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ResearchSource(
    val title: String = "",
    val url: String = "",
    val publishedDate: String = "",
    val credibilityNote: String = ""
)

@Keep
@IgnoreExtraProperties
data class ResearchReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val topic: String = "",
    val summary: String = "",
    val verificationNotes: String = "",
    val conflictingInfo: String? = null,
    val sources: List<ResearchSource> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UsageLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val projectTitle: String = "",
    val stage: String = "",
    val modelName: String = "",
    val estimatedTokens: Long = 0L,
    val costUsd: Double = 0.0,
    val status: String = "SUCCESS",
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ContentReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val topic: String = "",
    val reason: String = "",
    val status: String = "OPEN",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AppConfig(
    @DocumentId val id: String = "global",
    val maxDurationSeconds: Long = 1200L,
    val defaultSpendingLimitUsd: Double = 25.0,
    val searchGroundingEnabled: Boolean = true,
    val nanoBananaEnabled: Boolean = true,
    val geminiTtsEnabled: Boolean = true,
    val veoServerAdapterEnabled: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class CostEstimate(
    val estimatedScenes: Int,
    val estimatedImages: Int,
    val estimatedTokens: Int,
    val estimatedCostUsd: Double,
    val exceedsBudget: Boolean,
    val remainingBudgetUsd: Double
)

data class ProviderAdapterStatus(
    val name: String,
    val modelOrEngine: String,
    val availableOnClient: Boolean,
    val statusNote: String
)

data class VideoCreationRequest(
    val topic: String,
    val format: VideoFormat,
    val aspectRatio: AspectRatioOption,
    val language: String,
    val translatedLanguage: String?,
    val targetDurationSeconds: Int,
    val voiceGender: VoiceGender,
    val voiceStyle: VoiceStyle,
    val productionMode: ProductionMode,
    val thumbnailStyle: ThumbnailStyle,
    val burnSubtitles: Boolean,
    val addAmbientMusic: Boolean
)
