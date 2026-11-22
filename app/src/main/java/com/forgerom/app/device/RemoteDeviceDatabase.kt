package com.forgerom.app.device

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Remote metadata only. It must never be used to authorize flashing or OEM unlock. */
object RemoteDeviceParser {
    fun parse(text: String): List<CatalogEntry> {
        val root = JSONObject(text)
        require(root.optInt("schema_version") == 1) { "Unsupported database schema" }
        val array = root.getJSONArray("devices")
        require(array.length() <= 10000) { "Too many device records" }
        val entries = ArrayList<CatalogEntry>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val brand = row.optString("brand").trim().take(64)
            val marketing = row.optString("marketing").trim().take(120)
            val codename = row.optString("codename").trim().take(48)
            val models = row.optJSONArray("models") ?: continue
            if (brand.isEmpty() || marketing.isEmpty() ||
                !codename.matches(Regex("[A-Za-z0-9_.+-]{1,48}"))) continue
            for (modelIndex in 0 until minOf(models.length(), 40)) {
                val model = models.optString(modelIndex).trim().take(64)
                if (model.isNotBlank()) {
                    entries += CatalogEntry(brand, marketing, codename, model)
                }
            }
        }
        return entries.distinctBy { it.model.lowercase() + "/" + it.codename.lowercase() }
    }
}

class RemoteDeviceDatabase(private val context: Context, private val client: OkHttpClient) {
    private val cacheFile get() = File(context.filesDir, "forgerom_phones_v1.json")
    @Volatile var source: String = "Unavailable"
        private set

    suspend fun entries(force: Boolean = false): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val file = cacheFile
        val cached = if (file.isFile) runCatching {
            RemoteDeviceParser.parse(file.readText())
        }.getOrDefault(emptyList()) else emptyList()
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS
        if (!force && fresh && cached.isNotEmpty()) {
            source = "GitHub cache"
            return@withContext cached
        }

        val responseText = try {
            val request = Request.Builder()
                .url(DATABASE_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", if (force) "no-cache" else "max-age=3600")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.source()?.let { source ->
                    val bytes = source.readByteArray(MAX_BYTES + 1L)
                    if (bytes.size > MAX_BYTES) null else bytes.toString(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) { null }
        if (responseText != null) {
            val rows = runCatching { RemoteDeviceParser.parse(responseText) }
                .getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                runCatching {
                    val temp = File(context.filesDir, "forgerom_phones_v1.tmp")
                    temp.writeText(responseText)
                    if (!temp.renameTo(file)) {
                        file.writeText(responseText)
                        temp.delete()
                    }
                }
                source = "GitHub (updated)"
                return@withContext rows
            }
        }
        source = if (cached.isNotEmpty()) "GitHub cache (offline)" else "Unavailable"
        return@withContext cached
    }

    companion object {
        const val DATABASE_URL = "https://raw.githubusercontent.com/RectifyData/ForgeROM/main/database/devices.json"
        private const val CACHE_MS = 24L * 60 * 60 * 1000
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
