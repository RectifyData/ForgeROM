package com.forgerom.app

import android.content.Context
import com.forgerom.app.ai.BootloaderInfo
import com.forgerom.app.ai.BootloaderStatus
import com.forgerom.app.ai.DownloadInfo
import com.forgerom.app.ai.RecoveryItem
import com.forgerom.app.ai.RomItem
import com.forgerom.app.ai.RomArtifact
import com.forgerom.app.ai.RomBug
import com.forgerom.app.ai.RomExtraZip
import com.forgerom.app.ai.RomSearchResult
import com.forgerom.app.ai.UiText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves search results per device in a small JSON file, so they can be shown again without searching.
 * Results older than 30 days are ignored. Translatable texts are stored by resource NAME, because
 * resource numbers change between app versions.
 */
class ResultCache(private val context: Context) {

    class Hit(val result: RomSearchResult, val savedAt: Long)

    private val file get() = File(context.filesDir, "result_cache_v2.json")

    fun get(key: String): Hit? {
        return try {
            val entry = readAll().optJSONObject(key) ?: return null
            val savedAt = entry.getLong("savedAt")
            if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null
            Hit(resultFromJson(entry.getJSONObject("result")), savedAt)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun put(key: String, result: RomSearchResult, savedAt: Long) {
        try {
            val all = readAll()
            all.put(key, JSONObject().put("savedAt", savedAt).put("result", resultToJson(result)))

            // Drop expired entries, then keep only the newest MAX_ENTRIES.
            val now = System.currentTimeMillis()
            val entries = all.keys().asSequence().toList()
                .map { k -> k to all.optJSONObject(k)?.optLong("savedAt", 0L)!! }
            entries.filter { now - it.second > MAX_AGE_MS }.forEach { all.remove(it.first) }
            entries.filter { now - it.second <= MAX_AGE_MS }
                .sortedByDescending { it.second }
                .drop(MAX_ENTRIES)
                .forEach { all.remove(it.first) }

            file.writeText(all.toString())
        } catch (e: Exception) {
            // The cache is only a convenience; ignore any problem.
        }
    }

    private fun readAll(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
    }

    // ---- JSON conversion ----

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun UiText.toJson(): JSONObject {
        val id = res
        return if (id != null) {
            JSONObject()
                .put("res", context.resources.getResourceEntryName(id))
                .put("args", JSONArray(args))
        } else {
            JSONObject().put("raw", raw.orEmpty())
        }
    }

    private fun uiTextFromJson(o: JSONObject?): UiText? {
        if (o == null) return null
        if (o.has("res")) {
            val id = context.resources.getIdentifier(o.getString("res"), "string", context.packageName)
            if (id == 0) return null
            val a = o.optJSONArray("args")
            val args = if (a == null) emptyList<String>() else List(a.length()) { a.optString(it) }
            return UiText.res(id, *args.toTypedArray())
        }
        return UiText.raw(o.optString("raw"))
    }

    private fun resultToJson(r: RomSearchResult): JSONObject = JSONObject()
        .put("marketingName", r.marketingName ?: JSONObject.NULL)
        .put("blStatus", r.bootloader.status.name)
        .put("blNotes", r.bootloader.notes?.toJson() ?: JSONObject.NULL)
        .put("roms", JSONArray(r.roms.map { rom ->
            JSONObject()
                .put("name", rom.name)
                .put("android", rom.androidVersion ?: JSONObject.NULL)
                .put("status", rom.status ?: JSONObject.NULL)
                .put("url", rom.url)
                .put("notes", rom.notes?.toJson() ?: JSONObject.NULL)
                .put("dl", rom.download?.let { dl ->
                    JSONObject()
                        .put("url", dl.url)
                        .put("file", dl.fileName)
                        .put("size", dl.sizeBytes ?: JSONObject.NULL)
                        .put("sha", dl.sha256 ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
                .put("artifacts", JSONArray(rom.artifacts.map { item ->
                    JSONObject()
                        .put("label", item.label)
                        .put("url", item.url)
                        .put("direct", item.directUrl ?: JSONObject.NULL)
                        .put("version", item.version ?: JSONObject.NULL)
                        .put("file", item.fileName ?: JSONObject.NULL)
                        .put("partition", item.partition ?: JSONObject.NULL)
                }))
                .put("extraZips", JSONArray(rom.extraZips.map { zip ->
                    JSONObject()
                        .put("label", zip.label).put("kind", zip.kind)
                        .put("required", zip.required)
                        .put("url", zip.url ?: JSONObject.NULL)
                        .put("direct", zip.directUrl ?: JSONObject.NULL)
                        .put("documentation", zip.documentationUrl ?: JSONObject.NULL)
                        .put("file", zip.fileName ?: JSONObject.NULL)
                        .put("version", zip.version ?: JSONObject.NULL)
                        .put("notes", zip.notes ?: JSONObject.NULL)
                        .put("sha256", zip.sha256 ?: JSONObject.NULL)
                        .put("models", JSONArray(zip.applicableModels))
                }))
                .put("bugs", JSONArray(rom.bugs.map { bug ->
                    JSONObject().put("component", bug.component).put("status", bug.status)
                        .put("details", bug.details ?: JSONObject.NULL)
                }))
                .put("safetyNotes", JSONArray(rom.safetyNotes))
                .put("credits", JSONArray(rom.credits))
        }))
        .put("recoveries", JSONArray(r.recoveries.map { rec ->
            JSONObject()
                .put("name", rec.name)
                .put("url", rec.url)
                .put("status", rec.status ?: JSONObject.NULL)
                .put("notes", rec.notes?.toJson() ?: JSONObject.NULL)
                .put("dl", rec.download?.let { dl ->
                    JSONObject().put("url", dl.url).put("file", dl.fileName)
                        .put("size", dl.sizeBytes ?: JSONObject.NULL).put("sha", dl.sha256 ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
        }))
        .put("warnings", JSONArray(r.warnings.map { it.toJson() }))

    private fun resultFromJson(o: JSONObject): RomSearchResult {
        val roms = o.getJSONArray("roms").let { arr ->
            List(arr.length()) { i ->
                val x = arr.getJSONObject(i)
                RomItem(
                    name = x.getString("name"),
                    androidVersion = x.str("android"),
                    status = x.str("status"),
                    url = x.getString("url"),
                    notes = uiTextFromJson(x.optJSONObject("notes")),
                    download = x.optJSONObject("dl")?.let { dl ->
                        DownloadInfo(
                            url = dl.getString("url"),
                            fileName = dl.getString("file"),
                            sizeBytes = if (dl.isNull("size")) null else dl.optLong("size"),
                            sha256 = dl.str("sha")
                        )
                    },
                    artifacts = x.optJSONArray("artifacts")?.let { entries ->
                        (0 until entries.length()).mapNotNull { idx ->
                            val v = entries.optJSONObject(idx) ?: return@mapNotNull null
                            val label = v.str("label") ?: return@mapNotNull null
                            val url = v.str("url") ?: return@mapNotNull null
                            RomArtifact(label, url, v.str("direct"), v.str("version"),
                                v.str("file"), v.str("partition"))
                        }
                    } ?: emptyList(),
                    extraZips = x.optJSONArray("extraZips")?.let { rows ->
                        (0 until rows.length()).mapNotNull { i ->
                            val zip = rows.optJSONObject(i) ?: return@mapNotNull null
                            val label = zip.str("label") ?: return@mapNotNull null
                            val models = zip.optJSONArray("models")
                            RomExtraZip(
                                label = label, kind = zip.str("kind") ?: "other",
                                required = zip.optBoolean("required", true),
                                url = zip.str("url"), directUrl = zip.str("direct"),
                                documentationUrl = zip.str("documentation"),
                                fileName = zip.str("file"), version = zip.str("version"),
                                notes = zip.str("notes"), sha256 = zip.str("sha256"),
                                applicableModels = if (models == null) emptyList()
                                    else (0 until models.length()).map { models.optString(it) }
                            )
                        }
                    } ?: emptyList(),
                    bugs = x.optJSONArray("bugs")?.let { entries ->
                        (0 until entries.length()).mapNotNull { idx ->
                            val v = entries.optJSONObject(idx) ?: return@mapNotNull null
                            val component = v.str("component") ?: return@mapNotNull null
                            RomBug(component, v.str("status") ?: "untested", v.str("details"))
                        }
                    } ?: emptyList(),
                    safetyNotes = x.optJSONArray("safetyNotes")?.let { rows ->
                        (0 until rows.length()).mapNotNull { rows.optString(it).ifBlank { null } }
                    } ?: emptyList(),
                    credits = x.optJSONArray("credits")?.let { rows ->
                        (0 until rows.length()).mapNotNull { rows.optString(it).ifBlank { null } }
                    } ?: emptyList()
                )
            }
        }
        val recoveries = o.getJSONArray("recoveries").let { arr ->
            List(arr.length()) { i ->
                val x = arr.getJSONObject(i)
                RecoveryItem(
                    name = x.getString("name"),
                    url = x.getString("url"),
                    notes = uiTextFromJson(x.optJSONObject("notes")),
                    status = x.str("status"),
                    download = x.optJSONObject("dl")?.let { dl ->
                        DownloadInfo(dl.getString("url"), dl.getString("file"),
                            if (dl.isNull("size")) null else dl.optLong("size"), dl.str("sha"))
                    }
                )
            }
        }
        val warnings = o.getJSONArray("warnings").let { arr ->
            List(arr.length()) { i -> uiTextFromJson(arr.optJSONObject(i)) }.filterNotNull()
        }
        return RomSearchResult(
            marketingName = o.str("marketingName"),
            bootloader = BootloaderInfo(
                BootloaderStatus.valueOf(o.getString("blStatus")),
                uiTextFromJson(o.optJSONObject("blNotes"))
            ),
            roms = roms,
            recoveries = recoveries,
            warnings = warnings
        )
    }

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000   // 30 days
        const val MAX_ENTRIES = 30
    }
}
