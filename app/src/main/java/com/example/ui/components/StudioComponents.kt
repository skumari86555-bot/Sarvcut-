package com.example.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.PipelineStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CarbonBorder
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CarbonSurfaceElevated
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletBright
import com.example.ui.theme.VioletPulse
import java.io.File

@Composable
fun StudioBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x308B5CF6), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.08f),
                        radius = size.maxDimension * 0.55f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x1E22D3EE), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.72f),
                        radius = size.maxDimension * 0.45f
                    )
                )
            }
    ) {
        content()
    }
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    borderColor: Color = CarbonBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (elevated) CarbonSurfaceElevated else CarbonSurface,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = if (elevated) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun StatusBadge(status: PipelineStatus, modifier: Modifier = Modifier) {
    val (bg, fg, icon) = when (status) {
        PipelineStatus.COMPLETED -> Triple(EmeraldSuccess.copy(alpha = 0.16f), EmeraldSuccess, Icons.Default.CheckCircle)
        PipelineStatus.FAILED -> Triple(CrimsonError.copy(alpha = 0.16f), CrimsonError, Icons.Default.ErrorOutline)
        PipelineStatus.CANCELLED -> Triple(AmberWarn.copy(alpha = 0.16f), AmberWarn, Icons.Default.ErrorOutline)
        else -> Triple(VioletPulse.copy(alpha = 0.18f), VioletBright, Icons.Default.Schedule)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = status.label, tint = fg, modifier = Modifier.size(14.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VioletPulse.copy(alpha = 0.18f))
                    .border(1.dp, VioletPulse.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = VioletBright, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

@Composable
fun PipelineProgressCard(
    status: PipelineStatus,
    stageLabel: String,
    progressPercent: Int,
    jobId: String,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (progressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(),
        label = "pipelineProgress"
    )
    val stages = listOf(
        "Topic", "Research", "Verify", "Script", "Scenes", "Voice", "Visuals", "Edit", "Subtitles", "Thumb", "MP4"
    )
    val completedSteps = when (status) {
        PipelineStatus.QUEUED -> 1
        PipelineStatus.RESEARCHING -> 2
        PipelineStatus.VERIFYING -> 3
        PipelineStatus.SCRIPTING -> 5
        PipelineStatus.VOICE_GEN -> 6
        PipelineStatus.VISUAL_GEN -> 7
        PipelineStatus.RENDERING -> 9
        PipelineStatus.THUMBNAIL_GEN -> 10
        PipelineStatus.COMPLETED -> 11
        PipelineStatus.FAILED, PipelineStatus.CANCELLED -> (progressPercent / 10).coerceIn(1, 10)
    }

    StudioCard(
        modifier = modifier,
        elevated = true,
        borderColor = if (status == PipelineStatus.FAILED) CrimsonError.copy(alpha = 0.5f) else VioletPulse.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(status)
            Text(
                text = "$progressPercent%  ·  $jobId",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFamily,
                color = CyanTelemetry
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stageLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = when (status) {
                PipelineStatus.COMPLETED -> EmeraldSuccess
                PipelineStatus.FAILED -> CrimsonError
                else -> VioletPulse
            },
            trackColor = CarbonBorder
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            stages.forEachIndexed { idx, label ->
                val done = idx < completedSteps
                val active = idx == completedSteps - 1 && !status.isTerminal
                val dotColor by animateColorAsState(
                    targetValue = when {
                        done -> EmeraldSuccess
                        active -> VioletBright
                        else -> CarbonBorder
                    },
                    label = "stageDot"
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(if (active) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    if (idx % 2 == 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (done || active) TextSecondary else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Mp4VideoPlayerCard(
    videoPath: String,
    aspectRatio: Float,
    modifier: Modifier = Modifier
) {
    val file = remember(videoPath) { File(videoPath) }
    var isStarted by remember(videoPath) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.5f, 1.85f))
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black)
            .border(1.dp, CarbonBorder, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center
    ) {
        if (!file.exists()) {
            Text(
                text = "Video file not available on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else if (!isStarted) {
            Surface(
                onClick = { isStarted = true },
                shape = CircleShape,
                color = VioletPulse.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(64.dp)
                    .testTag("play_mp4_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play rendered MP4 video",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Text(
                text = "Tap to play rendered MP4",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            start()
                        }
                    }
                },
                update = { view ->
                    if (!view.isPlaying) {
                        view.start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "${"%.1f".format(mb)} MB" else "${(bytes / 1024L).coerceAtLeast(1)} KB"
}

fun formatDurationSeconds(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}m ${s.toString().padStart(2, '0')}s" else "${s}s"
}
