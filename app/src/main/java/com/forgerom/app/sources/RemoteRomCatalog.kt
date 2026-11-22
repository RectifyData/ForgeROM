package com.forgerom.app.sources

import android.content.Context
import com.forgerom.app.ai.RomArtifact
import com.forgerom.app.ai.RomBug
import com.forgerom.app.ai.RomExtraZip
import com.forgerom.app.ai.RomItem
import com.forgerom.app.ai.PublicFolderAccess
import com.forgerom.app.ai.UiText
import com.forgerom.app.device.DeviceInfo
import com.forgerom.app.device.RemoteDeviceDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Untrusted, display-only ROM metadata from the user-maintained JSON database.
 * No data from this class may authorize flashing, repartitioning, or root commands.
 */
object CuratedRomParser {
    private fun JSONObject.string(key: String, limit: Int = 400): String? =
        if (isNull(key)) null else optString(key).trim().take(limit).ifBlank { null }

    private fun trustedWebLink(candidate: String?): String? {
        val input = candidate?.takeIf { it.length in 12..2048 && it.none(Char::isWhitespace) } ?: return null
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        return if (uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null && uri.userInfo == null && uri.port in listOf(-1, 443)) input else null
    }

    /** Only display passwords accompanied by an HTTPS citation of the owner's public post. */
    private fun JSONObject.publicFolder(): PublicFolderAccess? {
        val folder = trustedWebLink(string("folder_url", 2048)) ?: return null
        val citation = trustedWebLink(string("password_source_url", 2048))
        // A password alone is not proof of permission to share it; require the owner's public post.
        val supplied = if (isNull("public_password")) "" else optString("public_password", "")
        val password = supplied.takeIf { value ->
            citation != null && value.length in 1..128 && value.none { it.isISOControl() }
        }
        return PublicFolderAccess(folderUrl = folder, publicPassword = password,
            passwordSourceUrl = if (password == null) null else citation)
    }

    private fun modelKey(text: String): String = text.uppercase(Locale.ROOT)
        .replace("/DS", "").replace(" ", "").trim()

    private fun matches(models: JSONArray?, model: String): Boolean {
        if (models == null || model.isBlank()) return false
        val wanted = modelKey(model)
        for (i in 0 until minOf(models.length(), 60)) {
            if (modelKey(models.optString(i)) == wanted) return true
        }
        return false
    }

    private fun artifacts(array: JSONArray?, partitionImages: Boolean): List<RomArtifact> {
        if (array == null) return emptyList()
        val out = ArrayList<RomArtifact>()
        for (i in 0 until minOf(array.length(), 24)) {
            val a = array.optJSONObject(i) ?: continue
            val folder = a.publicFolder()
            val url = trustedWebLink(a.string("url", 2048)) ?: folder?.folderUrl ?: continue
            val partition = if (partitionImages) a.string("partition", 40)?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9_]{1,40}")) } ?: continue else null
            val label = a.string("label", 100) ?: continue
            out += RomArtifact(
                label = label,
                url = url,
                directUrl = trustedWebLink(a.string("direct_url", 2048)),
                version = a.string("version", 40),
                fileName = a.string("file_name", 140),
                partition = partition,
                folderAccess = folder
            )
        }
        return out
    }

    /** Extra flashable ZIPs are external references only; missing links stay visible as unresolved. */
    private fun parseExtraZips(rows: JSONArray?, device: DeviceInfo): List<RomExtraZip> {
        if (rows == null) return emptyList()
        val items = ArrayList<RomExtraZip>()
        val supportedKinds = setOf("repartitioner", "recovery", "firmware",
            "patch", "filesystem", "encryption", "addon", "other")
        for (index in 0 until minOf(rows.length(), 32)) {
            val entry = rows.optJSONObject(index) ?: continue
            val label = entry.string("label", 100) ?: continue
            val models = entry.optJSONArray("models")
            if (models != null && !matches(models, device.model)) continue
            val kind = entry.string("kind", 32)?.lowercase(Locale.ROOT)
                ?.takeIf { it in supportedKinds } ?: "other"
            val modelNames = if (models == null) emptyList()
                else (0 until minOf(models.length(), 40))
                    .mapNotNull { models.optString(it).trim().take(64).ifBlank { null } }
            val hash = entry.string("sha256", 64)
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                ?.lowercase(Locale.ROOT)
            items += RomExtraZip(
                label = label, kind = kind, required = entry.optBoolean("required", true),
                url = trustedWebLink(entry.string("url", 2048)),
                directUrl = trustedWebLink(entry.string("direct_url", 2048)),
                documentationUrl = trustedWebLink(entry.string("documentation_url", 2048)),
                fileName = entry.string("file_name", 140),
                version = entry.string("version", 40),
                notes = entry.string("notes", 400),
                sha256 = hash,
                applicableModels = modelNames,
                folderAccess = entry.publicFolder()
            )
        }
        return items.distinctBy { it.label.lowercase(Locale.ROOT) + "|" + (it.fileName ?: "") }
    }

    fun parse(json: String, device: DeviceInfo): List<RomItem> {
        val root = JSONObject(json)
        if (root.optInt("schema_version") != 1) return emptyList()
        val devices = root.optJSONArray("devices") ?: return emptyList()
        val out = ArrayList<RomItem>()
        for (index in 0 until minOf(devices.length(), 10000)) {
            val row = devices.optJSONObject(index) ?: continue
            if (!row.string("codename", 48).equals(device.codename, ignoreCase = true)) continue
            if (!matches(row.optJSONArray("models"), device.model)) continue
            val roms = row.optJSONArray("roms") ?: continue
            for (j in 0 until minOf(roms.length(), 30)) {
                val rom = roms.optJSONObject(j) ?: continue
                if (!matches(rom.optJSONArray("models") ?: row.optJSONArray("models"), device.model)) continue
                val url = trustedWebLink(rom.string("source_url", 2048)) ?: continue
                val name = rom.string("name", 140) ?: continue
                val bugs = ArrayList<RomBug>()
                val bugRows = rom.optJSONArray("bugs")
                if (bugRows != null) for (k in 0 until minOf(bugRows.length(), 48)) {
                    val item = bugRows.optJSONObject(k) ?: continue
                    val component = item.string("component", 100) ?: continue
                    val status = item.string("status", 24)?.lowercase(Locale.ROOT) ?: "untested"
                    bugs += RomBug(component, status, item.string("details", 320))
                }
                fun notes(field: String): List<String> {
                    val values = rom.optJSONArray(field) ?: return emptyList()
                    return (0 until minOf(values.length(), 32)).mapNotNull { idx ->
                        values.optString(idx).trim().take(300).ifBlank { null }
                    }
                }
                out += RomItem(
                    name = name,
                    androidVersion = rom.string("android_version", 24),
                    status = rom.string("status", 28) ?: "unofficial",
                    url = url,
                    notes = rom.string("notes", 600)?.let(UiText::raw),
                    // Unverified third-party links must open in the browser; they are NOT
                    // direct DownloadInfo entries and must not enter the root installer.
                    download = null,
                    artifacts = artifacts(rom.optJSONArray("packages"), false) +
                        artifacts(rom.optJSONArray("extra_images"), true),
                    extraZips = parseExtraZips(rom.optJSONArray("extra_zips"), device),
                    bugs = bugs,
                    safetyNotes = notes("warnings"),
                    credits = notes("credits")
                )
            }
        }
        return out.distinctBy { it.url + "|" + it.name }
    }
}

/** Read to EOF, but refuse oversized untrusted metadata without allocating its full length. */
internal object BoundedJsonReader {
    fun read(input: java.io.InputStream, maxBytes: Int): String? {
        require(maxBytes > 0)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() > maxBytes - read) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** Fresh GitHub request with a bounded offline cache. No remote command execution. */
class RemoteRomCatalog(private val context: Context, private val client: OkHttpClient) {
    private val cache get() = File(context.filesDir, "forgerom_roms_v1.json")

    suspend fun search(device: DeviceInfo): List<RomItem> = withContext(Dispatchers.IO) {
        val fetched: String? = try {
            val request = Request.Builder()
                .url(RemoteDeviceDatabase.DATABASE_URL)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.byteStream()?.use { input ->
                    BoundedJsonReader.read(input, MAX_BYTES)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fetched != null) {
            val parsed = runCatching { CuratedRomParser.parse(fetched, device) }.getOrNull()
            if (parsed != null) {
                runCatching {
                    val tmp = File(context.filesDir, "forgerom_roms_v1.tmp")
                    tmp.writeText(fetched)
                    if (!tmp.renameTo(cache)) {
                        cache.writeText(fetched)
                        tmp.delete()
                    }
                }
                return@withContext parsed
            }
        }
        val cached = runCatching { if (cache.isFile) cache.readText() else null }.getOrNull()
        if (cached == null) emptyList() else runCatching { CuratedRomParser.parse(cached, device) }
            .getOrDefault(emptyList())
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
