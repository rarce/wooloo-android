package dev.herdroid.connection

import dev.herdroid.herdr.HostProbe
import dev.herdroid.ssh.CommandChannel
import dev.herdroid.ssh.RemoteShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class HostProbeReadTest {
    /** A probe whose last tool (a hung nvidia-smi) never ends the output. */
    private class StalledShell(private val printed: String) : RemoteShell {
        override val isConnected = true
        override suspend fun exec(command: String): CommandChannel = object : CommandChannel {
            override val stdout = PipedInputStream(64 * 1024)
            private val remote = PipedOutputStream(stdout).apply { write(printed.toByteArray()); flush() }
            override val stdin = ByteArrayOutputStream()
            override val stderr = byteArrayOf().inputStream()
            override val isOpen = true
            override fun close() { runCatching { remote.close() } }
        }
        override fun close() {}
    }

    @Test(timeout = 10_000) fun aStalledGpuToolCostsOnlyTheGpuRow(): Unit = runBlocking {
        val printed = "host=gpu-box\nstat=cpu 1 0 1 8 0 0 0 0\nMemTotal=1000\nMemAvailable=500\n" +
            "df=/dev/sda1 100 40 60 40% /\nnvidia=NVIDIA H100, 4"
        val (output, finished) = readHostProbe(StalledShell(printed), "probe", timeoutMs = 300)
        assertFalse(finished)
        val sample = HostProbe.parse(output)!!
        assertEquals("gpu-box", sample.hostname)
        assertEquals(1000L * 1024, sample.memoryTotal)
        assertEquals(60L * 1024, sample.diskFree)
        // The cut-off line is dropped, not parsed as a GPU with bogus numbers.
        assertTrue(sample.gpus.isEmpty())
    }
}
