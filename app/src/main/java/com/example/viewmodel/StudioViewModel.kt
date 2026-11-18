package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.LocalStudioSettings
import com.example.core.StudioPreferences
import com.example.data.model.AppConfig
import com.example.data.model.AspectRatioOption
import com.example.data.model.ContentReport
import com.example.data.model.CostEstimate
import com.example.data.model.ProductionMode
import com.example.data.model.ResearchReport
import com.example.data.model.ThumbnailStyle
import com.example.data.model.UsageLog
import com.example.data.model.UserProfile
import com.example.data.model.VideoCreationRequest
import com.example.data.model.VideoFormat
import com.example.data.model.VideoProject
import com.example.data.model.VideoScene
import com.example.data.model.VoiceGender
import com.example.data.model.VoiceStyle
import com.example.data.repository.FirestoreRepository
import com.example.pipeline.MediaExportHelper
import com.example.pipeline.VideoPipelineCoordinator
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "StudioVM"
private const val ADMIN_EMAIL = "skumari86555@gmail.com"

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class CreateDraftState(
    val topic: String = "",
    val format: VideoFormat = VideoFormat.DOCUMENTARY,
    val aspectRatio: AspectRatioOption = AspectRatioOption.YOUTUBE_16_9,
    val language: String = "English",
    val translatedLanguage: String = "",
    val targetDurationSeconds: Int = 45,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val voiceStyle: VoiceStyle = VoiceStyle.CINEMATIC,
    val productionMode: ProductionMode = ProductionMode.STANDARD_HYBRID,
    val thumbnailStyle: ThumbnailStyle = ThumbnailStyle.BOLD_CINEMA
)

class StudioViewModel(
    private val repository: FirestoreRepository,
    private val coordinator: VideoPipelineCoordinator,
    private val preferences: StudioPreferences,
    private val exportHelper: MediaExportHelper,
    val currentUserId: String,
    val currentUserEmail: String
) : ViewModel() {

    val isAdmin: Boolean = currentUserEmail.equals(ADMIN_EMAIL, ignoreCase = true)

    private val _draft = MutableStateFlow(CreateDraftState())
    val draft: StateFlow<CreateDraftState> = _draft.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val userProfile: StateFlow<UiState<UserProfile?>> = wrapToUiState(
        repository.observeUserProfile(currentUserId),
        "Failed to load profile",
        null
    )

    val projects: StateFlow<UiState<List<VideoProject>>> = wrapToUiState(
        repository.observeUserProjects(currentUserId),
        "Failed to load projects",
        emptyList()
    )

    val researchReports: StateFlow<UiState<List<ResearchReport>>> = wrapToUiState(
        repository.observeResearchReports(currentUserId),
        "Failed to load research reports",
        emptyList()
    )

    val usageLogs: StateFlow<UiState<List<UsageLog>>> = wrapToUiState(
        repository.observeUsageLogs(currentUserId),
        "Failed to load usage history",
        emptyList()
    )

    val appConfig: StateFlow<AppConfig> = repository.observeAppConfig()
        .catch { error ->
            if (error is CancellationException &&
                generateSequence(error as Throwable) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull() == null
            ) throw error
            emit(AppConfig())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AppConfig())

    val localSettings: StateFlow<LocalStudioSettings> = preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LocalStudioSettings())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedProject: StateFlow<VideoProject?> = _selectedProjectId
        .flatMapLatest { pid ->
            if (pid.isNullOrBlank()) flowOf(null)
            else repository.observeProject(pid).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedProjectScenes: StateFlow<List<VideoScene>> = _selectedProjectId
        .flatMapLatest { pid ->
            if (pid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeProjectScenes(pid, currentUserId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Admin streams (instantiated safely only if isAdmin is true)
    val adminUsers: StateFlow<List<UserProfile>> = if (isAdmin) {
        repository.observeAllUsersAdmin().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else MutableStateFlow(emptyList())

    val adminProjects: StateFlow<List<VideoProject>> = if (isAdmin) {
        repository.observeAllProjectsAdmin().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else MutableStateFlow(emptyList())

    val adminUsageLogs: StateFlow<List<UsageLog>> = if (isAdmin) {
        repository.observeAllUsageLogsAdmin().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else MutableStateFlow(emptyList())

    val adminReports: StateFlow<List<ContentReport>> = if (isAdmin) {
        repository.observeAllContentReportsAdmin().catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else MutableStateFlow(emptyList())

    init {
        viewModelScope.launch {
            val user = Firebase.auth.currentUser
            if (user != null && user.uid == currentUserId) {
                repository.ensureUserProfile(user).onSuccess { profile ->
                    _draft.update {
                        it.copy(
                            aspectRatio = AspectRatioOption.fromWire(profile.defaultAspectRatio),
                            language = profile.defaultLanguage,
                            productionMode = if (profile.preferLowCostImageMode) {
                                ProductionMode.LOW_COST_IMAGE_ONLY
                            } else ProductionMode.STANDARD_HYBRID
                        )
                    }
                }
            }
        }
    }

    private fun <T> wrapToUiState(flow: Flow<T>, fallbackMsg: String, emptyInitial: T): StateFlow<UiState<T>> {
        return flow
            .map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, fallbackMsg, rootError)
                emit(UiState.Error(rootError.localizedMessage ?: fallbackMsg))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)
    }

    fun updateDraft(transform: (CreateDraftState) -> CreateDraftState) {
        _draft.update(transform)
    }

    fun computeCurrentEstimate(): CostEstimate {
        val d = _draft.value
        val profile = (userProfile.value as? UiState.Success)?.data
        val remaining = ((profile?.spendingLimitUsd ?: 25.0) - (profile?.totalSpentUsd ?: 0.0)).coerceAtLeast(0.0)
        return coordinator.estimateCost(d.targetDurationSeconds, d.productionMode, remaining)
    }

    fun startCreateVideo(onNavigateToProject: (String) -> Unit) {
        val d = _draft.value
        val profile = (userProfile.value as? UiState.Success)?.data
        val remaining = ((profile?.spendingLimitUsd ?: 25.0) - (profile?.totalSpentUsd ?: 0.0)).coerceAtLeast(0.0)
        val settings = localSettings.value

        val req = VideoCreationRequest(
            topic = d.topic,
            format = d.format,
            aspectRatio = d.aspectRatio,
            language = d.language,
            translatedLanguage = d.translatedLanguage.takeIf { it.isNotBlank() && it != d.language },
            targetDurationSeconds = d.targetDurationSeconds,
            voiceGender = d.voiceGender,
            voiceStyle = d.voiceStyle,
            productionMode = d.productionMode,
            thumbnailStyle = d.thumbnailStyle,
            burnSubtitles = settings.burnSubtitlesInMp4,
            addAmbientMusic = settings.addAmbientMusicBed
        )

        coordinator.startNewVideoJob(
            userId = currentUserId,
            request = req,
            appConfig = appConfig.value,
            remainingBudgetUsd = remaining,
            autoSaveToDownloads = settings.autoSaveToDownloads,
            onJobCreated = { projectId ->
                _draft.update { it.copy(topic = "") }
                _selectedProjectId.value = projectId
                onNavigateToProject(projectId)
            },
            onError = { msg ->
                _bannerMessage.value = msg
            }
        )
    }

    fun selectProject(projectId: String?) {
        _selectedProjectId.value = projectId
    }

    fun cancelProject(projectId: String) {
        coordinator.cancelProjectJob(projectId)
        _bannerMessage.value = "Pipeline job cancellation requested."
    }

    fun retryProject(project: VideoProject) {
        val settings = localSettings.value
        coordinator.retryProjectJob(
            userId = currentUserId,
            project = project,
            appConfig = appConfig.value,
            burnSubtitles = settings.burnSubtitlesInMp4,
            addAmbientMusic = settings.addAmbientMusicBed,
            autoSaveToDownloads = settings.autoSaveToDownloads
        )
        _bannerMessage.value = "Retrying production pipeline..."
    }

    fun deleteProject(projectId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            _isBusy.value = true
            val res = repository.deleteProject(projectId)
            _isBusy.value = false
            if (res.isSuccess) {
                if (_selectedProjectId.value == projectId) _selectedProjectId.value = null
                _bannerMessage.value = "Project deleted."
                onDeleted()
            } else {
                _bannerMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete project."
            }
        }
    }

    fun saveMp4ToDownloads(project: VideoProject) {
        viewModelScope.launch {
            _isBusy.value = true
            exportHelper.saveMp4ToDownloads(project)
                .onSuccess { uriString ->
                    repository.updateProjectFields(project.id, mapOf("exportedDownloadUri" to uriString))
                    _bannerMessage.value = "Saved MP4 to Downloads/SARVCUT"
                }
                .onFailure { err ->
                    _bannerMessage.value = err.localizedMessage ?: "Failed to export MP4."
                }
            _isBusy.value = false
        }
    }

    fun shareMp4(project: VideoProject) {
        exportHelper.shareMp4File(project)
            .onFailure { err ->
                _bannerMessage.value = err.localizedMessage ?: "Unable to share MP4."
            }
    }

    fun exportThumbnail(
        project: VideoProject,
        customTitle: String,
        style: ThumbnailStyle,
        firstSceneImagePath: String?,
        saveToDownloads: Boolean,
        asPng: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            exportHelper.regenerateAndSaveThumbnail(
                project = project,
                customTitle = customTitle,
                style = style,
                firstSceneImagePath = firstSceneImagePath,
                exportToDownloads = saveToDownloads,
                asPng = asPng
            ).onSuccess { (localPath, _) ->
                repository.updateProjectFields(
                    project.id,
                    mapOf(
                        "thumbnailStyle" to style.name,
                        "localThumbnailPath" to localPath
                    )
                )
                _bannerMessage.value = if (saveToDownloads) {
                    "Thumbnail saved to Downloads/SARVCUT (${if (asPng) "PNG" else "JPG"})"
                } else {
                    "Thumbnail updated (${style.label})"
                }
            }.onFailure { err ->
                _bannerMessage.value = err.localizedMessage ?: "Thumbnail export failed."
            }
            _isBusy.value = false
        }
    }

    fun updateAccountSettings(
        spendingLimitUsd: Double,
        defaultAspectRatio: String,
        defaultLanguage: String,
        preferLowCostImageMode: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.updateUserSettings(
                currentUserId, spendingLimitUsd, defaultAspectRatio, defaultLanguage, preferLowCostImageMode
            ).onSuccess {
                _bannerMessage.value = "Account settings saved."
            }.onFailure { err ->
                _bannerMessage.value = err.localizedMessage ?: "Failed to save settings."
            }
            _isBusy.value = false
        }
    }

    fun setLocalPreferences(burnSubtitles: Boolean, ambientMusic: Boolean, autoSaveDownloads: Boolean) {
        viewModelScope.launch {
            preferences.setBurnSubtitles(burnSubtitles)
            preferences.setAmbientMusic(ambientMusic)
            preferences.setAutoSaveDownloads(autoSaveDownloads)
        }
    }

    fun submitReport(project: VideoProject, reason: String) {
        viewModelScope.launch {
            val report = ContentReport(
                id = "creport_${UUID.randomUUID().toString().replace("-", "").take(14)}",
                userId = currentUserId,
                projectId = project.id,
                topic = project.topic,
                reason = reason
            )
            repository.submitContentReport(report)
                .onSuccess { _bannerMessage.value = "Report submitted for moderation review." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to submit report." }
        }
    }

    fun updateAdminConfig(config: AppConfig) {
        if (!isAdmin) return
        viewModelScope.launch {
            repository.updateAppConfigAdmin(config)
                .onSuccess { _bannerMessage.value = "Platform provider configuration updated." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to update config." }
        }
    }

    fun resolveContentReport(reportId: String, status: String) {
        if (!isAdmin) return
        viewModelScope.launch {
            repository.resolveContentReportAdmin(reportId, status)
                .onSuccess { _bannerMessage.value = "Report marked $status." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to update report." }
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    companion object {
        fun provideFactory(
            repository: FirestoreRepository,
            coordinator: VideoPipelineCoordinator,
            preferences: StudioPreferences,
            exportHelper: MediaExportHelper,
            currentUserId: String,
            currentUserEmail: String
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StudioViewModel(
                    repository = repository,
                    coordinator = coordinator,
                    preferences = preferences,
                    exportHelper = exportHelper,
                    currentUserId = currentUserId,
                    currentUserEmail = currentUserEmail
                )
            }
        }
    }
}
