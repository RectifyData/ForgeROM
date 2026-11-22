package com.forgerom.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stages a user-picked ZIP in Downloads/ForgeROM for a recovery-assisted manual install.
 * Does not create recovery scripts or flash partitions.
 */
object RecoveryInstaller {
    data class Prepared(val name: String, val sha256: String, val location: String)
    private const val MAX_SIZE = 10L * 1024 * 1024 * 1024

    suspend fun prepare(context: Context, source: Uri, publishedSha256: String): Result<Prepared> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(Build.VERSION.SDK_INT >= 29) {
                    "On Android 8-9, save the ROM ZIP to Downloads manually, then install it in recovery."
                }
                val expected = publishedSha256.trim().lowercase(Locale.ROOT)
                require(expected.matches(Regex("[a-f0-9]{64}"))) {
                    "A 64-character official SHA-256 checksum is required"
                }
                val displayName = "ForgeROM-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeROM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val destination = requireNotNull(
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ) { "Could not create Downloads file" }
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytesCopied = 0L
                    resolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "Could not open selected ZIP" }
                        val header = ByteArray(4)
                        require(input.read(header) == 4 && header[0] == 'P'.code.toByte() &&
                            header[1] == 'K'.code.toByte() &&
                            header[2] == 3.toByte() && header[3] == 4.toByte()) {
                            "Selected file is not a standard ZIP archive"
                        }
                        resolver.openOutputStream(destination, "w").use { output ->
                            requireNotNull(output) { "Could not write to Downloads" }
                            output.write(header)
                            digest.update(header)
                            bytesCopied += header.size
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytesCopied += count
                                check(bytesCopied <= MAX_SIZE) { "ROM ZIP exceeds the size limit" }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    require(bytesCopied > 4) { "ROM ZIP is empty" }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual == expected) { "SHA-256 mismatch: download may be corrupt. Nothing was installed." }
                    resolver.update(destination, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    Prepared(displayName, actual, "Downloads/ForgeROM/" + displayName)
                } catch (error: Exception) {
                    resolver.delete(destination, null, null)
                    throw error
                }
            }
        }
}
