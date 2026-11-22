package com.forgerom.app.ai

import androidx.annotation.StringRes
import java.io.IOException

enum class BootloaderStatus { UNLOCKABLE, CONDITIONAL, LOCKED, UNKNOWN }

/** Text that is either a translatable string resource (+ arguments) or raw text (e.g. written by the AI). */
class UiText private constructor(
    @StringRes val res: Int?,
    val args: List<String>,
    val raw: String?
) {
    companion object {
        fun raw(text: String) = UiText(null, emptyList(), text)
        fun res(@StringRes id: Int, vararg args: String) = UiText(id, args.toList(), null)
    }
}

data class BootloaderInfo(
    val status: BootloaderStatus,
    val notes: UiText?
)

/** A file that can be downloaded straight to the phone (only when the source gives a direct link). */
data class DownloadInfo(
    val url: String,
    val fileName: String,
    val sizeBytes: Long?,
    val sha256: String?
)

/** A linked release package or a separate partition image. URLs are unverified metadata, never flash commands. */
data class RomArtifact(
    val label: String,
    val url: String,
    val directUrl: String? = null,
    val version: String? = null,
    val fileName: String? = null,
    val partition: String? = null
)

/** Optional or maintainer-required ZIP. Metadata NEVER authorizes repartitioning or flashing. */
data class RomExtraZip(
    val label: String,
    val kind: String,
    val required: Boolean = true,
    val url: String? = null,
    val directUrl: String? = null,
    val documentationUrl: String? = null,
    val fileName: String? = null,
    val version: String? = null,
    val notes: String? = null,
    val sha256: String? = null,
    val applicableModels: List<String> = emptyList()
)

/** Reported by a ROM maintainer; status is not a device test result. */
data class RomBug(val component: String, val status: String, val details: String? = null)

data class RomItem(
    val name: String,
    val androidVersion: String?,
    val status: String?,   // official / unofficial / abandoned
    val url: String,
    val notes: UiText?,
    val download: DownloadInfo? = null,
    val artifacts: List<RomArtifact> = emptyList(),
    val extraZips: List<RomExtraZip> = emptyList(),
    val bugs: List<RomBug> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val credits: List<String> = emptyList()
)

data class RecoveryItem(
    val name: String,
    val url: String,
    val notes: UiText?,
    val status: String? = null,
    val download: DownloadInfo? = null
)

data class RomSearchResult(
    val marketingName: String?,
    val bootloader: BootloaderInfo,
    val roms: List<RomItem>,
    val recoveries: List<RecoveryItem>,
    val warnings: List<UiText>
)

/** An error whose message is a translatable string resource (+ optional technical detail). */
class UserFacingException(
    @StringRes val res: Int,
    val detail: String = ""
) : IOException(detail)
