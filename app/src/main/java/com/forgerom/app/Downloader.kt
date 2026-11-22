package com.forgerom.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.forgerom.app.ai.DownloadInfo

/** Hands a file to Android's DownloadManager (notification, resume, saved in Downloads). ForgeROM never installs it. */
object Downloader {

    /** First-party providers only. Mirrors must publish a trustworthy checksum before use. */
    fun isAllowed(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        val trusted = listOf("lineageos.org", "crdroid.net", "orangefox.download")
        return trusted.any { host == it || host.endsWith(".$it") } ||
            (host == "github.com" && uri.path?.startsWith(
                "/Galaxy-Expert/ForgeROM/releases/download/") == true)
    }

    fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Returns false if the download could not be started (for example no storage permission on old Android). */
    fun start(context: Context, file: DownloadInfo): Boolean {
        val url = file.url; val fileName = file.fileName
        if (!isAllowed(url)) return false
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeFileName(fileName))
                .setDescription("ForgeROM")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName(fileName))
            val id = manager.enqueue(request)
            DownloadVerifier.track(context, id, file)
            true
        } catch (e: Exception) {
            false
        }
    }
}
