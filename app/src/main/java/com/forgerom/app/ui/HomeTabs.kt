package com.forgerom.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgerom.app.AppMode
import com.forgerom.app.DownloadVerification
import com.forgerom.app.R
import com.forgerom.app.UiState
import com.forgerom.app.device.HardwareBootloader

/** Navigation is kept independent from discovery so each section has its own focus. */
@Composable
internal fun ForgeHomeTabs(state: UiState, actions: Actions) {
    var tab by rememberSaveable { mutableStateOf("discover") }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == "discover", onClick = { tab = "discover" },
                    icon = { Icon(Icons.Outlined.Explore, null) }, label = { Text(stringResource(R.string.tab_discover)) }
                )
                NavigationBarItem(
                    selected = tab == "downloads", onClick = { tab = "downloads"; actions.onVerifyDownloads() },
                    icon = { Icon(Icons.Outlined.CloudDownload, null) }, label = { Text(stringResource(R.string.tab_downloads)) }
                )
                NavigationBarItem(
                    selected = tab == "device", onClick = { tab = "device" },
                    icon = { Icon(Icons.Outlined.PhoneAndroid, null) }, label = { Text(stringResource(R.string.tab_device)) }
                )
                NavigationBarItem(
                    selected = tab == "settings", onClick = { tab = "settings" },
                    icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                "downloads" -> DownloadsPane(state, actions)
                "device" -> DevicePane(state, actions)
                "settings" -> SettingsPane(state, actions)
                else -> MainScreen(state, actions)
            }
        }
    }
}

@Composable
private fun PaneHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadsPane(state: UiState, a: Actions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PaneHeader(stringResource(R.string.tab_downloads), stringResource(R.string.downloads_desc))
            FilledTonalButton(onClick = a.onVerifyDownloads, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.downloads_refresh))
            }
        }
        if (state.downloadChecks.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Text(stringResource(R.string.downloads_empty), Modifier.padding(20.dp))
                }
            }
        }
        items(state.downloadChecks, key = { it.filename + it.status.name }) { check ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(check.filename, style = MaterialTheme.typography.titleMedium)
                    val message = when (check.status) {
                        DownloadVerification.Status.VERIFIED -> R.string.download_verified
                        DownloadVerification.Status.FAILED -> R.string.download_invalid
                        DownloadVerification.Status.UNVERIFIED -> R.string.download_unverified
                        DownloadVerification.Status.PENDING -> R.string.download_checking
                    }
                    Text(stringResource(message, check.filename), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (check.details.isNotBlank()) Text(check.details, style = MaterialTheme.typography.bodySmall)
                    if (check.status == DownloadVerification.Status.VERIFIED &&
                        check.filename.endsWith(".apk", ignoreCase = true)) {
                        Button(onClick = { a.onInstallVerifiedUpdate(check.filename) }) {
                            Text(stringResource(R.string.update_install_verified))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun DevicePane(state: UiState, a: Actions) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { PaneHeader(stringResource(R.string.tab_device), stringResource(R.string.device_desc)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.displayName ?: stringResource(R.string.hw_unknown),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    state.detected?.let {
                        Text(it.manufacturer + " · " + it.model + " · " + it.codename)
                        Text("Android " + it.androidVersion + " · API " + it.sdk)
                        it.soc?.let { soc -> Text(soc) }
                    }
                    OutlinedButton(onClick = a.onOpenPicker) {
                        Text(stringResource(R.string.picker_title))
                    }
                    if (state.manual != null) {
                        FilledTonalButton(onClick = a.onUseDetected) { Text(stringResource(R.string.device_use_detected)) }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.hw_title), style = MaterialTheme.typography.titleLarge)
                    val report = state.hardware
                    if (report == null || state.hardwareRefreshing) CircularProgressIndicator()
                    report?.let {
                        val stateName = when (it.state) {
                            HardwareBootloader.LockState.LOCKED -> R.string.hw_locked
                            HardwareBootloader.LockState.UNLOCKED -> R.string.hw_unlocked
                            else -> R.string.hw_unknown
                        }
                        Text(stringResource(stateName), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(when (it.support) {
                            HardwareBootloader.UnlockSupport.REPORTED_SUPPORTED -> R.string.hw_support_yes
                            HardwareBootloader.UnlockSupport.REPORTED_UNSUPPORTED -> R.string.hw_support_no
                            else -> R.string.hw_support_unknown
                        }))
                        Text(stringResource(R.string.hw_source, it.source),
                            style = MaterialTheme.typography.bodySmall)
                        it.verifiedBoot?.let { boot -> Text(stringResource(R.string.hw_bootstate, boot)) }
                        Text(it.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.hw_caution),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = a.onRefreshHardware) { Text(stringResource(R.string.hw_refresh)) }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.hw_developer_options)) }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.device_database_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.device_database_desc), style = MaterialTheme.typography.bodySmall)
                    Text(state.catalogStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = a.onRefreshCatalog, enabled = !state.catalogRefreshing) {
                        Text(stringResource(R.string.device_database_refresh))
                    }
                    Text(stringResource(R.string.device_database_warning),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SettingsPane(state: UiState, a: Actions) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PaneHeader(stringResource(R.string.tab_settings), stringResource(R.string.settings_desc)) }
        item { LanguageSection(a.onSetLanguage) }
        item { ModeSection(state.mode, !state.rootChecking, a.onSetMode) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_updates_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.updateCheckEnabled, onCheckedChange = a.onSetUpdateCheck)
                }
            }
        }
        item { OutlinedButton(onClick = a.onOpenGuide) { Text(stringResource(R.string.guide_button)) } }
        item { ReportButton(state) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
