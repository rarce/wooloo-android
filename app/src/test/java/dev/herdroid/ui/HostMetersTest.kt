package dev.herdroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.herdroid.herdr.GpuSample
import dev.herdroid.herdr.HostSample
import dev.herdroid.herdr.HostStats
import dev.herdroid.ui.session.HostMeters
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HostMetersTest {
    @get:Rule val compose = createComposeRule()
    private val base = HostSample("box", memoryUsed = 1L shl 30, memoryTotal = 4L shl 30, diskFree = 1L shl 30, diskTotal = 2L shl 30)

    private fun show(sample: HostSample, collapsed: Boolean = false) = compose.setContent {
        WoolooMobileTheme { HostMeters(HostStats(sample, 0.25), collapsed) }
    }

    private fun gpuNodes() = compose.onAllNodesWithContentDescription("GPU", substring = true)

    @Test fun noGpuRowWhenTheHostReportsNone() {
        show(base)
        compose.onNodeWithContentDescription("CPU", substring = true).assertExists()
        gpuNodes().assertCountEquals(0)
    }

    @Test fun collapsedCardHasNoGpuMeterWithoutGpus() {
        show(base, collapsed = true)
        gpuNodes().assertCountEquals(0)
    }

    @Test fun oneMeterPerGpuUpToTwo() {
        show(base.copy(gpus = listOf(GpuSample("Apple M4 Max", 0.95, 10_380_492_800L))))
        compose.onNodeWithContentDescription("GPU 95% · 9.7 GB in use").assertExists()
    }

    @Test fun twoGpusAreNumberedAndMoreShareOneMeter() {
        val gpu = GpuSample("A100", 0.5, 1L shl 30, 80L shl 30)
        show(base.copy(gpus = listOf(gpu, gpu)))
        compose.onNodeWithContentDescription("GPU1 50% · 1.0 GB / 80.0 GB").assertExists()
        compose.onNodeWithContentDescription("GPU2 50% · 1.0 GB / 80.0 GB").assertExists()
    }

    @Test fun manyGpusShareOneMeter() {
        show(base.copy(gpus = List(4) { GpuSample(utilization = 0.25) }))
        compose.onNodeWithContentDescription("GPU 4 GPUs · avg 25%").assertExists()
        gpuNodes().assertCountEquals(1)
    }

    @Test fun collapsedCardAddsAGpuMeter() {
        show(base.copy(gpus = listOf(GpuSample(utilization = 0.4))), collapsed = true)
        compose.onNodeWithContentDescription("GPU 40%").assertExists()
    }
}
