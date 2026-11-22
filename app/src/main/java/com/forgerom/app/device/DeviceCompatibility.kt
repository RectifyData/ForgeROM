package com.forgerom.app.device

import java.util.Locale

/** Source-level matching only: never interpret a codename as proof an image is safe to flash. */
object DeviceCompatibility {
    enum class Verdict { MODEL_MATCH, UNVERIFIED, MODEL_MISMATCH }

    data class Assessment(val verdict: Verdict, val reason: String)

    fun assess(device: DeviceInfo, sourceCodename: String, supportedModels: List<String>): Assessment {
        if (sourceCodename.isBlank() || !device.codename.equals(sourceCodename, ignoreCase = true)) {
            return Assessment(Verdict.MODEL_MISMATCH, "Device codename does not match the release")
        }
        if (supportedModels.isEmpty() || device.model.isBlank()) {
            return Assessment(Verdict.UNVERIFIED,
                "Codename matches, but model variant, CPU architecture, partitions and required firmware are not verified. Read the device-specific installation guide")
        }
        val normalized = SourceModel.normalize(device.model)
        val matches = supportedModels.any { SourceModel.normalize(it) == normalized }
        return if (matches) Assessment(Verdict.MODEL_MATCH,
            "Model matches the maintainer list; CPU architecture, partition layout and firmware requirements must still be checked in the official guide")
        else Assessment(Verdict.MODEL_MISMATCH,
            "Model ${device.model} is not listed for this build; do not use the file for this variant")
    }

    private object SourceModel {
        fun normalize(model: String): String = model.uppercase(Locale.ROOT).trim()
            .replace(Regex("(/DS|/DUAL|[- ]DUOS)$"), "")
            .replace(" ", "").trim()
    }
}
