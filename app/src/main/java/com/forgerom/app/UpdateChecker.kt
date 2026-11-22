package com.forgerom.app

import com.forgerom.app.ai.DownloadInfo
import com.forgerom.app.sources.SourceParsers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Fetches official upstream releases. Never downloads or installs an APK without user consent. */
class UpdateChecker(private val client: OkHttpClient) {
    data class Update(val tag: String, val url: String, val changelog: String, val download: DownloadInfo?)

    fun latest(): Update? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Galaxy-Expert/ForgeROM/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val items = JSONArray(response.body?.string().orEmpty())
            val found = mutableListOf<Update>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optBoolean("draft")) continue
                val tag = item.optString("tag_name")
                val url = item.optString("html_url")
                if (!tag.matches(Regex("v?\\d+(\\.\\d+){1,2}([-.+][A-Za-z0-9.-]+)?")) ||
                    !url.startsWith("https://github.com/Galaxy-Expert/ForgeROM/releases/")) continue
                var apk: DownloadInfo? = null
                val assets = item.optJSONArray("assets") ?: JSONArray()
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    val address = asset.optString("browser_download_url")
                    if (!name.endsWith(".apk", ignoreCase = true) ||
                        !address.startsWith("https://github.com/Galaxy-Expert/ForgeROM/releases/download/")) continue
                    val digest = asset.optString("digest")
                    // Only offer an in-app download when the official release publishes a SHA-256.
                    val sha = digest.removePrefix("sha256:").takeIf {
                        digest.startsWith("sha256:") && it.matches(Regex("[a-fA-F0-9]{64}"))
                    } ?: continue
                    apk = DownloadInfo(address, name, asset.optLong("size", 0L).takeIf { it > 0 }, sha)
                    break
                }
                found += Update(tag, url, item.optString("body").take(2500), apk)
            }
            return found.maxWithOrNull { a, b -> SourceParsers.compareVersions(a.tag, b.tag) }
        }
    }
}
