package dev.herdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStatsTest {
    private val linuxOutput = """
        host=build-box
        stat=cpu  100 20 30 800 50 0 0 0 0 0
        load=0.52 0.41 0.30
        ncpu=8
        MemTotal=16384000
        MemAvailable=4096000
        uptime=93784.21
        df=/dev/nvme0n1p2 1000000 400000 600000 40% /
    """.trimIndent()

    private val macOutput = """
        host=mini.local
        load= 1.50 1.25 1.00
        ncpu=10
        memsize=17179869184
        boottime=1700000000
        now=1700090061
        pscpu=250.5
        pagesize=16384
        active=100000
        wired down=50000
        occupied by compressor=10000
        df=/dev/disk3s5 Data 500000000 300000000 200000000 61% /System/Volumes/Data
    """.trimIndent()

    @Test
    fun parsesLinuxProcOutput() {
        val sample = HostProbe.parse(linuxOutput)!!
        assertEquals("build-box", sample.hostname)
        assertEquals(CpuTicks(busy = 150, total = 1000), sample.cpuTicks)
        assertNull(sample.cpuUsage)
        assertEquals(listOf(0.52, 0.41, 0.30), sample.loadAverage)
        assertEquals(8, sample.cpuCount)
        assertEquals(16_384_000L * 1024, sample.memoryTotal)
        assertEquals(12_288_000L * 1024, sample.memoryUsed)
        assertEquals(93784.21, sample.uptimeSeconds!!, 0.001)
        assertEquals(1_000_000L * 1024, sample.diskTotal)
        assertEquals(600_000L * 1024, sample.diskFree)
    }

    @Test
    fun parsesMacOutputWithSpacesInTheFilesystemName() {
        val sample = HostProbe.parse(macOutput)!!
        assertEquals("mini", sample.hostname)
        assertNull(sample.cpuTicks)
        assertEquals(0.2505, sample.cpuUsage!!, 0.0001)
        assertEquals(listOf(1.5, 1.25, 1.0), sample.loadAverage)
        assertEquals(17_179_869_184L, sample.memoryTotal)
        assertEquals(160_000L * 16384, sample.memoryUsed)
        assertEquals(90061.0, sample.uptimeSeconds!!, 0.0)
        assertEquals(500_000_000L * 1024, sample.diskTotal)
        assertEquals(200_000_000L * 1024, sample.diskFree)
    }

    @Test
    fun rejectsOutputWithoutAHost() {
        assertNull(HostProbe.parse("load=1 2 3\n"))
        assertNull(HostProbe.parse(""))
    }

    @Test
    fun cpuUsageComesFromTheTicksBetweenSamples() {
        val first = CpuTicks(busy = 100, total = 1000)
        assertEquals(0.5, CpuTicks(busy = 150, total = 1100).usage(since = first)!!, 0.0)
        assertNull("no time passed", first.usage(since = first))
        assertNull("counters reset", CpuTicks(busy = 10, total = 50).usage(since = first))

        val one = HostStats.from(HostSample("h", cpuTicks = first), previous = null)
        assertNull("one sample has no usage yet", one.cpuUsage)
        val two = HostStats.from(HostSample("h", cpuTicks = CpuTicks(125, 1100)), one)
        assertEquals(0.25, two.cpuUsage!!, 0.0)
        val reset = HostStats.from(HostSample("h", cpuTicks = CpuTicks(1, 2)), two)
        assertEquals("a reset keeps the last reading", 0.25, reset.cpuUsage!!, 0.0)
    }

    @Test
    fun formatting() {
        assertEquals("0m", HostStats.uptimeText(59.0))
        assertEquals("3h 25m", HostStats.uptimeText(3 * 3600 + 25 * 60.0))
        assertEquals("2d 5h", HostStats.uptimeText(2 * 86400 + 5 * 3600 + 60.0))
        assertEquals("512 MB", HostStats.bytesText(512L * 1_048_576))
        assertEquals("16.0 GB", HostStats.bytesText(17_179_869_184L))
        assertEquals("500 GB", HostStats.bytesText(500L * 1_073_741_824))
    }

    /** Runs the probe on this machine through `sh -c`, as sshj's exec would, to check its quoting. */
    @Test
    fun probeRunsOnThisMachine() {
        val process = ProcessBuilder("/bin/sh", "-c", HostProbe.command()).redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        val sample = HostProbe.parse(output)
        assertNotNull(output, sample)
        sample!!
        assertTrue(output, sample.cpuTicks != null || sample.cpuUsage != null)
        assertTrue(output, (sample.memoryUsed ?: 0) > 0 && sample.memoryUsed!! <= sample.memoryTotal!!)
        assertTrue(output, sample.diskFree!! <= sample.diskTotal!!)
        assertTrue(output, sample.uptimeSeconds!! > 0)
    }
}
