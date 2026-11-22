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
                "version": "v1.0", "file_name": "rikka.zip",
                "folder_url": "https://gofile.io/d/ExamplePublicFolder",
                "public_password": "OwnerPublishedExample",
                "password_source_url": "https://xdaforums.com/t/rikka"}],
              "extra_images": [
                {"label": "Vendor", "url": "https://pixeldrain.com/u/vendor",
                  "partition": "vendor", "file_name": "vendor.img"},
                {"label": "Kernel", "url": "https://pixeldrain.com/u/boot",
                  "partition": "boot", "file_name": "kernel.img"}
              ],
              "extra_zips": [
                {"label": "Repartition before install", "kind": "repartitioner",
                  "required": true, "notes": "No confirmed ZIP",
                  "documentation_url": "https://xdaforums.com/t/rikka",
                  "folder_url": "https://gofile.io/d/ExampleRepartitioner",
                  "public_password": "ExamplePublicCode",
                  "password_source_url": "https://xdaforums.com/t/rikka"},
                {"label": "Optional patch", "kind": "patch",
                  "required": false, "file_name": "patch.zip",
                  "url": "https://example.org/patch.zip", "models": ["SM-G610F/DS"]},
                {"label": "Wrong model ZIP", "kind": "patch",
                  "url": "https://example.org/wrong.zip", "models": ["SM-G975F"]},
                {"label": "Bad scheme ZIP", "kind": "repartitioner",
                  "url": "http://example.org/bad.zip", "sha256": "bad"}
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

    @Test fun displaysOwnerPublishedPasswordAndOriginalFolderWithoutAutoDownload() {
        val rom = CuratedRomParser.parse(fixture, j7).single()
        val pkg = rom.artifacts.single { it.fileName == "rikka.zip" }
        assertEquals("https://gofile.io/d/ExamplePublicFolder", pkg.folderAccess?.folderUrl)
        assertEquals("OwnerPublishedExample", pkg.folderAccess?.publicPassword)
        assertEquals("https://xdaforums.com/t/rikka", pkg.folderAccess?.passwordSourceUrl)
        assertEquals(null, rom.download)
        val zip = rom.extraZips.first()
        assertEquals("ExamplePublicCode", zip.folderAccess?.publicPassword)
        assertEquals(null, zip.url)
    }

    @Test fun passwordDoesNotRequirePublicSourceLink() {
        val withoutSource = fixture.replace(
            ", \"password_source_url\": \"https://xdaforums.com/t/rikka\"",
            "")
        val rom = CuratedRomParser.parse(withoutSource, j7).single()
        assertEquals("OwnerPublishedExample", rom.artifacts.first().folderAccess?.publicPassword)
        assertEquals("ExamplePublicCode", rom.extraZips.first().folderAccess?.publicPassword)
        assertEquals(null, rom.artifacts.first().folderAccess?.passwordSourceUrl)
        assertTrue(rom.artifacts.first().folderAccess?.folderUrl!!.startsWith("https://"))
    }

    @Test fun invalidOptionalSourceDoesNotHidePassword() {
        val changed = fixture.replace(
            "\"password_source_url\": \"https://xdaforums.com/t/rikka\"",
            "\"password_source_url\": \"http://invalid.example/secret\"")
        val rom = CuratedRomParser.parse(changed, j7).single()
        assertEquals("OwnerPublishedExample", rom.artifacts.first().folderAccess?.publicPassword)
        assertEquals(null, rom.artifacts.first().folderAccess?.passwordSourceUrl)
    }

    @Test fun gofileFolderWithoutASeparateDownloadPageIsShown() {
        val changed = fixture.replace(
            "\"label\": \"ROM\", \"url\": \"https://pixeldrain.com/u/rom\",",
            "\"label\": \"ROM\",")
        val rom = CuratedRomParser.parse(changed, j7).single()
        assertEquals("https://gofile.io/d/ExamplePublicFolder", rom.artifacts.first().url)
        assertEquals(1, rom.artifacts.count { it.folderAccess != null })
        assertEquals(null, rom.download)
    }

    @Test fun blankPasswordIsNotDisplayed() {
        val changed = fixture.replace(
            "\"public_password\": \"OwnerPublishedExample\"",
            "\"public_password\": \" \"")
        val rom = CuratedRomParser.parse(changed, j7).single()
        assertEquals(null, rom.artifacts.first().folderAccess?.publicPassword)
    }

    @Test fun nullOwnerPasswordDoesNotBecomeLiteralNullString() {
        val changed = fixture.replace(
            "\"public_password\": \"OwnerPublishedExample\"",
            "\"public_password\": null")
        val rom = CuratedRomParser.parse(changed, j7).single()
        assertEquals(null, rom.artifacts.first().folderAccess?.publicPassword)
        assertEquals("https://gofile.io/d/ExamplePublicFolder",
            rom.artifacts.first().folderAccess?.folderUrl)
    }

    @Test fun ignoresUnsafeFolderSchemesAndInvalidAccessCodes() {
        val injected = fixture.replace(
            "https://gofile.io/d/ExamplePublicFolder",
            "javascript:alert(1)")
        val rom = CuratedRomParser.parse(injected, j7).single()
        assertEquals(null, rom.artifacts.first().folderAccess)
        assertTrue(rom.artifacts.first().url.startsWith("https://"))
    }

    @Test fun supportsMissingRequiredZipLinksAndModelSpecificExtras() {
        val rom = CuratedRomParser.parse(fixture, j7).single()
        assertEquals(3, rom.extraZips.size)
        val repartitioner = rom.extraZips.first()
        assertTrue(repartitioner.required)
        assertEquals("repartitioner", repartitioner.kind)
        assertEquals(null, repartitioner.url)
        assertTrue(repartitioner.documentationUrl!!.startsWith("https://"))
        val optional = rom.extraZips[1]
        assertFalse(optional.required)
        assertEquals("patch.zip", optional.fileName)
        assertEquals(listOf("SM-G610F/DS"), optional.applicableModels)
        val insecure = rom.extraZips[2]
        assertEquals(null, insecure.url)
        assertEquals(null, insecure.sha256)
    }

    @Test fun optionalZipMatchingCannotLeakAcrossDeviceVariants() {
        val rom = CuratedRomParser.parse(fixture, j7.copy(model = "SM-G610F/DS")).single()
        assertTrue(rom.extraZips.any { it.fileName == "patch.zip" })
        val otherRom = CuratedRomParser.parse(fixture, j7.copy(model = "SM-G610F")).single()
        assertTrue(otherRom.extraZips.any { it.fileName == "patch.zip" })
        assertFalse(rom.extraZips.any { it.label == "Wrong model ZIP" })
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
