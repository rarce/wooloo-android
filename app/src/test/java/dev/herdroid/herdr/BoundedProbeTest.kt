package dev.herdroid.herdr

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BoundedProbeTest {
    @Test fun exactLimitIsAcceptedWithoutTruncation() {
        val bytes = ByteArray(64) { it.toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBoundedBytes(64))
    }

    @Test fun overflowingResponseIsRejectedAfterOneExtraByteWithoutContentsInError() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return 'S'.code }
        }
        try {
            input.readBoundedBytes(64)
            fail("Expected an oversized response error")
        } catch (e: IOException) {
            assertEquals("Remote response exceeds the size limit", e.message)
            assertEquals(65, reads)
        }
    }
}
