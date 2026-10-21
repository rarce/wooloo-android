package dev.herdroid.herdr

import java.util.Locale

/** Cumulative CPU time of a host, in ticks. Usage is the busy share of the ticks between two samples. */
data class CpuTicks(val busy: Long, val total: Long) {
    fun usage(since: CpuTicks): Double? {
        if (busy < 0 || total < busy || since.busy < 0 || since.total < since.busy) return null
        if (total <= since.total || busy < since.busy) return null
        return minOf(1.0, (busy - since.busy).toDouble() / (total - since.total))
    }
}

/** One reading of a host's resources. Fields a host cannot report stay null. */
data class HostSample(
    val hostname: String,
    val cpuTicks: CpuTicks? = null,
    /** Instant CPU usage (0..1), for hosts without tick counters (a Mac). */
    val cpuUsage: Double? = null,
    val loadAverage: List<Double> = emptyList(),
    val cpuCount: Int? = null,
    val memoryUsed: Long? = null,
    val memoryTotal: Long? = null,
    val uptimeSeconds: Double? = null,
    val diskFree: Long? = null,
    val diskTotal: Long? = null,
    /** Empty when the host has no GPU the probe can read. */
    val gpus: List<GpuSample> = emptyList(),
)

/** One GPU. Apple's unified memory has no separate total, so [memoryTotal] stays null there. */
data class GpuSample(
    val name: String? = null,
    /** 0..1 */
    val utilization: Double? = null,
    val memoryUsed: Long? = null,
    val memoryTotal: Long? = null,
) {
    val memoryFraction: Double?
        get() {
            val used = memoryUsed ?: return null
            val total = memoryTotal?.takeIf { it > 0 } ?: return null
            return minOf(1.0, used.toDouble() / total)
        }
}

/** What the spaces screen shows: a sample plus the CPU usage worked out from the previous one. */
data class HostStats(val sample: HostSample, val cpuUsage: Double?) {
    val memoryFraction: Double?
        get() {
            val used = sample.memoryUsed ?: return null
            val total = sample.memoryTotal?.takeIf { it > 0 } ?: return null
            return minOf(1.0, used.toDouble() / total)
        }

    val diskUsedFraction: Double?
        get() {
            val free = sample.diskFree ?: return null
            val total = sample.diskTotal?.takeIf { it > 0 } ?: return null
            return minOf(1.0, (total - minOf(free, total)).toDouble() / total)
        }

    /** Mean utilization of the GPUs that report one. */
    val gpuUsage: Double?
        get() = sample.gpus.mapNotNull { it.utilization }.takeIf { it.isNotEmpty() }?.average()

    companion object {
        /** CPU usage comes from the sample itself, the ticks since [previous], or stays as it was. */
        fun from(sample: HostSample, previous: HostStats?): HostStats {
            val usage = sample.cpuUsage
                ?: sample.cpuTicks?.let { ticks -> previous?.sample?.cpuTicks?.let { ticks.usage(since = it) } }
            return HostStats(sample, usage ?: previous?.cpuUsage)
        }

        fun uptimeText(seconds: Double): String {
            val minutes = seconds.toLong() / 60
            val days = minutes / 1440
            val hours = minutes / 60 % 24
            val rest = minutes % 60
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${rest}m"
                else -> "${rest}m"
            }
        }

        /** "37% · 10.0 GB / 24.0 GB", or "12% · 1.2 GB in use" without a memory total. */
        fun gpuText(gpu: GpuSample): String = listOfNotNull(
            gpu.utilization?.let(::percentText) ?: "…",
            when {
                gpu.memoryUsed != null && gpu.memoryTotal != null -> "${bytesText(gpu.memoryUsed)} / ${bytesText(gpu.memoryTotal)}"
                gpu.memoryUsed != null -> "${bytesText(gpu.memoryUsed)} in use"
                else -> null
            },
        ).joinToString(" · ")

        /** Several GPUs on one line, "4 GPUs · avg 37%", so the card stays short. */
        fun gpusText(gpus: List<GpuSample>): String {
            val usage = gpus.mapNotNull { it.utilization }.takeIf { it.isNotEmpty() }?.average()
            return "${gpus.size} GPUs · " + (usage?.let { "avg ${percentText(it)}" } ?: "…")
        }

        private fun percentText(fraction: Double) = String.format(Locale.US, "%.0f%%", fraction.coerceIn(0.0, 1.0) * 100)

        fun bytesText(bytes: Long): String {
            val gib = bytes / 1_073_741_824.0
            return when {
                gib >= 100 -> String.format(Locale.US, "%.0f GB", gib)
                gib >= 1 -> String.format(Locale.US, "%.1f GB", gib)
                else -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
            }
        }
    }
}

/** Reads a host's CPU, memory, disk, uptime and GPUs with one read-only shell script (wooloo's `HostProbe`). */
object HostProbe {
    /**
     * A POSIX sh script printing `key=value` lines, for Linux (`/proc`) and macOS (`sysctl`,
     * `vm_stat`). Disk space is that of the volume holding [directory] (home when null).
     * GPUs are best effort and need no privileges: `nvidia-smi` (bounded to 2 s, killed after 3 s;
     * it can be slow on an idle GPU), AMD's `amdgpu` sysfs files, and macOS's `IOAccelerator`
     * statistics. A missing tool or file prints nothing. GPU lines come last: an `nvidia-smi` stuck
     * in the driver can't be killed and keeps the output open, so the caller parses what arrived
     * (see `HerdrConnection.sampleHost`) and the next probe skips `nvidia-smi` while it lingers.
     */
    fun command(directory: String? = null): String {
        val dir = directory?.let { RemoteCommand.shellQuote(it) } ?: "\"${'$'}HOME\""
        val script = """
            LC_ALL=C; export LC_ALL
            echo "host=$(hostname)"
            if [ -r /proc/stat ]; then
              echo "stat=$(head -n 1 /proc/stat)"
              echo "load=$(cut -d ' ' -f 1-3 /proc/loadavg)"
              echo "ncpu=$(getconf _NPROCESSORS_ONLN)"
              awk '/^(MemTotal|MemAvailable):/ { sub(":", "", $1); print $1 "=" $2 }' /proc/meminfo
              echo "uptime=$(cut -d ' ' -f 1 /proc/uptime)"
            else
              echo "load=$(sysctl -n vm.loadavg | tr -d '{}')"
              echo "ncpu=$(sysctl -n hw.ncpu)"
              echo "memsize=$(sysctl -n hw.memsize)"
              echo "boottime=$(sysctl -n kern.boottime | awk '{ gsub(",", "", $4); print $4 }')"
              echo "now=$(date +%s)"
              echo "pscpu=$(ps -A -o %cpu= | awk '{s+=$1} END {print s+0}')"
              vm_stat | awk '/page size of/ { print "pagesize=" $8 }
                /^Pages (active|wired down|occupied by compressor):/ { k = $0; sub(/^Pages /, "", k); sub(/:.*/, "", k); n = ${'$'}NF; sub(/\.$/, "", n); print k "=" n }'
            fi
            echo "df=$(df -Pk $dir 2>/dev/null | tail -n 1)"
            if ! command -v nvidia-smi >/dev/null 2>&1; then :
            # Only our own earlier query counts, not the user's `nvidia-smi -l`, `dmon` or `watch`.
            # Anchored at the start: Linux pgrep doesn't exclude its ancestors, and this very
            # script's `sh -c` command line contains the query text too.
            elif command -v pgrep >/dev/null 2>&1 &&
              pgrep -u "$(id -u)" -f -- '^nvidia-smi --query-gpu=name,utilization[.]gpu,memory[.]used,memory[.]total' >/dev/null 2>&1; then
              : # an earlier probe's query is still stuck (a hung driver): don't pile up another
            else
              q=--query-gpu=name,utilization.gpu,memory.used,memory.total
              # BusyBox before 1.30 only knows `timeout -t SECS`, so probe for -k support first.
              if timeout -k 1 2 true >/dev/null 2>&1; then
                timeout -k 1 2 nvidia-smi "${'$'}q" --format=csv,noheader,nounits 2>/dev/null
              else
                nvidia-smi "${'$'}q" --format=csv,noheader,nounits 2>/dev/null & p=$!
                end=$(( $(date +%s) + 3 ))
                # No unbounded wait: a process stuck in the driver may never exit. A finished
                # but unreaped one still passes kill -0, so also stop at its zombie state. Where
                # `ps -p` is missing (some BusyBox builds) ps prints nothing and the loop stops at
                # once: that gives up the 3 s kill rather than risk waiting on a process that is gone.
                while kill -0 "${'$'}p" 2>/dev/null; do
                  case "$(ps -o stat= -p "${'$'}p" 2>/dev/null)" in Z*|"") break ;; esac
                  if [ "$(date +%s)" -ge "${'$'}end" ]; then kill -9 "${'$'}p" 2>/dev/null; break; fi
                  sleep 0.1 2>/dev/null || sleep 1
                done
              fi | head -n 16 | sed 's/^/nvidia=/'
            fi
            for d in /sys/class/drm/card[0-9]/device /sys/class/drm/card[0-9][0-9]/device; do
              [ -r "${'$'}d/gpu_busy_percent" ] || continue
              echo "amdgpu=$(cat "${'$'}d/gpu_busy_percent" 2>/dev/null || echo -) $(cat "${'$'}d/mem_info_vram_used" 2>/dev/null || echo -) $(cat "${'$'}d/mem_info_vram_total" 2>/dev/null || echo -)"
            done
            if [ ! -r /proc/stat ] && command -v ioreg >/dev/null 2>&1; then
              ioreg -r -d 1 -w 0 -c IOAccelerator 2>/dev/null |
                grep -E '[+]-o |"model" = |"PerformanceStatistics" = ' | head -n 48 | sed 's/^/ioreg=/'
            fi
            :
        """.trimIndent()
        return "/bin/sh -c " + RemoteCommand.shellQuote(script)
    }

    fun parse(output: String): HostSample? {
        val lines = output.lineSequence().mapNotNull { line ->
            val equals = line.indexOf('=').takeIf { it >= 0 } ?: return@mapNotNull null
            line.substring(0, equals) to line.substring(equals + 1).trim()
        }.toList()
        val values = lines.toMap()
        // macOS reports its Bonjour name; the .local suffix adds nothing on screen.
        val host = values["host"]?.removeSuffix(".local")?.takeIf { it.isNotEmpty() } ?: return null
        val cpuCount = values["ncpu"]?.toIntOrNull()?.takeIf { it > 0 }
        val load = values["load"].orEmpty().split(' ').filter { it.isNotEmpty() }.take(3).mapNotNull { it.nonNegativeDouble() }
        var sample = HostSample(hostname = host, cpuCount = cpuCount, loadAverage = load)

        val stat = values["stat"]
        if (stat != null) {
            // cpu user nice system idle iowait irq softirq steal …; idle and iowait are not busy.
            val ticks = stat.split(' ').filter { it.isNotEmpty() }.drop(1).take(8).map { it.nonNegativeLong() }
            if (ticks.size >= 4 && ticks.all { it != null }) {
                val total = checkedSum(ticks.filterNotNull())
                val idle = checkedSum(listOf(ticks[3]!!, ticks.getOrNull(4) ?: 0))
                if (total != null && idle != null) sample = sample.copy(cpuTicks = CpuTicks(busy = total - idle, total = total))
            }
            val total = values["MemTotal"].nonNegativeLong()?.checkedTimes(1024)?.takeIf { it > 0 }
            val available = values["MemAvailable"].nonNegativeLong()?.checkedTimes(1024)
            if (total != null && available != null) {
                sample = sample.copy(memoryTotal = total, memoryUsed = total - minOf(available, total))
            }
            sample = sample.copy(uptimeSeconds = values["uptime"].nonNegativeDouble())
        } else {
            sample = sample.copy(memoryTotal = values["memsize"].nonNegativeLong()?.takeIf { it > 0 })
            values["pagesize"].nonNegativeLong()?.takeIf { it > 0 }?.let { pageSize ->
                val pages = listOf("active", "wired down", "occupied by compressor").mapNotNull { values[it].nonNegativeLong() }
                if (pages.isNotEmpty()) sample = sample.copy(memoryUsed = checkedSum(pages)?.checkedTimes(pageSize))
            }
            val boot = values["boottime"].nonNegativeDouble()
            val now = values["now"].nonNegativeDouble()
            if (boot != null && now != null) sample = sample.copy(uptimeSeconds = maxOf(0.0, now - boot))
            val percent = values["pscpu"].nonNegativeDouble()
            if (percent != null && cpuCount != null && cpuCount > 0) {
                sample = sample.copy(cpuUsage = minOf(1.0, percent / 100 / cpuCount))
            }
        }

        // Filesystem 1024-blocks Used Available Capacity Mounted-on; the name may hold spaces, so count from the end.
        val df = values["df"].orEmpty().split(' ').filter { it.isNotEmpty() }
        if (df.size >= 6) {
            val total = df[df.size - 5].nonNegativeLong()?.checkedTimes(1024)?.takeIf { it > 0 }
            val available = df[df.size - 3].nonNegativeLong()?.checkedTimes(1024)
            if (total != null && available != null) {
                sample = sample.copy(diskTotal = total, diskFree = minOf(available, total))
            }
        }
        return sample.copy(gpus = parseGpus(lines))
    }

    /** GPU lines may repeat, one per GPU: nvidia-smi CSV rows, amdgpu sysfs readings, ioreg accelerators. */
    private fun parseGpus(lines: List<Pair<String, String>>): List<GpuSample> {
        val gpus = mutableListOf<GpuSample>()
        var accelerator: GpuSample? = null
        fun endAccelerator() {
            accelerator?.takeIf { it.utilization != null || it.memoryUsed != null }?.let(gpus::add)
            accelerator = null
        }
        for ((key, value) in lines) when (key) {
            "nvidia" -> nvidiaGpu(value)?.let(gpus::add)
            "amdgpu" -> amdGpu(value)?.let(gpus::add)
            "ioreg" -> if (value.startsWith("+-o ") || " +-o " in value) {
                endAccelerator()
                accelerator = GpuSample()
            } else {
                accelerator = ioregProperty(accelerator ?: GpuSample(), value)
            }
        }
        endAccelerator()
        return gpus.take(MAX_GPUS)
    }

    /** `name, utilization %, memory used MiB, memory total MiB`; "[N/A]" fields stay null. */
    private fun nvidiaGpu(row: String): GpuSample? {
        val fields = row.split(',').map { it.trim() }
        if (fields.size < 4) return null // "No devices were found", driver errors
        val (utilization, used, total) = fields.takeLast(3)
        // The name is free text and may hold commas itself: it is everything before the last three.
        var cut = row.length
        repeat(3) { cut = row.lastIndexOf(',', cut - 1) }
        val gpu = GpuSample(
            name = row.substring(0, cut).trim().ifEmpty { null },
            utilization = utilization.percentFraction(),
            memoryUsed = used.nonNegativeLong()?.checkedTimes(MIB),
            memoryTotal = total.nonNegativeLong()?.checkedTimes(MIB)?.takeIf { it > 0 },
        )
        return gpu.takeIf { it.utilization != null || it.memoryUsed != null || it.memoryTotal != null }
    }

    /** `busy % vram used bytes vram total bytes`, with "-" for a file that couldn't be read. */
    private fun amdGpu(reading: String): GpuSample? {
        val fields = reading.split(' ').filter { it.isNotEmpty() }
        if (fields.size != 3) return null
        val gpu = GpuSample(
            utilization = fields[0].percentFraction(),
            memoryUsed = fields[1].nonNegativeLong(),
            memoryTotal = fields[2].nonNegativeLong()?.takeIf { it > 0 },
        )
        return gpu.takeIf { it.utilization != null || it.memoryUsed != null || it.memoryTotal != null }
    }

    private val ioregModel = Regex(""""model"\s*=\s*<?"([^"]+)"""")
    private val ioregUtilization = Regex(""""Device Utilization %"\s*=\s*(\d+)""")
    private val ioregMemory = Regex(""""In use system memory"\s*=\s*(\d+)""")

    private fun ioregProperty(gpu: GpuSample, line: String): GpuSample = when {
        line.contains("\"PerformanceStatistics\"") -> gpu.copy(
            utilization = ioregUtilization.find(line)?.groupValues?.get(1).percentFraction(),
            memoryUsed = ioregMemory.find(line)?.groupValues?.get(1).nonNegativeLong(),
        )
        else -> ioregModel.find(line)?.let { gpu.copy(name = it.groupValues[1].trim().ifEmpty { null }) } ?: gpu
    }

    private fun String?.percentFraction() = nonNegativeDouble()?.takeIf { it <= 100 }?.div(100)

    private const val MIB = 1_048_576L
    private const val MAX_GPUS = 16

    private fun String?.nonNegativeDouble() = this?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun String?.nonNegativeLong() = this?.toLongOrNull()?.takeIf { it >= 0 }
    private fun Long.checkedTimes(other: Long) = runCatching { Math.multiplyExact(this, other) }.getOrNull()
    private fun checkedSum(values: List<Long>) = runCatching { values.fold(0L, Math::addExact) }.getOrNull()
}
