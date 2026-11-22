package com.forgerom.app.sources

import org.json.JSONObject
import org.jsoup.Jsoup
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure parsing helpers (no Android classes), so they can be unit tested on a normal JVM. */
object SourceParsers {

    /** Safely parse the vendor-maintained device YAML (including block/flow lists). */
    fun parseYaml(text: String): Map<String, String> {
        val source = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return emptyMap()
        return source.entries.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val scalar = when (value) {
                is List<*> -> value.joinToString(prefix = "[", postfix = "]") { it?.toString().orEmpty() }
                is String, is Number, is Boolean -> value.toString()
                else -> return@mapNotNull null
            }
            name to scalar
        }.toMap()
    }

    /** Splits YAML flow lists, as returned by parseYaml, without mistaking quoted commas. */
    fun parseList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val v = value.trim()
        val parsed = if (v.startsWith("[") && v.endsWith("]")) {
            try { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(v) } catch (_: Exception) { null }
        } else null
        return when (parsed) {
            is List<*> -> parsed.mapNotNull { it?.toString() }.filter { it.isNotBlank() && it != "None" }
            else -> listOf(v.trim('\'', '"')).filter { it.isNotBlank() && it != "None" }
        }
    }

    fun normalizeModel(m: String): String = m.uppercase().trim().replace(Regex("(/DS|/DUAL)$"), "")
        .replace(" ", "").trim()

    /** LineageOS version -> Android version (null when unknown). */
    fun lineageToAndroid(branch: String): String? =
        when (branch.substringBefore('.').toIntOrNull()) {
            14 -> "7.1"; 15 -> "8.1"; 16 -> "9"; 17 -> "10"; 18 -> "11"
            19 -> "12"; 20 -> "13"; 21 -> "14"; 22 -> "15"; 23 -> "16"
            else -> null
        }

    // ---- crDroid ----

    class CrDroidInfo(val code: String, val latest: Int, val lastBuild: String?)

    /**
     * crDroid lists every official device on one page with links like .../<codename>/<version>
     * and a "Last build: YYYY-MM-DD" line after them.
     */
    fun parseCrDroid(html: String, code: String): CrDroidInfo? {
        val doc = Jsoup.parse(html, "https://crdroid.net")
        val selected = doc.select("a[href]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val uri = try { java.net.URI(href) } catch (_: Exception) { return@mapNotNull null }
            if (uri.host != null && !uri.host.equals("crdroid.net", true) &&
                !uri.host.equals("www.crdroid.net", true)) return@mapNotNull null
            val components = (uri.path ?: "").trimEnd('/').split('/').filter { it.isNotEmpty() }
            if (components.size < 2 || !components[components.lastIndex - 1].equals(code, true)) return@mapNotNull null
            val version = components.last().toIntOrNull() ?: return@mapNotNull null
            if (version !in 1..30) return@mapNotNull null
            Triple(components[components.lastIndex - 1], version, link)
        }
        if (selected.isEmpty()) return null
        val latest = selected.maxByOrNull { it.second } ?: return null
        // Date must belong to this entry, not a different device farther down the page.
        val next = latest.third.nextElementSibling()
        val date = next?.takeIf { it.text().contains("Last build:", true) }
            ?.text()?.let { Regex("""\d{4}-\d{2}-\d{2}""").find(it)?.value }
        return CrDroidInfo(latest.first, latest.second, date)
    }

    /** True when the last build is older than [days] days (unknown date = not stale). */
    fun isStale(lastBuild: String?, today: LocalDate, days: Long = 365): Boolean {
        if (lastBuild == null) return false
        return try {
            ChronoUnit.DAYS.between(LocalDate.parse(lastBuild), today) > days
        } catch (e: Exception) {
            false
        }
    }

    // ---- LineageOS download API (v1) ----

    class LineageBuild(
        val url: String,
        val fileName: String,
        val sizeBytes: Long?,
        val sha256: String?,
        val version: String?,
        val datetime: Long
    )

    /** Picks the newest build from `{"response":[{url, filename, id (sha256), size, version, datetime}, ...]}`. */
    fun parseLineageBuilds(json: String): LineageBuild? {
        val arr = JSONObject(json).optJSONArray("response") ?: return null
        var best: LineageBuild? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            val name = o.optString("filename")
            if (!url.startsWith("https://") || !name.endsWith(".zip")) continue
            val size = o.optLong("size", -1L)
            val id = o.optString("id")
            val version = o.optString("version")
            val build = LineageBuild(
                url = url,
                fileName = name,
                sizeBytes = if (size > 0) size else null,
                sha256 = if (id.length == 64) id else null,
                version = if (version.isBlank()) null else version,
                datetime = o.optLong("datetime", 0L)
            )
            if (best == null || build.datetime > best.datetime) best = build
        }
        return best
    }

    // ---- App versions: semantic version precedence, including alpha/beta/RC ----

    private data class Version(val numbers: List<Int>, val prerelease: List<String>)
    private fun version(s: String): Version {
        val raw = s.trim().removePrefix("v").substringBefore('+')
        val numbers = raw.substringBefore('-').split('.').take(3).map { it.toIntOrNull() ?: 0 }
        val suffix = raw.substringAfter('-', "").lowercase()
        return Version(numbers, if (suffix.isBlank()) emptyList() else suffix.split('.', '-'))
    }

    fun compareVersions(a: String, b: String): Int {
        val x = version(a); val y = version(b)
        for (i in 0..2) {
            val c = x.numbers.getOrElse(i) { 0 }.compareTo(y.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (x.prerelease.isEmpty()) return if (y.prerelease.isEmpty()) 0 else 1
        if (y.prerelease.isEmpty()) return -1
        val rank = mapOf("dev" to 0, "snapshot" to 0, "alpha" to 1, "beta" to 2, "rc" to 3)
        for (i in 0 until maxOf(x.prerelease.size, y.prerelease.size)) {
            if (i == x.prerelease.size) return -1
            if (i == y.prerelease.size) return 1
            val a0 = x.prerelease[i]; val b0 = y.prerelease[i]
            if (a0 == b0) continue
            val an = a0.toIntOrNull(); val bn = b0.toIntOrNull()
            val order = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                rank.containsKey(a0) && rank.containsKey(b0) -> rank.getValue(a0).compareTo(rank.getValue(b0))
                else -> a0.compareTo(b0)
            }
            if (order != 0) return order
        }
        return 0
    }

    fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0
}
