package com.forgerom.app.sources

import com.forgerom.app.device.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedRomParserTest {
    private val j7 = DeviceInfo("Samsung", "samsung", "SM-G610F", "on7xelte", "8.1", 27, "Exynos")

    private val fixture = """
        {
          "schema_version": 1,
          "devices": [{
            "brand": "Samsung",
            "marketing": "Galaxy J7 Prime",
            "codename": "on7xelte",
            "models": ["SM-G610F", "SM-G610F/DS"],
            "roms": [{
              "name": "RikkaOS One UI 7",
              "android_version": "15",
              "status": "unstable",
              "source_url": "https://xdaforums.com/t/rikka",
              "packages": [{"label": "ROM", "url": "https://pixeldrain.com/u/rom",
                "version": "v1.0", "file_name": "rikka.zip"}],
              "extra_images": [
                {"label": "Vendor", "url": "https://pixeldrain.com/u/vendor",
                  "partition": "vendor", "file_name": "vendor.img"},
                {"label": "Kernel", "url": "https://pixeldrain.com/u/boot",
                  "partition": "boot", "file_name": "kernel.img"}
              ],
              "bugs": [
                {"component": "Face unlock", "status": "broken"},
                {"component": "16 GB model", "status": "unsupported"}
              ],
              "warnings": ["16 GB model is not supported"],
              "credits": ["Port author"]
            }]
          }]
        }
    """.trimIndent()

    @Test fun exposesPackagesPartitionImagesAndBugReports() {
        val rom = CuratedRomParser.parse(fixture, j7).single()
        assertEquals("unstable", rom.status)
        assertEquals(3, rom.artifacts.size)
        assertEquals(listOf("vendor", "boot"), rom.artifacts.mapNotNull { it.partition })
        assertEquals(2, rom.bugs.size)
        assertEquals("Face unlock", rom.bugs.first().component)
        assertTrue(rom.safetyNotes.single().contains("16 GB"))
        assertEquals("Port author", rom.credits.single())
        assertEquals(null, rom.download) // Community links are not trusted auto-downloads
    }

    @Test fun doesNotShowWrongModelOrCodename() {
        val wrongModel = j7.copy(model = "SM-G975F")
        val wrongCodename = j7.copy(codename = "beyond2lte")
        assertTrue(CuratedRomParser.parse(fixture, wrongModel).isEmpty())
        assertTrue(CuratedRomParser.parse(fixture, wrongCodename).isEmpty())
    }

    @Test fun rejectsInsecureMetadataLinks() {
        val injected = fixture.replace("https://xdaforums.com/t/rikka", "http://xdaforums.com/t/rikka")
        assertTrue(CuratedRomParser.parse(injected, j7).isEmpty())
        val parts = fixture.replace("https://pixeldrain.com/u/vendor", "https://evil@pixeldrain.com/u/vendor")
        val rom = CuratedRomParser.parse(parts, j7).single()
        assertFalse(rom.artifacts.any { it.partition == "vendor" })
    }

    @Test fun futureSchemaDoesNotCrashOldApps() {
        assertTrue(CuratedRomParser.parse(fixture.replace("\"schema_version\": 1",
            "\"schema_version\": 999"), j7).isEmpty())
    }
}
