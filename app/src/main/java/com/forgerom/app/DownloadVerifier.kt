package com.forgerom.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.forgerom.app.ai.DownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File

/** Results are intentionally not described as 'verified' without a trusted SHA-256. */
data class DownloadVerification(val filename: String, val status: Status, val details: String = "") {
    enum class Status { PENDING, VERIFIED, UNVERIFIED, FAILED }
}

object DownloadVerifier {
    private const val PREF = "forgerom_downloads"
    private const val KEY = "records"
    private const val RESULTS = "results"

    @Synchronized
    fun track(context: Context, id: Long, file: DownloadInfo) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val records = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        records.put(JSONObject().put("id", id).put("file", file.fileName)
            .put("size", file.sizeBytes ?: -1L).put("sha", file.sha256 ?: ""))
        prefs.edit().putString(KEY, records.toString()).apply()
    }

    suspend fun scan(context: Context): List<DownloadVerification> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val records = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val pending = JSONArray()
        val results = mutableListOf<DownloadVerification>()
        val past = try { JSONArray(prefs.getString(RESULTS, "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until past.length()) {
            val entry = past.optJSONObject(i) ?: continue
            val name = entry.optString("file")
            val status = try { DownloadVerification.Status.valueOf(entry.optString("status")) }
                catch (_: Exception) { continue }
            results += DownloadVerification(name, status, entry.optString("detail"))
        }
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val id = record.optLong("id", -1L)
            val file = record.optString("file").take(180)
            val sha = record.optString("sha").lowercase()
            val size = record.optLong("size", -1L)
            if (id < 0) continue
            try {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        results += DownloadVerification(file, DownloadVerification.Status.FAILED, "Download no longer exists")
                    } else {
                        val col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val state = if (col >= 0) cursor.getInt(col) else -1
                        when (state) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uri: Uri? = manager.getUriForDownloadedFile(id)
                                results += if (uri == null) DownloadVerification(file, DownloadVerification.Status.FAILED, "File not accessible")
                                    else verify(context, uri, file, sha, size)
                            }
                            DownloadManager.STATUS_FAILED -> results += DownloadVerification(file, DownloadVerification.Status.FAILED, "Download failed")
                            else -> {
                                results += DownloadVerification(file, DownloadVerification.Status.PENDING)
                                pending.put(record)
                            }
                        }
                    }
                } ?: run { results += DownloadVerification(file, DownloadVerification.Status.FAILED, "Download not found") }
            } catch (e: Exception) {
                results += DownloadVerification(file, DownloadVerification.Status.FAILED, "Cannot access download: ${e.javaClass.simpleName}")
            }
        }
        // Keep incomplete items; completed results remain in this session, do not rehash gigantic files on each launch.
        val finished = JSONArray()
        results.filter { it.status != DownloadVerification.Status.PENDING }.takeLast(10).forEach {
            finished.put(JSONObject().put("file", it.filename).put("status", it.status.name).put("detail", it.details))
        }
        prefs.edit().putString(KEY, pending.toString()).putString(RESULTS, finished.toString()).apply()
        results.distinctBy { it.filename to it.status }
    }

    private fun verify(context: Context, uri: Uri, file: String, sha: String, expectedBytes: Long): DownloadVerification {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            val stream = context.contentResolver.openInputStream(uri)
                ?: return DownloadVerification(file, DownloadVerification.Status.FAILED, "Cannot read file")
            val isApk = file.endsWith(".apk", true)
            val temp = if (isApk) File(context.cacheDir, "pending-update-${System.nanoTime()}.apk") else null
            try {
                stream.use { input ->
                    val output = temp?.outputStream()
                    try {
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            length += read
                            digest.update(buffer, 0, read)
                            output?.write(buffer, 0, read)
                        }
                    } finally { output?.close() }
                }
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                when {
                    expectedBytes > 0 && expectedBytes != length ->
                        DownloadVerification(file, DownloadVerification.Status.FAILED, "Size mismatch")
                    sha.matches(Regex("[a-f0-9]{64}")) && sha != sum ->
                        DownloadVerification(file, DownloadVerification.Status.FAILED, "SHA-256 mismatch")
                    sha.matches(Regex("[a-f0-9]{64}")) -> {
                        if (isApk) {
                            if (temp == null || !VerifiedUpdateInstaller.matchesInstalledSignature(context, temp))
                                DownloadVerification(file, DownloadVerification.Status.FAILED,
                                    "APK signature or package ID differs from installed ForgeROM")
                            else {
                                val dest = VerifiedUpdateInstaller.verifiedFile(context, file)
                                if (!temp.copyTo(dest, overwrite = true).exists())
                                    DownloadVerification(file, DownloadVerification.Status.FAILED, "Cannot save verified APK")
                                else DownloadVerification(file, DownloadVerification.Status.VERIFIED,
                                    "SHA-256 and installed APK signing certificate match")
                            }
                        } else DownloadVerification(file, DownloadVerification.Status.VERIFIED,
                            "SHA-256 matches published checksum")
                    }
                    else -> DownloadVerification(file, DownloadVerification.Status.UNVERIFIED,
                        "No trusted SHA-256 was supplied; verify manually before use")
                }
            } finally { temp?.delete() }
        } catch (e: Exception) {
            DownloadVerification(file, DownloadVerification.Status.FAILED, "Cannot verify file: ${e.javaClass.simpleName}")
        }
    }
}
