package dev.herdroid.ui.terminal

import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.FailureKind
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.ssh.CommandChannel
import dev.herdroid.ui.components.bannerOffersRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** #24: switching panes must never cost the host connection, and recovery has one owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaneSwitchTest {
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun overlayOffersActionsOnlyWhileTheBannerHasNone() {
        val statuses = listOf(
            ConnectionStatus.Idle,
            ConnectionStatus.Connecting("Opening SSH…"),
            ConnectionStatus.Connected,
            ConnectionStatus.Reconnecting(1, "Connection lost", 0),
            ConnectionStatus.Failed(FailureKind.NETWORK, "Can't reach the machine"),
        )
        val states = listOf(
            PaneState.WaitingForLayout, PaneState.Opening, PaneState.Live, PaneState.Offline,
            PaneState.Busy("use --takeover"), PaneState.Busy("use --takeover", afterReconnect = true),
            PaneState.TakenOver, PaneState.Closed("pane exited"), PaneState.Closed(null, interrupted = true),
        )
        for (status in statuses) for (state in states) {
            val recovery = recoveryUi(status, state)
            val label = "$status × $state → $recovery"
            assertFalse("Two actionable surfaces: $label", status.bannerOffersRecovery && recovery is PaneRecovery.Actions)
            if (status.bannerOffersRecovery && state != PaneState.Live) {
                assertTrue("The pane waits for the host: $label", recovery is PaneRecovery.WaitingForHost)
            }
            if (status == ConnectionStatus.Connected &&
                (state is PaneState.Busy || state == PaneState.TakenOver || state is PaneState.Closed)) {
                assertEquals("Pane problems keep their actions: $label", PaneRecovery.Actions(state), recovery)
            }
            if (state == PaneState.Live) assertEquals(PaneRecovery.None, recovery)
        }
    }

    @Test(timeout = 20_000) fun switchStormWithSlowClosesKeepsTheHostConnected(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val host = FakeHost(openDelayMs = 60)
        var current: TerminalController? = null
        repeat(30) { i ->
            current?.dispose()
            val controller = TerminalController(host, "pane$i").also { current = it }
            controller.onGridSize(80, 24, 8, 16)
            // Before the open debounce, mid-open, and after the stream went live.
            delay(longArrayOf(0, 40, 180, 260)[i % 4])
            host.opened.lastOrNull()?.frame()
        }
        val last = current!!
        await { host.opened.lastOrNull()?.frame(); last.state == PaneState.Live }
        assertEquals(ConnectionStatus.Connected, host.status.value)
        assertEquals("No pane switch may abort the shared transport", 0, host.aborts.get())
        assertTrue("Debounced switches open no channel", host.opened.size < 30)
        await(5_000) { host.opened.dropLast(1).none { it.isOpen } }
        last.dispose()
    }

    @Test(timeout = 10_000) fun conflatedReconnectStillReopensAnInterruptedPane(): Unit = runBlocking {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val host = FakeHost(openDelayMs = 0)
        val controller = TerminalController(host, "pane")
        try {
            scheduler.runCurrent()
            controller.onGridSize(80, 24, 8, 16)
            pump(scheduler) { host.opened.size == 1 }
            host.opened[0].frame()
            pump(scheduler) { controller.state == PaneState.Live }
            // The host drops and is back before the controller's status collector runs, so the
            // collector only ever sees Connected; the dead stream's close is already queued.
            host.status.value = ConnectionStatus.Reconnecting(1, "Connection lost", 0)
            host.opened[0].remote.close()
            Thread.sleep(300)
            host.status.value = ConnectionStatus.Connected
            pump(scheduler) { host.opened.size == 2 }
            host.opened[1].frame()
            pump(scheduler) { controller.state == PaneState.Live }
        } finally {
            controller.dispose()
        }
    }

    private fun pump(scheduler: TestCoroutineScheduler, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not reached" }
            scheduler.advanceTimeBy(50)
            scheduler.runCurrent()
            Thread.sleep(5)
        }
    }

    private suspend fun await(timeoutMs: Long = 3_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }

    /** A host whose channels acknowledge close slowly; aborting any channel's transport drops it. */
    private class FakeHost(private val openDelayMs: Long) : TerminalConnection {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected)
        override val isLive get() = status.value == ConnectionStatus.Connected
        val opened = CopyOnWriteArrayList<SlowChannel>()
        val aborts = AtomicInteger()
        override suspend fun openTerminal(paneId: String, cols: Int, rows: Int, takeover: Boolean,
                                          readOnly: Boolean, scope: CoroutineScope): TerminalStream {
            if (openDelayMs > 0) delay(openDelayMs)
            val channel = SlowChannel { aborts.incrementAndGet(); status.value = ConnectionStatus.Reconnecting(1, "aborted", 0) }
            opened += channel
            return TerminalStream.fromChannel(channel, scope, readOnly)
        }
    }

    private class SlowChannel(private val onAbort: () -> Unit) : CommandChannel {
        override val stdout = PipedInputStream(8192)
        val remote = PipedOutputStream(stdout)
        override val stdin = ByteArrayOutputStream()
        override val stderr = byteArrayOf().inputStream()
        @Volatile override var isOpen = true
        fun frame() = runCatching {
            remote.write("{\"type\":\"terminal.frame\",\"width\":80,\"height\":24,\"bytes\":\"cmVhZHk=\"}\n".toByteArray())
            remote.flush()
        }
        override fun close() {
            Thread.sleep(400) // the peer's close acknowledgement takes longer than the old 250 ms budget
            isOpen = false
            runCatching { remote.close() }
            runCatching { stdout.close() }
        }
        override fun abort() = onAbort()
        override suspend fun isTransportHealthy() = true
    }
}
