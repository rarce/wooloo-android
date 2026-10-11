package dev.herdroid.herdr

import java.util.Locale

/** Cumulative CPU time of a host, in ticks. Usage is the busy share of the ticks between two samples. */
data class CpuTicks(val busy: Long, val total: Long) {
    fun usage(since: CpuTicks): Double? {
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
)

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

/** Reads a host's CPU, memory, disk and uptime with one read-only shell script (wooloo's `HostProbe`). */
object HostProbe {
    /**
     * A POSIX sh script printing `key=value` lines, for Linux (`/proc`) and macOS (`sysctl`,
     * `vm_stat`). Disk space is that of the volume holding [directory] (home when null).
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
        """.trimIndent()
        return "/bin/sh -c " + RemoteCommand.shellQuote(script)
    }

    fun parse(output: String): HostSample? {
        val values = output.lineSequence().mapNotNull { line ->
            val equals = line.indexOf('=').takeIf { it >= 0 } ?: return@mapNotNull null
            line.substring(0, equals) to line.substring(equals + 1).trim()
        }.toMap()
        // macOS reports its Bonjour name; the .local suffix adds nothing on screen.
        val host = values["host"]?.removeSuffix(".local")?.takeIf { it.isNotEmpty() } ?: return null
        val cpuCount = values["ncpu"]?.toIntOrNull()
        val load = values["load"].orEmpty().split(' ').filter { it.isNotEmpty() }.take(3).mapNotNull { it.toDoubleOrNull() }
        var sample = HostSample(hostname = host, cpuCount = cpuCount, loadAverage = load)

        val stat = values["stat"]
        if (stat != null) {
            // cpu user nice system idle iowait irq softirq steal …; idle and iowait are not busy.
            val ticks = stat.split(' ').filter { it.isNotEmpty() }.drop(1).mapNotNull { it.toLongOrNull() }
            if (ticks.size >= 4) {
                val total = ticks.take(8).sum()
                val idle = ticks[3] + (ticks.getOrNull(4) ?: 0)
                sample = sample.copy(cpuTicks = CpuTicks(busy = total - idle, total = total))
            }
            val total = values["MemTotal"]?.toLongOrNull()?.times(1024)
            val available = values["MemAvailable"]?.toLongOrNull()?.times(1024)
            if (total != null && available != null) {
                sample = sample.copy(memoryTotal = total, memoryUsed = total - minOf(available, total))
            }
            sample = sample.copy(uptimeSeconds = values["uptime"]?.toDoubleOrNull())
        } else {
            sample = sample.copy(memoryTotal = values["memsize"]?.toLongOrNull())
            values["pagesize"]?.toLongOrNull()?.let { pageSize ->
                val pages = listOf("active", "wired down", "occupied by compressor").mapNotNull { values[it]?.toLongOrNull() }
                if (pages.isNotEmpty()) sample = sample.copy(memoryUsed = pages.sum() * pageSize)
            }
            val boot = values["boottime"]?.toDoubleOrNull()
            val now = values["now"]?.toDoubleOrNull()
            if (boot != null && now != null) sample = sample.copy(uptimeSeconds = maxOf(0.0, now - boot))
            val percent = values["pscpu"]?.toDoubleOrNull()
            if (percent != null && cpuCount != null && cpuCount > 0) {
                sample = sample.copy(cpuUsage = minOf(1.0, percent / 100 / cpuCount))
            }
        }

        // Filesystem 1024-blocks Used Available Capacity Mounted-on; the name may hold spaces, so count from the end.
        val df = values["df"].orEmpty().split(' ').filter { it.isNotEmpty() }
        if (df.size >= 6) {
            val total = df[df.size - 5].toLongOrNull()
            val available = df[df.size - 3].toLongOrNull()
            if (total != null && available != null) {
                sample = sample.copy(diskTotal = total * 1024, diskFree = available * 1024)
            }
        }
        return sample
    }
}
