package dev.herdroid.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class OpenSshEd25519Test {
    @Test
    fun encodesOpensshKeyV1Container() {
        val seed = ByteArray(32) { it.toByte() }
        val pub = ByteArray(32) { (it + 100).toByte() }
        val pem = OpenSshEd25519.encodePrivateKey(seed, pub, "test@herdroid")
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(pem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        val body = java.util.Base64.getDecoder().decode(
            pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        )
        val buf = ByteBuffer.wrap(body)
        val magic = ByteArray(15).also { buf.get(it) }
        assertEquals("openssh-key-v1\u0000", String(magic))
        fun str(): ByteArray = ByteArray(buf.int).also { buf.get(it) }
        assertEquals("none", String(str()))
        assertEquals("none", String(str()))
        assertEquals(0, str().size)
        assertEquals(1, buf.int)
        assertArrayEquals(OpenSshEd25519.publicBlob(pub), str())
        val private = ByteBuffer.wrap(str())
        assertEquals(0, private.capacity() % 8)
        assertEquals(private.int, private.int) // check ints match
    }
}
