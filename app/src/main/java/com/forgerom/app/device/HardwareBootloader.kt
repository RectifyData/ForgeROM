package com.forgerom.app.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Read-only bootloader observations from the running device.
 * These properties are reported by firmware, not an unlock permission.
 * OEM user/carrier eligibility generally requires privileged OS APIs or fastboot.
 */
object HardwareBootloader {
    enum class LockState { LOCKED, UNLOCKED, UNKNOWN }
    enum class UnlockSupport { REPORTED_SUPPORTED, REPORTED_UNSUPPORTED, UNKNOWN }

    data class Report(
        val state: LockState,
        val support: UnlockSupport,
        val verifiedBoot: String?,
        val source: String,
        val detail: String
    )

    suspend fun inspect(): Report = withContext(Dispatchers.IO) {
        val flashLocked = property("ro.boot.flash.locked")
        val vbmeta = property("ro.boot.vbmeta.device_state")?.lowercase()
        val bootState = property("ro.boot.verifiedbootstate")?.lowercase()
        val supportProperty = property("ro.oem_unlock_supported")
        val stateByFlash = when (flashLocked) {
            "0" -> LockState.UNLOCKED
            "1" -> LockState.LOCKED
            else -> LockState.UNKNOWN
        }
        val stateByVbmeta = when (vbmeta) {
            "unlocked" -> LockState.UNLOCKED
            "locked" -> LockState.LOCKED
            else -> LockState.UNKNOWN
        }
        val conflicts = stateByFlash != LockState.UNKNOWN &&
            stateByVbmeta != LockState.UNKNOWN && stateByFlash != stateByVbmeta
        val state = when {
            conflicts -> LockState.UNKNOWN
            stateByFlash != LockState.UNKNOWN -> stateByFlash
            else -> stateByVbmeta
        }
        val support = when (supportProperty) {
            "1" -> UnlockSupport.REPORTED_SUPPORTED
            "0" -> UnlockSupport.REPORTED_UNSUPPORTED
            else -> UnlockSupport.UNKNOWN
        }
        val source = when {
            conflicts -> "Conflicting bootloader properties"
            stateByFlash != LockState.UNKNOWN -> "ro.boot.flash.locked"
            stateByVbmeta != LockState.UNKNOWN -> "ro.boot.vbmeta.device_state"
            else -> "No readable lock-state property"
        }
        val detail = if (conflicts) {
            "Firmware properties disagree. Verify the device state in fastboot or download mode."
        } else {
            "OEM unlock eligibility, carrier policy, and the Developer Options toggle are not reliably accessible to normal apps. A reported capability is not permission to unlock."
        }
        Report(state, support, bootState?.ifBlank { null }, source, detail)
    }

    private fun property(key: String): String? {
        val process = try {
            ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { it.readText().trim() }.ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }
}
