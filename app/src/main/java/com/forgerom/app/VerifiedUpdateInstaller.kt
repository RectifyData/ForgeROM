package com.forgerom.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** Platform installation UI only; never attempts a background or root install. */
object VerifiedUpdateInstaller {
    private fun updates(context: Context): File = File(context.cacheDir, "verified_updates").apply { mkdirs() }
    fun verifiedFile(context: Context, name: String): File = File(updates(context), Downloader.safeFileName(name))

    @Suppress("DEPRECATION")
    fun matchesInstalledSignature(context: Context, archive: File): Boolean {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val candidate = manager.getPackageArchiveInfo(archive.absolutePath, flags) ?: return false
        if (candidate.packageName != context.packageName) return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        fun fingerprints(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28)
                info.signingInfo?.apkContentsSigners else info.signatures
            return signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        val original = fingerprints(installed)
        return original.isNotEmpty() && original == fingerprints(candidate)
    }

    fun install(context: Context, name: String): Boolean {
        return try {
            if (!name.endsWith(".apk", true) ) return false
            val file = verifiedFile(context, name)
            if (!file.isFile || !matchesInstalledSignature(context, file)) return false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.verified_updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
}
