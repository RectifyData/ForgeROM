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
import com.forgerom.app.ai.RomExtraZip
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

        if (rom.extraZips.isNotEmpty()) {
            val required = rom.extraZips.filter { it.required }
            val optional = rom.extraZips.filterNot { it.required }
            if (required.isNotEmpty()) {
                PaneTitle(stringResource(R.string.extra_zips_required_title))
                Text(stringResource(R.string.extra_zips_required_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                required.forEach { zip -> ExtraZipCard(zip) }
            }
            if (optional.isNotEmpty()) {
                PaneTitle(stringResource(R.string.extra_zips_optional_title))
                optional.forEach { zip -> ExtraZipCard(zip) }
            }
        }
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
        if (rom.download == null && rom.artifacts.isEmpty() && rom.extraZips.isEmpty()) {
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

/** Prerequisites are displayed as manual-only references, never sent to the root installer. */
@Composable
private fun ExtraZipCard(zip: RomExtraZip) {
    val browser = LocalUriHandler.current
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor =
            if (zip.required) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(zip.label, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(if (zip.required) R.string.extra_zips_required
                    else R.string.extra_zips_optional),
                    style = MaterialTheme.typography.labelMedium)
            }
            val typeLabel = when (zip.kind) {
                "repartitioner" -> R.string.extra_zips_type_repartitioner
                "recovery" -> R.string.extra_zips_type_recovery
                "firmware" -> R.string.extra_zips_type_firmware
                "patch" -> R.string.extra_zips_type_patch
                "filesystem" -> R.string.extra_zips_type_filesystem
                "encryption" -> R.string.extra_zips_type_encryption
                "addon" -> R.string.extra_zips_type_addon
                else -> R.string.extra_zips_type_other
            }
            Text(stringResource(typeLabel), style = MaterialTheme.typography.labelMedium)
            zip.fileName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            zip.version?.let {
                Text(stringResource(R.string.rom_details_file_version, it),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (zip.applicableModels.isNotEmpty()) {
                Text(stringResource(R.string.extra_zips_models,
                    zip.applicableModels.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
            }
            zip.notes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            zip.sha256?.let {
                Text(stringResource(R.string.extra_zips_published_hash, it),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (zip.url == null && zip.directUrl == null) {
                Text(stringResource(R.string.extra_zips_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            zip.url?.let { link ->
                OutlinedButton(onClick = { browser.openUri(link) }) {
                    Text(stringResource(R.string.rom_details_page))
                }
            }
            zip.directUrl?.let { link ->
                FilledTonalButton(onClick = { browser.openUri(link) }) {
                    Text(stringResource(R.string.rom_details_mirror))
                }
            }
            zip.documentationUrl?.let { link ->
                OutlinedButton(onClick = { browser.openUri(link) }) {
                    Text(stringResource(R.string.extra_zips_instructions))
                }
            }
            if (zip.kind == "repartitioner") {
                Text(stringResource(R.string.extra_zips_repartition_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
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
