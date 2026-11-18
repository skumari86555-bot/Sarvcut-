package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.signOut
import com.example.data.model.ResearchReport
import com.example.data.model.UsageLog
import com.example.pipeline.ProviderRegistry
import com.example.ui.components.SectionHeader
import com.example.ui.components.StudioCard
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

@Composable
fun ResearchReportsScreen(viewModel: StudioViewModel) {
    val reportsState by viewModel.researchReports.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SectionHeader(
                title = "Research Reports",
                subtitle = "Verified citations, publication status, and conflict flags",
                icon = Icons.Default.TravelExplore
            )
            Spacer(Modifier.height(12.dp))

            when (val state = reportsState) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VioletPulse)
                }
                is UiState.Error -> StudioCard(borderColor = CrimsonError) {
                    Text(state.message, color = CrimsonError)
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Create a video to generate a grounded research report.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(state.data, key = { it.id }) { report ->
                                ResearchReportCard(
                                    report = report,
                                    onOpenUrl = { url ->
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        } catch (_: Exception) {
                                        }
                                    }
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
private fun ResearchReportCard(
    report: ResearchReport,
    onOpenUrl: (String) -> Unit
) {
    StudioCard {
        Text(report.topic, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(report.summary, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

        if (report.verificationNotes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Verification: ${report.verificationNotes}",
                style = MaterialTheme.typography.bodyMedium,
                color = EmeraldSuccess
            )
        }
        if (!report.conflictingInfo.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Uncertain / Conflicting: ${report.conflictingInfo}",
                style = MaterialTheme.typography.bodyMedium,
                color = AmberWarn
            )
        }

        if (report.sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("VERIFIED SOURCES (${report.sources.size})", style = MaterialTheme.typography.labelMedium, color = CyanTelemetry)
            Spacer(Modifier.height(6.dp))
            report.sources.forEach { src ->
                Surface(
                    color = CarbonSurfaceElevated,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, CarbonBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onOpenUrl(src.url) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${src.publishedDate} · ${src.url}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open source URL",
                            tint = VioletBright,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No external web URLs returned by search grounding for this query.",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
        }
    }
}

@Composable
fun UsageAndBudgetScreen(viewModel: StudioViewModel) {
    val profileState by viewModel.userProfile.collectAsStateWithLifecycle()
    val usageState by viewModel.usageLogs.collectAsStateWithLifecycle()
    val profile = (profileState as? UiState.Success)?.data

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SectionHeader(
                title = "Usage & Cost History",
                subtitle = "Track AI calls, token estimates, and spending limits",
                icon = Icons.Default.Insights
            )
            Spacer(Modifier.height(12.dp))

            val spent = profile?.totalSpentUsd ?: 0.0
            val limit = (profile?.spendingLimitUsd ?: 25.0).coerceAtLeast(0.5)
            val ratio = (spent / limit).toFloat().coerceIn(0f, 1f)

            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Cumulative Spend", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            text = "$${"%.3f".format(spent)} / $${"%.2f".format(limit)}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = JetBrainsMonoFamily,
                            color = if (ratio > 0.85f) AmberWarn else CyanTelemetry
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Videos Created", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            text = "${profile?.totalVideosCreated ?: 0L}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = JetBrainsMonoFamily,
                            color = EmeraldSuccess
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (ratio > 0.85f) AmberWarn else VioletPulse,
                    trackColor = CarbonBorder
                )
            }

            Spacer(Modifier.height(12.dp))

            when (val state = usageState) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VioletPulse)
                }
                is UiState.Error -> StudioCard(borderColor = CrimsonError) {
                    Text(state.message, color = CrimsonError)
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No API usage recorded yet.", color = TextSecondary)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(state.data, key = { it.id }) { log ->
                                UsageLogCard(log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLogCard(log: UsageLog) {
    StudioCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(log.projectTitle, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${log.stage} · ${log.modelName} · ${log.estimatedTokens} tok",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                if (!log.errorMessage.isNullOrBlank()) {
                    Text(log.errorMessage, style = MaterialTheme.typography.bodyMedium, color = CrimsonError)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (log.status == "FAILED") "ERR" else "$${"%.3f".format(log.costUsd)}",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = JetBrainsMonoFamily,
                color = if (log.status == "FAILED") CrimsonError else EmeraldSuccess
            )
        }
    }
}

@Composable
fun SettingsAndAdminScreen(viewModel: StudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profileState by viewModel.userProfile.collectAsStateWithLifecycle()
    val localSettings by viewModel.localSettings.collectAsStateWithLifecycle()
    val appConfig by viewModel.appConfig.collectAsStateWithLifecycle()
    val profile = (profileState as? UiState.Success)?.data

    var limitValue by remember(profile?.spendingLimitUsd) {
        mutableDoubleStateOf(profile?.spendingLimitUsd ?: 25.0)
    }
    var defaultRatio by remember(profile?.defaultAspectRatio) {
        mutableStateOf(profile?.defaultAspectRatio ?: "16:9")
    }
    var defaultLang by remember(profile?.defaultLanguage) {
        mutableStateOf(profile?.defaultLanguage ?: "English")
    }
    var lowCostDefault by remember(profile?.preferLowCostImageMode) {
        mutableStateOf(profile?.preferLowCostImageMode ?: false)
    }

    val adminUsers by viewModel.adminUsers.collectAsStateWithLifecycle()
    val adminProjects by viewModel.adminProjects.collectAsStateWithLifecycle()
    val adminLogs by viewModel.adminUsageLogs.collectAsStateWithLifecycle()
    val adminReports by viewModel.adminReports.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account Card
            StudioCard(elevated = true) {
                SectionHeader(
                    title = "Account & Session",
                    subtitle = viewModel.currentUserEmail.ifBlank { "Google Authenticated" },
                    icon = Icons.Default.ManageAccounts
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = profile?.displayName ?: "Sarvcut Creator",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "UID: ${viewModel.currentUserId}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = TextMuted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { signOut(credentialManager, scope) },
                    border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = CrimsonError)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", color = CrimsonError)
                }
            }

            // Budget & Studio Defaults
            StudioCard {
                SectionHeader(
                    title = "Budget & Studio Defaults",
                    subtitle = "Configure spending limit and MP4 export preferences",
                    icon = Icons.Default.Insights
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Spending Limit", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Text(
                        text = "$${"%.2f".format(limitValue)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = JetBrainsMonoFamily,
                        color = CyanTelemetry
                    )
                }
                Slider(
                    value = limitValue.toFloat().coerceIn(1f, 200f),
                    onValueChange = { limitValue = it.toInt().toDouble() },
                    valueRange = 1f..200f,
                    colors = SliderDefaults.colors(thumbColor = VioletBright, activeTrackColor = VioletPulse)
                )

                Text("Default Aspect Ratio", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("16:9", "9:16").forEach { r ->
                        FilterChip(
                            selected = defaultRatio == r,
                            onClick = { defaultRatio = r },
                            label = { Text(r) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Eco Image-Based Mode Default", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text("Lower API cost by reusing key visuals", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                    Switch(checked = lowCostDefault, onCheckedChange = { lowCostDefault = it })
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateAccountSettings(limitValue, defaultRatio, defaultLang, lowCostDefault) },
                    colors = ButtonDefaults.buttonColors(containerColor = VioletPulse),
                    modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
                ) {
                    Text("Save Account Defaults", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Burn Synchronized Subtitles in MP4",
                    subtitle = "Draw captions onto video frames automatically",
                    checked = localSettings.burnSubtitlesInMp4,
                    onCheckedChange = {
                        viewModel.setLocalPreferences(it, localSettings.addAmbientMusicBed, localSettings.autoSaveToDownloads)
                    }
                )
                ToggleRow(
                    title = "Add Studio Ambient Music Bed",
                    subtitle = "Mix subtle background pad under narration",
                    checked = localSettings.addAmbientMusicBed,
                    onCheckedChange = {
                        viewModel.setLocalPreferences(localSettings.burnSubtitlesInMp4, it, localSettings.autoSaveToDownloads)
                    }
                )
                ToggleRow(
                    title = "Auto-Save MP4 to Downloads/SARVCUT",
                    subtitle = "Export immediately when rendering finishes",
                    checked = localSettings.autoSaveToDownloads,
                    onCheckedChange = {
                        viewModel.setLocalPreferences(localSettings.burnSubtitlesInMp4, localSettings.addAmbientMusicBed, it)
                    }
                )
            }

            // Provider Adapter Architecture Transparency
            StudioCard {
                SectionHeader(
                    title = "AI Provider Adapters",
                    subtitle = "Modular pipeline services & client/server capability status",
                    icon = Icons.Default.Memory
                )
                Spacer(Modifier.height(10.dp))
                val statuses = ProviderRegistry.listStatuses(
                    appConfig.searchGroundingEnabled,
                    appConfig.nanoBananaEnabled,
                    appConfig.geminiTtsEnabled,
                    appConfig.veoServerAdapterEnabled
                )
                statuses.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (item.availableOnClient) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (item.availableOnClient) EmeraldSuccess else AmberWarn,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${item.name} · ${item.modelOrEngine}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            Text(item.statusNote, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                }
            }

            // Admin Console (Strictly visible & functional for verified admin)
            if (viewModel.isAdmin) {
                StudioCard(elevated = true, borderColor = CyanTelemetry.copy(alpha = 0.5f)) {
                    SectionHeader(
                        title = "Admin Console",
                        subtitle = "Users, jobs, provider switches, error monitor & moderation",
                        icon = Icons.Default.AdminPanelSettings
                    )
                    Spacer(Modifier.height(12.dp))
                    val errorCount = adminLogs.count { it.status == "FAILED" }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricPill("Users", "${adminUsers.size}")
                        MetricPill("Projects", "${adminProjects.size}")
                        MetricPill("Errors", "$errorCount", if (errorCount > 0) CrimsonError else EmeraldSuccess)
                        MetricPill("Reports", "${adminReports.count { it.status == "OPEN" }}")
                    }

                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Enable Google Search Grounding",
                        subtitle = "Research agent web search tool",
                        checked = appConfig.searchGroundingEnabled,
                        onCheckedChange = { viewModel.updateAdminConfig(appConfig.copy(searchGroundingEnabled = it)) }
                    )
                    ToggleRow(
                        title = "Enable Nano Banana Image Gen",
                        subtitle = "Scene artwork & thumbnail synthesis",
                        checked = appConfig.nanoBananaEnabled,
                        onCheckedChange = { viewModel.updateAdminConfig(appConfig.copy(nanoBananaEnabled = it)) }
                    )
                    ToggleRow(
                        title = "Enable Gemini TTS Primary",
                        subtitle = "Cloud TTS with Android TTS fallback",
                        checked = appConfig.geminiTtsEnabled,
                        onCheckedChange = { viewModel.updateAdminConfig(appConfig.copy(geminiTtsEnabled = it)) }
                    )

                    if (adminReports.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Content Reports", style = MaterialTheme.typography.labelLarge, color = AmberWarn)
                        adminReports.take(5).forEach { rep ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rep.topic, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${rep.status} · ${rep.reason}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                                if (rep.status == "OPEN") {
                                    FilterChip(
                                        selected = false,
                                        onClick = { viewModel.resolveContentReport(rep.id, "RESOLVED") },
                                        label = { Text("Resolve") }
                                    )
                                }
                            }
                        }
                    }

                    val recentErrors = adminLogs.filter { it.status == "FAILED" }.take(3)
                    if (recentErrors.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Recent Service Errors", style = MaterialTheme.typography.labelLarge, color = CrimsonError)
                        recentErrors.forEach { err ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrimsonError, modifier = Modifier.size(14.dp))
                                Text(
                                    text = "${err.stage}: ${err.errorMessage ?: "Failed"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MetricPill(label: String, value: String, valueColor: Color = CyanTelemetry) {
    Surface(
        color = CarbonSurfaceElevated,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, CarbonBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMonoFamily, color = valueColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}
