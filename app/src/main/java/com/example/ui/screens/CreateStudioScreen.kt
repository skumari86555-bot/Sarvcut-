package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AspectRatioOption
import com.example.data.model.ProductionMode
import com.example.data.model.ThumbnailStyle
import com.example.data.model.VideoFormat
import com.example.data.model.VoiceGender
import com.example.data.model.VoiceStyle
import com.example.ui.components.SectionHeader
import com.example.ui.components.StudioCard
import com.example.ui.components.formatDurationSeconds
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
import com.example.ui.theme.VioletDeep
import com.example.ui.theme.VioletPulse
import com.example.viewmodel.StudioViewModel

private val SUPPORTED_LANGUAGES = listOf(
    "English", "Hindi", "Spanish", "French", "German", "Japanese", "Portuguese", "Arabic", "Korean", "Mandarin"
)

private val STARTER_TOPICS = listOf(
    "How deep-sea creatures create bioluminescence",
    "The engineering behind reusable orbital rockets",
    "Why quantum computers use superconducting qubits",
    "The hidden underground mycelium network of forests"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateStudioScreen(
    viewModel: StudioViewModel,
    onProjectStarted: (String) -> Unit
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val appConfig by viewModel.appConfig.collectAsStateWithLifecycle()
    val profileState by viewModel.userProfile.collectAsStateWithLifecycle()
    val estimate = viewModel.computeCurrentEstimate()

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startCreateVideo(onProjectStarted)
    }

    val launchJob = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startCreateVideo(onProjectStarted)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Studio Header
            StudioCard(
                elevated = true,
                borderColor = VioletPulse.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUTONOMOUS VIDEO STUDIO",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanTelemetry
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Topic to Finished 1080p MP4",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VioletPulse.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MovieCreation,
                            contentDescription = null,
                            tint = VioletBright,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Enter an idea below. SARVCUT researches the web, verifies facts, writes scenes, synthesizes narration, generates visuals, and edits your MP4 automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            // Topic Prompt Card
            StudioCard {
                SectionHeader(
                    title = "1. Video Topic or Idea",
                    subtitle = "Any subject — science, history, tech, storytelling, explainers",
                    icon = Icons.Default.AutoAwesome
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.topic,
                    onValueChange = { next -> viewModel.updateDraft { it.copy(topic = next.take(500)) } },
                    placeholder = {
                        Text(
                            "e.g. How James Webb Telescope sees the earliest galaxies",
                            color = TextMuted
                        )
                    },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VioletPulse,
                        unfocusedBorderColor = CarbonBorder,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = VioletBright
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("topic_input")
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Quick Inspiration:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    STARTER_TOPICS.forEach { idea ->
                        Surface(
                            onClick = { viewModel.updateDraft { it.copy(topic = idea) } },
                            shape = RoundedCornerShape(50),
                            color = CarbonSurfaceElevated,
                            border = BorderStroke(1.dp, CarbonBorder)
                        ) {
                            Text(
                                text = idea,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VioletBright,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            // Format, Aspect Ratio & Duration
            StudioCard {
                SectionHeader(
                    title = "2. Format & Dimensions",
                    subtitle = "YouTube 16:9 or Shorts 9:16, from 30s to 20m",
                    icon = Icons.Default.Tune
                )
                Spacer(Modifier.height(12.dp))
                Text("Video Style", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoFormat.entries.forEach { fmt ->
                        val selected = draft.format == fmt
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.updateDraft { it.copy(format = fmt) } },
                            label = { Text(fmt.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletDeep,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("format_${fmt.name.lowercase()}")
                        )
                    }
                }
                Text(
                    text = draft.format.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(14.dp))
                Text("Aspect Ratio (1080p Target)", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AspectRatioOption.entries.forEach { ratio ->
                        val selected = draft.aspectRatio == ratio
                        Surface(
                            onClick = { viewModel.updateDraft { it.copy(aspectRatio = ratio) } },
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VioletDeep.copy(alpha = 0.7f) else CarbonSurfaceElevated,
                            border = BorderStroke(1.dp, if (selected) VioletBright else CarbonBorder),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("aspect_${ratio.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = ratio.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "${ratio.widthPx}×${ratio.heightPx}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = if (selected) VioletBright else TextMuted
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Target Duration", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text(
                        text = formatDurationSeconds(draft.targetDurationSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = JetBrainsMonoFamily,
                        color = CyanTelemetry
                    )
                }
                val maxDur = appConfig.maxDurationSeconds.toFloat().coerceIn(60f, 1200f)
                Slider(
                    value = draft.targetDurationSeconds.toFloat().coerceIn(30f, maxDur),
                    onValueChange = { v ->
                        val stepped = ((v / 15f).toInt() * 15).coerceIn(30, maxDur.toInt())
                        viewModel.updateDraft { it.copy(targetDurationSeconds = stepped) }
                    },
                    valueRange = 30f..maxDur,
                    colors = SliderDefaults.colors(
                        thumbColor = VioletBright,
                        activeTrackColor = VioletPulse,
                        inactiveTrackColor = CarbonBorder
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 180, 600).filter { it <= maxDur.toInt() }.forEach { preset ->
                        FilterChip(
                            selected = draft.targetDurationSeconds == preset,
                            onClick = { viewModel.updateDraft { it.copy(targetDurationSeconds = preset) } },
                            label = { Text(formatDurationSeconds(preset)) }
                        )
                    }
                }
            }

            // Language, Translation & Voiceover
            StudioCard {
                SectionHeader(
                    title = "3. Language & AI Voiceover",
                    subtitle = "Narration language, optional translation, and speaking tone",
                    icon = Icons.Default.RecordVoiceOver
                )
                Spacer(Modifier.height(12.dp))
                Text("Primary Language", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SUPPORTED_LANGUAGES.forEach { lang ->
                        FilterChip(
                            selected = draft.language == lang,
                            onClick = { viewModel.updateDraft { it.copy(language = lang) } },
                            label = { Text(lang) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Translate, contentDescription = null, tint = CyanTelemetry, modifier = Modifier.size(16.dp))
                    Text("Translate Output To (Optional)", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = draft.translatedLanguage.isBlank(),
                        onClick = { viewModel.updateDraft { it.copy(translatedLanguage = "") } },
                        label = { Text("No Translation") }
                    )
                    SUPPORTED_LANGUAGES.filter { it != draft.language }.take(5).forEach { lang ->
                        FilterChip(
                            selected = draft.translatedLanguage == lang,
                            onClick = { viewModel.updateDraft { it.copy(translatedLanguage = lang) } },
                            label = { Text(lang) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Voice Option & Style", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceGender.entries.forEach { g ->
                        FilterChip(
                            selected = draft.voiceGender == g,
                            onClick = { viewModel.updateDraft { it.copy(voiceGender = g) } },
                            label = { Text("${g.label} Voice") }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoiceStyle.entries.forEach { vs ->
                        FilterChip(
                            selected = draft.voiceStyle == vs,
                            onClick = { viewModel.updateDraft { it.copy(voiceStyle = vs) } },
                            label = { Text(vs.label) }
                        )
                    }
                }
            }

            // Production Mode, Thumbnail Style & Cost Estimator
            StudioCard(
                elevated = true,
                borderColor = if (estimate.exceedsBudget) CrimsonError else CarbonBorder
            ) {
                SectionHeader(
                    title = "4. Production Mode & Cost Estimate",
                    subtitle = "Estimated before starting — stops automatically if budget is exceeded",
                    icon = Icons.Default.Bolt
                )
                Spacer(Modifier.height(12.dp))
                ProductionMode.entries.forEach { mode ->
                    val selected = draft.productionMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (selected) VioletDeep.copy(alpha = 0.55f) else CarbonSurfaceElevated)
                            .border(1.dp, if (selected) VioletBright else CarbonBorder, MaterialTheme.shapes.small)
                            .clickable { viewModel.updateDraft { it.copy(productionMode = mode) } }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(mode.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                        if (selected) {
                            Text("SELECTED", style = MaterialTheme.typography.labelMedium, color = VioletBright)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Thumbnail Design", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThumbnailStyle.entries.forEach { ts ->
                        FilterChip(
                            selected = draft.thumbnailStyle == ts,
                            onClick = { viewModel.updateDraft { it.copy(thumbnailStyle = ts) } },
                            label = { Text(ts.label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Surface(
                    color = CarbonSurfaceElevated,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, if (estimate.exceedsBudget) CrimsonError else CarbonBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Estimated Cost", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                            Text(
                                text = "$${"%.3f".format(estimate.estimatedCostUsd)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = JetBrainsMonoFamily,
                                color = if (estimate.exceedsBudget) CrimsonError else EmeraldSuccess
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${estimate.estimatedScenes} scenes · ${estimate.estimatedImages} AI visuals · ~${estimate.estimatedTokens} tokens",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                            Text(
                                text = "Budget left: $${"%.2f".format(estimate.remainingBudgetUsd)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanTelemetry
                            )
                        }
                        if (estimate.exceedsBudget) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = AmberWarn, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Exceeds spending limit. Switch to Eco mode or raise limit in Settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AmberWarn
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = launchJob,
                    enabled = draft.topic.trim().length >= 3 && !estimate.exceedsBudget,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VioletPulse,
                        contentColor = Color.White,
                        disabledContainerColor = CarbonBorder,
                        disabledContentColor = TextMuted
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("create_video_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "CREATE VIDEO",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
