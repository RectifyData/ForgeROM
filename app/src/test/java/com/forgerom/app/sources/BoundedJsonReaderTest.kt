package com.forgerom.app.sources

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedJsonReaderTest {
    @Test fun normalJsonCanBeReadWithoutRequiringMaximumBytes() {
        val sample = """{"schema_version":1,"devices":[]}"""
        val read = BoundedJsonReader.read(ByteArrayInputStream(sample.toByteArray()), 2 * 1024 * 1024)
        assertEquals(sample, read)
    }

    @Test fun rejectsOversizedResponse() {
        val input = ByteArrayInputStream(ByteArray(33) { 65 })
        assertNull(BoundedJsonReader.read(input, 32))
    }

    @Test fun acceptsExactlyTheMaximum() {
        val value = "a".repeat(32)
        assertEquals(value, BoundedJsonReader.read(ByteArrayInputStream(value.toByteArray()), 32))
    }

    @Test fun supportsEmptyValidResponsesForCallerToParse() {
        assertEquals("", BoundedJsonReader.read(ByteArrayInputStream(byteArrayOf()), 32))
    }
}
