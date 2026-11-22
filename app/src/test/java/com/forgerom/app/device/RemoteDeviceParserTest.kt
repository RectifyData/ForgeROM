package com.forgerom.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceParserTest {
    @Test fun multipleModelsBecomeSearchableEntries() {
        val json = """{"schema_version":1,"devices":[{"brand":"Samsung","marketing":"Galaxy S10+","codename":"beyond2lte","models":["SM-G975F","SM-G975F/DS"]}]}"""
        val rows = RemoteDeviceParser.parse(json)
        assertEquals(2, rows.size)
        assertEquals("beyond2lte", rows[0].codename)
        assertTrue(rows.all { it.name.contains("S10+") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongSchemaIsRejected() {
        RemoteDeviceParser.parse("""{"schema_version":2,"devices":[]}""")
    }

    @Test fun noModelsMeansNoSearchableDevice() {
        assertTrue(RemoteDeviceParser.parse("""{"schema_version":1,"devices":[{"brand":"X","marketing":"Y","codename":"foo","models":[]}]}""").isEmpty())
    }
}
