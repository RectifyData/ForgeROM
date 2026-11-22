package com.forgerom.app.sources

import com.forgerom.app.device.DeviceCompatibility
import com.forgerom.app.device.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {
    private val detected = DeviceInfo("Samsung", "samsung", "SM-G975F", "beyond2lte", "16", 36, "Exynos")

    @Test fun exactModelMatchesButDoesNotGuaranteeFlash() {
        val assessment = DeviceCompatibility.assess(detected, "beyond2lte", listOf("SM-G975F/DS"))
        assertEquals(DeviceCompatibility.Verdict.MODEL_MATCH, assessment.verdict)
        assertTrue(assessment.reason.contains("firmware"))
    }

    @Test fun sameCodenameWithWrongModelIsRejected() {
        assertEquals(DeviceCompatibility.Verdict.MODEL_MISMATCH,
            DeviceCompatibility.assess(detected, "beyond2lte", listOf("SM-G975U")).verdict)
    }

    @Test fun codenameOnlyIsUnverified() {
        assertEquals(DeviceCompatibility.Verdict.UNVERIFIED,
            DeviceCompatibility.assess(detected, "beyond2lte", emptyList()).verdict)
    }

    @Test fun differentCodenameIsMismatch() {
        assertEquals(DeviceCompatibility.Verdict.MODEL_MISMATCH,
            DeviceCompatibility.assess(detected, "beyond1lte", listOf("SM-G975F")).verdict)
    }
}
