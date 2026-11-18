package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.example.data.model.PipelineStatus
import com.example.data.model.ThumbnailStyle
import com.example.data.model.VideoProject
import com.example.data.model.VideoScene
import com.example.ui.components.Mp4VideoPlayerCard
import com.example.ui.components.PipelineProgressCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.components.StudioCard
import com.example.ui.components.formatDurationSeconds
import com.example.ui.components.formatFileSize
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurfaceElevated
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletBright
import com.example.ui.theme.VioletPulse
import com.example.viewmodel.StudioViewModel
import com.example.viewmodel.UiState
import java.io.File

private enum class ProjectFilter(val label: String) {
    ALL("All Projects"),
    ACTIVE("Active Jobs"),
    COMPLETED("Completed MP4"),
    DOWNLOADED("Downloads")
}

@Composable
fun ProjectsScreen(
    viewModel: StudioViewModel,
    onOpenProject: (String) -> Unit
) {
    val projectsState by viewModel.projects.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ProjectFilter.ALL) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SectionHeader(
                title = "Projects & Downloads",
                subtitle = "Monitor background jobs, play finished MP4s, and export files",
                icon = Icons.Default.VideoLibrary
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("filter_${item.name.lowercase()}")
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            when (val state = projectsState) {
                is UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VioletPulse)
                    }
                }
                is UiState.Error -> {
                    StudioCard(borderColor = CrimsonError) {
                        Text(state.message, color = CrimsonError, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is UiState.Success -> {
                    val filtered = state.data.filter { proj ->
                        val status = proj.statusEnum()
                        when (filter) {
                            ProjectFilter.ALL -> true
                            ProjectFilter.ACTIVE -> !status.isTerminal
                            ProjectFilter.COMPLETED -> status == PipelineStatus.COMPLETED
                            ProjectFilter.DOWNLOADED -> !proj.exportedDownloadUri.isNullOrBlank()
                        }
                    }

                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "No videos in this view yet.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Open Create to turn any topic into a 1080p MP4.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(filtered, key = { it.id }) { project ->
                                ProjectListCard(
                                    project = project,
                                    onClick = { onOpenProject(project.id) },
                                    onCancel = { viewModel.cancelProject(project.id) },
                                    onRetry = { viewModel.retryProject(project) },
                                    onSaveDownload = { viewModel.saveMp4ToDownloads(project) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectListCard(
    project: VideoProject,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSaveDownload: () -> Unit
) {
    val status = project.statusEnum()
    StudioCard(
        elevated = !status.isTerminal,
        borderColor = if (!status.isTerminal) VioletPulse.copy(alpha = 0.55f) else CarbonBorder,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("project_card_${project.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumbFile = project.localThumbnailPath?.let { File(it) }?.takeIf { it.exists() }
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(CarbonSurfaceElevated)
                    .border(1.dp, CarbonBorder, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                if (thumbFile != null) {
                    Image(
                        painter = rememberAsyncImagePainter(thumbFile),
                        contentDescription = "${project.title} thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = VioletBright)
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(status)
                    Text(
                        text = "${project.aspectRatio} · ${project.language}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                }
            }
        }

        if (!status.isTerminal) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (project.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = VioletPulse,
                trackColor = CarbonBorder
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${project.progressPercent}% · ${project.currentStage}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = AmberWarn, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = AmberWarn)
                }
            }
        } else if (status == PipelineStatus.COMPLETED) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatDurationSeconds(project.renderedDurationSeconds.toInt())} · ${formatFileSize(project.videoFileSizeBytes)} · $${"%.3f".format(project.actualCostUsd)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFamily,
                    color = CyanTelemetry
                )
                TextButton(onClick = onSaveDownload, modifier = Modifier.testTag("quick_download_${project.id}")) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save MP4", color = EmeraldSuccess)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.errorMessage ?: "Production interrupted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrimsonError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = VioletBright, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", color = VioletBright)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDetailScreen(
    viewModel: StudioViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val project by viewModel.selectedProject.collectAsStateWithLifecycle()
    val scenes by viewModel.selectedProjectScenes.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var customThumbTitle by remember(project?.id) { mutableStateOf(project?.title.orEmpty()) }
    var selectedThumbStyle by remember(project?.id) {
        mutableStateOf(project?.thumbnailStyleEnum() ?: ThumbnailStyle.BOLD_CINEMA)
    }

    val current = project
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = VioletPulse)
        }
        return
    }

    val status = current.statusEnum()
    val firstSceneImage = scenes.firstOrNull { !it.localImagePath.isNullOrBlank() }?.localImagePath

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back, report, delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_detail")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to projects",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${current.formatEnum().label} · ${current.aspectRatio} · ${current.language}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = { showReportDialog = true }) {
                    Icon(Icons.Default.Flag, contentDescription = "Report content", tint = TextSecondary)
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete project", tint = CrimsonError)
                }
            }

            // Live 11-Stage Pipeline Progress
            PipelineProgressCard(
                status = status,
                stageLabel = current.currentStage,
                progressPercent = current.progressPercent.toInt(),
                jobId = current.jobId
            )

            // Action row for active / failed / cancelled jobs
            if (!status.isTerminal) {
                OutlinedButton(
                    onClick = { viewModel.cancelProject(current.id) },
                    border = BorderStroke(1.dp, AmberWarn),
                    modifier = Modifier.fillMaxWidth().testTag("cancel_job_button")
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = AmberWarn)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel Active Job", color = AmberWarn)
                }
            } else if (status == PipelineStatus.FAILED || status == PipelineStatus.CANCELLED) {
                if (!current.errorMessage.isNullOrBlank()) {
                    StudioCard(borderColor = CrimsonError.copy(alpha = 0.6f)) {
                        Text("Error Details", style = MaterialTheme.typography.titleMedium, color = CrimsonError)
                        Spacer(Modifier.height(4.dp))
                        Text(current.errorMessage, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
                Button(
                    onClick = { viewModel.retryProject(current) },
                    colors = ButtonDefaults.buttonColors(containerColor = VioletPulse),
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("retry_job_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Retry Production Pipeline")
                }
            }

            // Completed MP4 Player & Download Actions
            if (status == PipelineStatus.COMPLETED && !current.localVideoPath.isNullOrBlank()) {
                val playerRatio = if (current.aspectRatio == "9:16") 9f / 16f else 16f / 9f
                Mp4VideoPlayerCard(
                    videoPath = current.localVideoPath,
                    aspectRatio = playerRatio
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveMp4ToDownloads(current) },
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = Color.Black),
                        modifier = Modifier.weight(1f).height(50.dp).testTag("download_mp4_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save MP4", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.shareMp4(current) },
                        border = BorderStroke(1.dp, VioletBright),
                        modifier = Modifier.weight(1f).height(50.dp).testTag("share_mp4_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = VioletBright)
                        Spacer(Modifier.width(6.dp))
                        Text("Share MP4", color = VioletBright)
                    }
                }

                Text(
                    text = "Export: ${formatDurationSeconds(current.renderedDurationSeconds.toInt())} · ${formatFileSize(current.videoFileSizeBytes)} · H.264 / AAC · Cost $${"%.3f".format(current.actualCostUsd)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFamily,
                    color = CyanTelemetry
                )

                // Thumbnail Studio
                StudioCard {
                    SectionHeader(
                        title = "Thumbnail Studio",
                        subtitle = "Customize design & export JPG or PNG to Downloads",
                        icon = Icons.Default.Image
                    )
                    Spacer(Modifier.height(12.dp))
                    val thumbFile = current.localThumbnailPath?.let { File(it) }?.takeIf { it.exists() }
                    if (thumbFile != null) {
                        Image(
                            painter = rememberAsyncImagePainter(thumbFile),
                            contentDescription = "Generated Thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (current.aspectRatio == "9:16") 9f / 16f else 16f / 9f)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedTextField(
                        value = customThumbTitle,
                        onValueChange = { customThumbTitle = it.take(100) },
                        label = { Text("Thumbnail Headline") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThumbnailStyle.entries.forEach { ts ->
                            FilterChip(
                                selected = selectedThumbStyle == ts,
                                onClick = { selectedThumbStyle = ts },
                                label = { Text(ts.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                viewModel.exportThumbnail(current, customThumbTitle, selectedThumbStyle, firstSceneImage, saveToDownloads = true, asPng = false)
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export JPG")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.exportThumbnail(current, customThumbTitle, selectedThumbStyle, firstSceneImage, saveToDownloads = true, asPng = true)
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export PNG")
                        }
                    }
                }
            }

            // Research & Verification Summary
            if (!current.researchSummary.isNullOrBlank()) {
                StudioCard {
                    SectionHeader(
                        title = "Research & Fact Verification",
                        subtitle = "Grounded findings and cross-source audit",
                        icon = Icons.AutoMirrored.Filled.FactCheck
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(current.researchSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (!current.verificationVerdict.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Verification: ${current.verificationVerdict}", style = MaterialTheme.typography.bodyMedium, color = EmeraldSuccess)
                    }
                    if (!current.uncertaintyFlags.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Uncertainty / Conflicts: ${current.uncertaintyFlags}", style = MaterialTheme.typography.bodyMedium, color = AmberWarn)
                    }
                }
            }

            // Scenes & Subtitles Breakdown
            if (scenes.isNotEmpty()) {
                StudioCard {
                    SectionHeader(
                        title = "Script & Scene Plan (${scenes.size})",
                        subtitle = "Aligned voice narration, visual prompts, and subtitles",
                        icon = Icons.Default.Subtitles
                    )
                    Spacer(Modifier.height(10.dp))
                    if (!current.scriptIntro.isNullOrBlank()) {
                        Text("INTRO: ${current.scriptIntro}", style = MaterialTheme.typography.bodyMedium, color = VioletBright)
                        Spacer(Modifier.height(8.dp))
                    }
                    scenes.forEach { scene ->
                        SceneRowCard(scene)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (!current.scriptOutro.isNullOrBlank()) {
                        Text("OUTRO: ${current.scriptOutro}", style = MaterialTheme.typography.bodyMedium, color = VioletBright)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Project?") },
            text = { Text("This deletes the project, scene plan, and Firestore record permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteProject(current.id, onDeleted = onBack)
                }) {
                    Text("Delete", color = CrimsonError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report Content") },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { reportReason = it.take(500) },
                    label = { Text("Reason for moderation review") },
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(
                    enabled = reportReason.trim().length >= 3,
                    onClick = {
                        viewModel.submitReport(current, reportReason)
                        reportReason = ""
                        showReportDialog = false
                    }
                ) {
                    Text("Submit", color = VioletBright)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SceneRowCard(scene: VideoScene) {
    Surface(
        color = CarbonSurfaceElevated,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, CarbonBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            val imgFile = scene.localImagePath?.let { File(it) }?.takeIf { it.exists() }
            if (imgFile != null) {
                Image(
                    painter = rememberAsyncImagePainter(imgFile),
                    contentDescription = scene.heading,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${scene.sceneIndex + 1}. ${scene.heading}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${scene.durationSeconds}s",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFamily,
                        color = CyanTelemetry
                    )
                }
                Text(scene.narration, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                if (!scene.translatedNarration.isNullOrBlank()) {
                    Text(
                        text = "Translation: ${scene.translatedNarration}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VioletBright
                    )
                }
                Text(
                    text = "Visual: ${scene.visualPrompt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
