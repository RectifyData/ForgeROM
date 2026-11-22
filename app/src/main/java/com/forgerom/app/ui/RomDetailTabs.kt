package com.forgerom.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgerom.app.R
import com.forgerom.app.ai.RomArtifact
import com.forgerom.app.ai.RomBug
import com.forgerom.app.ai.RomItem

/**
 * Details are deliberately read-only. Community links and metadata must never be
 * treated as a flashing recipe or as evidence that a partition image is compatible.
 */
@Composable
internal fun RomDetailTabs(rom: RomItem, onTrustedDownload: () -> Unit) {
    var selected by rememberSaveable(rom.url) { mutableIntStateOf(0) }
    val names = listOf(
        stringResource(R.string.rom_details_info),
        stringResource(R.string.rom_details_files),
        stringResource(R.string.rom_details_bugs)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            names.forEachIndexed { index, label ->
                Tab(selected = selected == index, onClick = { selected = index },
                    text = {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    })
            }
        }
        when (selected) {
            0 -> RomInfoPane(rom)
            1 -> RomFilesPane(rom, onTrustedDownload)
            2 -> RomBugsPane(rom)
        }
    }
}

@Composable
private fun PaneTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun RomInfoPane(rom: RomItem) {
    val browser = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rom.notes?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium) }
        if (rom.safetyNotes.isNotEmpty()) {
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rom_details_warnings), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    rom.safetyNotes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        if (rom.credits.isNotEmpty()) {
            PaneTitle(stringResource(R.string.rom_details_credits))
            Text(rom.credits.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { browser.openUri(rom.url) }) {
            Text(stringResource(R.string.rom_details_source))
        }
    }
}

@Composable
private fun RomFilesPane(rom: RomItem, onTrustedDownload: () -> Unit) {
    val browser = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.rom_details_download_caution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        rom.download?.let { file ->
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.fileName, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onTrustedDownload) { Text(stringResource(R.string.download_button)) }
                }
            }
        }
        rom.artifacts.forEach { artifact ->
            ArtifactCard(artifact)
        }
        if (rom.download == null && rom.artifacts.isEmpty()) {
            Text(stringResource(R.string.rom_details_empty_files),
                style = MaterialTheme.typography.bodyMedium)
        }
        if (rom.artifacts.isNotEmpty()) {
            Text(stringResource(R.string.rom_details_manual_install),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtifactCard(artifact: RomArtifact) {
    val browser = LocalUriHandler.current
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(artifact.label, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            artifact.fileName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            artifact.partition?.let {
                Text(stringResource(R.string.rom_details_partition, it),
                    style = MaterialTheme.typography.labelMedium)
            }
            artifact.version?.let {
                Text(stringResource(R.string.rom_details_file_version, it),
                    style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { browser.openUri(artifact.url) }) {
                Text(stringResource(R.string.rom_details_page))
            }
            artifact.directUrl?.let { direct ->
                FilledTonalButton(onClick = { browser.openUri(direct) }) {
                    Text(stringResource(R.string.rom_details_mirror))
                }
            }
        }
    }
}

@Composable
private fun RomBugsPane(rom: RomItem) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rom.bugs.isEmpty()) {
            Text(stringResource(R.string.rom_details_empty_bugs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.rom_details_count, rom.bugs.size),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.rom_details_reports),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rom.bugs.forEach { bug ->
                BugCard(bug)
            }
        }
    }
}

@Composable
private fun BugCard(bug: RomBug) {
    val status = when (bug.status.lowercase()) {
        "broken" -> R.string.rom_details_report_status_broken
        "unsupported" -> R.string.rom_details_report_status_unsupported
        "untested" -> R.string.rom_details_report_status_untested
        "fixed" -> R.string.rom_details_report_status_fixed
        else -> R.string.rom_details_report_status_other
    }
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bug.component, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(status),
                    color = if (bug.status == "broken" || bug.status == "unsupported")
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            bug.details?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
