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

    @Test fun malformedNumericFieldsNeverProduceNonfiniteOrNegativeMetrics() {
        listOf("NaN", "Infinity", "-Infinity", "-1").forEach { invalid ->
            val mac = HostProbe.parse("host=bad\nncpu=2\npscpu=$invalid\nload=$invalid 1 2\nboottime=$invalid\nnow=1")!!
            assertNull(mac.cpuUsage)
            assertNull(mac.uptimeSeconds)
            assertEquals(listOf(1.0, 2.0), mac.loadAverage)
            assertNull(HostProbe.parse("host=bad\nstat=cpu 1 2 3 4\nuptime=$invalid")!!.uptimeSeconds)
        }
        val sample = HostProbe.parse("host=bad\nncpu=-1\nmemsize=-1\npagesize=-1\nactive=2\ndf=x -1 0 -1 0% /")!!
        assertNull(sample.cpuCount)
        assertNull(sample.memoryTotal)
        assertNull(sample.memoryUsed)
        assertNull(sample.diskTotal)
        assertNull(sample.diskFree)
    }

    @Test fun oversizedCountersDoNotWrapAroundOrShiftInvalidTickPositions() {
        val overflow = HostProbe.parse("host=bad\nstat=cpu ${Long.MAX_VALUE} 1 0 0\nMemTotal=${Long.MAX_VALUE}\nMemAvailable=0\ndf=x ${Long.MAX_VALUE} 0 1 0% /")!!
        assertNull(overflow.cpuTicks)
        assertNull(overflow.memoryTotal)
        assertNull(overflow.diskTotal)
        assertNull(HostProbe.parse("host=bad\nstat=cpu 1 invalid 2 3 4")!!.cpuTicks)
        assertNull(HostProbe.parse("host=bad\nstat=cpu 1 -1 2 3 4")!!.cpuTicks)
        assertNull(HostProbe.parse("host=bad\npagesize=16384\nactive=${Long.MAX_VALUE}")!!.memoryUsed)
        assertNull(CpuTicks(-1, 100).usage(CpuTicks(0, 1)))
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
        // A Mac always has an accelerator; elsewhere a GPU is optional.
        if ("ioreg=" in output) assertTrue(output, sample.gpus.isNotEmpty())
    }

    // Captured on an Apple M4 Max (macOS 26.6.2) through the probe's ioreg | grep pipeline.
    private val appleIoreg = """
        ioreg=+-o AGXAcceleratorG16X  <class AGXAcceleratorG16X, id 0x100000542, registered, matched, active, busy 0 (1581 ms), retain 62>
        ioreg=      "PerformanceStatistics" = {"In use system memory (driver)"=0,"Alloc system memory"=13397131264,"Tiler Utilization %"=0,"recoveryCount"=0,"lastRecoveryTime"=0,"Renderer Utilization %"=0,"TiledSceneBytes"=2031616,"Device Utilization %"=95,"SplitSceneCount"=0,"Allocated PB Size"=154533888,"In use system memory"=10380492800}
        ioreg=      "model" = "Apple M4 Max"
    """.trimIndent()

    @Test fun noGpuSourceMeansNoGpus() {
        assertTrue(HostProbe.parse(linuxOutput)!!.gpus.isEmpty())
        assertTrue(HostProbe.parse(macOutput)!!.gpus.isEmpty())
    }

    @Test fun parsesOneNvidiaGpu() {
        val gpus = HostProbe.parse(linuxOutput + "\nnvidia=NVIDIA GeForce RTX 4090, 37, 10240, 24564")!!.gpus
        assertEquals(listOf(GpuSample("NVIDIA GeForce RTX 4090", 0.37, 10_240L * 1_048_576, 24_564L * 1_048_576)), gpus)
        // CPU, memory and disk are unaffected by the GPU lines.
        assertEquals(HostProbe.parse(linuxOutput)!!, HostProbe.parse(linuxOutput + "\nnvidia=x, 1, 2, 3")!!.copy(gpus = emptyList()))
    }

    @Test fun parsesTwoNvidiaGpusInOrder() {
        val gpus = HostProbe.parse(linuxOutput + """
            |
            |nvidia=NVIDIA A100-SXM4-80GB, 0, 4, 81920
            |nvidia=NVIDIA A100-SXM4-80GB, 100, 81000, 81920
        """.trimMargin())!!.gpus
        assertEquals(2, gpus.size)
        assertEquals(listOf(0.0, 1.0), gpus.map { it.utilization })
        assertEquals(81_000L * 1_048_576, gpus[1].memoryUsed)
        val stats = HostStats(HostProbe.parse(linuxOutput)!!.copy(gpus = gpus), null)
        assertEquals(0.5, stats.gpuUsage!!, 0.0)
        assertEquals("2 GPUs · avg 50%", HostStats.gpusText(gpus))
    }

    @Test fun parsesAmdSysfsReadings() {
        val gpus = HostProbe.parse(linuxOutput + "\namdgpu=23 2147483648 17163091968\namdgpu=- 1048576 -")!!.gpus
        assertEquals(GpuSample(null, 0.23, 2_147_483_648L, 17_163_091_968L), gpus[0])
        assertEquals(GpuSample(null, null, 1_048_576L, null), gpus[1])
        assertEquals("23% · 2.0 GB / 16.0 GB", HostStats.gpuText(gpus[0]))
    }

    @Test fun parsesAppleSiliconIoreg() {
        val gpus = HostProbe.parse(macOutput + "\n" + appleIoreg)!!.gpus
        assertEquals(listOf(GpuSample("Apple M4 Max", 0.95, 10_380_492_800L, null)), gpus)
        assertEquals("95% · 9.7 GB in use", HostStats.gpuText(gpus.single()))
    }

    @Test fun malformedOrPartialGpuOutputYieldsNullFieldsNotACrash() {
        val output = linuxOutput + """
            |
            |nvidia=No devices were found
            |nvidia=NVIDIA-SMI has failed because it couldn't communicate with the NVIDIA driver.
            |nvidia=Tesla T4, [N/A], 15, 15360
            |nvidia=Broken, abc, -5, 0
            |nvidia=Over, 250, 1, 2
            |amdgpu=abc
            |amdgpu=1 2
            |amdgpu=- - -
            |ioreg=+-o IntelAccelerator  <class IntelAccelerator>
            |ioreg=      "model" = <"Intel Iris Plus Graphics">
            |ioreg=+-o Garbage
            |ioreg=      "PerformanceStatistics" = {"Device Utilization %"=900,"In use system memory"=-1}
        """.trimMargin()
        val gpus = HostProbe.parse(output)!!.gpus
        assertEquals(listOf(
            GpuSample("Tesla T4", null, 15L * 1_048_576, 15_360L * 1_048_576),
            GpuSample("Over", null, 1_048_576L, 2L * 1_048_576),
        ), gpus)
        assertEquals("… · 15 MB / 15.0 GB", HostStats.gpuText(gpus[0]))
    }
}
