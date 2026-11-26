package dev.herdroid.ui.terminal

import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.herdr.TerminalOpenException
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.ssh.CommandChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRecoveryTest {
    private val connection = FakeConnection()
    private lateinit var controller: TerminalController

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = TerminalController(connection, "pane")
    }
    @After fun tearDown() {
        controller.dispose()
        connection.opened.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test(timeout = 10_000) fun promptOptionsAreReadFromEveryFrameWithoutAToggle(): Unit = runBlocking {
        startLive()
        assertEquals(emptyList<PromptOption>(), controller.promptOptions)
        // "❯ 1. Yes\r\n  2. No"
        connection.opened.last().record("""{"type":"terminal.frame","width":80,"height":24,"full":true,"bytes":"4p2vIDEuIFllcw0KICAyLiBObw=="}""")
        await { controller.promptOptions.isNotEmpty() }
        assertEquals(listOf(1, 2), controller.promptOptions.map { it.number })
        assertEquals(1, controller.promptOptions.single { it.focused }.number)
    }

    @Test(timeout = 10_000) fun channelFailureWhileConnectedRetriesThePaneWithoutStealingControl(): Unit = runBlocking {
        startLive()
        connection.opened.last().remote.close()
        // A pane-level failure: the pane retries by itself and the host is never touched.
        await { connection.opened.size == 2 }
        assertEquals(ConnectionStatus.Connected, connection.status.value)
        assertFalse(connection.takeovers.last())
        connection.opened.last().record("""{"type":"terminal.closed","reason":"use --takeover"}""")
        await { controller.state is PaneState.Busy }
        assertTrue((controller.state as PaneState.Busy).afterReconnect)
        assertEquals(2, connection.opened.size)
        controller.open(takeover = true)
        await { connection.opened.size == 3 }
        assertTrue(connection.takeovers.last())
    }

    @Test(timeout = 15_000) fun refusedOpensRetryThenOfferReconnectOnAConnectedHost(): Unit = runBlocking {
        connection.refuse = 10
        controller.onGridSize(80, 24, 8, 16)
        await(8_000) { controller.state is PaneState.Closed }
        assertEquals(PaneState.Closed("open refused", interrupted = true), controller.state)
        assertEquals(4, connection.attempts)
        assertEquals(PaneRecovery.Actions(controller.state), recoveryUi(connection.status.value, controller.state))
        connection.refuse = 0
        controller.open()
        await { connection.opened.size == 1 }
    }

    @Test(timeout = 10_000) fun sshFailureBeforeChannelCloseRecoversAndKeepsObserveMode(): Unit = runBlocking {
        startLive(observe = true)
        reconnect()
        await { connection.opened.size == 2 }
        assertTrue(connection.observers.last())
        assertFalse(connection.takeovers.last())
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
    }

    @Test(timeout = 10_000) fun closedPaneIsRecheckedAfterSshReconnectWithoutTakingControl(): Unit = runBlocking {
        startLive()
        connection.opened.last().record("""{"type":"terminal.closed","reason":"pane exited"}""")
        await { controller.state == PaneState.Closed("pane exited") }
        // While the host is away the pane only waits; the banner owns recovery.
        connection.status.value = ConnectionStatus.Reconnecting(1, "fixture", 0)
        assertEquals(PaneState.Offline, controller.state)
        connection.status.value = ConnectionStatus.Connected
        await { connection.opened.size == 2 }
        assertFalse(connection.takeovers.last())
        connection.opened.last().record("""{"type":"terminal.closed","reason":"pane exited"}""")
        await { controller.state == PaneState.Closed("pane exited") }
        assertEquals(2, connection.opened.size)
    }

    @Test(timeout = 10_000) fun takenOverPaneIsWatchedUntilTheUserTakesControlBack(): Unit = runBlocking {
        startLive()
        connection.opened.last().record("""{"type":"terminal.closed","reason":"taken over"}""")
        // No modal: the pane reopens as a watcher right away, without taking control back.
        await { connection.opened.size == 2 }
        assertTrue(connection.observers.last())
        assertFalse(connection.takeovers.last())
        assertTrue(controller.readOnly)
        assertTrue(controller.controlMoved)
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }

        controller.takeControl()
        await { connection.opened.size == 3 }
        assertTrue(connection.takeovers.last())
        assertFalse(connection.observers.last())
        assertFalse(controller.readOnly)
        assertFalse(controller.controlMoved)
    }

    @Test(timeout = 10_000) fun takeoverIsNotUndoneBySshRecovery(): Unit = runBlocking {
        startLive()
        connection.opened.last().record("""{"type":"terminal.closed","reason":"taken over"}""")
        await { connection.opened.size == 2 }
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
        connection.status.value = ConnectionStatus.Reconnecting(1, "fixture", 0)
        assertEquals(PaneState.Offline, controller.state)
        connection.status.value = ConnectionStatus.Connected
        // The reopen keeps watching instead of taking the pane back.
        await { connection.opened.size == 3 }
        assertTrue(connection.observers.last())
        assertFalse(connection.takeovers.last())
        assertTrue(controller.controlMoved)
    }

    @Test(timeout = 10_000) fun choosingToWatchIsNotReportedAsMovedControl(): Unit = runBlocking {
        startLive(observe = true)
        assertTrue(controller.readOnly)
        assertFalse(controller.controlMoved)
    }

    @Test(timeout = 15_000) fun streamThatRendersOneFrameAndDiesRunsOutOfRetries(): Unit = runBlocking {
        connection.flapping = true
        controller.onGridSize(80, 24, 8, 16)
        await(10_000) { controller.state is PaneState.Closed }
        assertTrue((controller.state as PaneState.Closed).interrupted)
        assertEquals("The first open and three retries", 4, connection.opened.size)
        assertEquals(PaneRecovery.Actions(controller.state), recoveryUi(connection.status.value, controller.state))
        delay(600)
        assertEquals(4, connection.opened.size)
    }

    private suspend fun startLive(observe: Boolean = false) {
        if (observe) controller.setObserveOnly(true)
        controller.onGridSize(80, 24, 8, 16)
        await { connection.opened.isNotEmpty() }
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
    }
    private suspend fun reconnect() {
        connection.status.value = ConnectionStatus.Reconnecting(1, "fixture", 0)
        await { controller.state == PaneState.Offline }
        connection.status.value = ConnectionStatus.Connected
    }
    private suspend fun await(timeoutMs: Long = 3000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }

    private class FakeConnection : TerminalConnection {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected)
        override val isLive get() = status.value == ConnectionStatus.Connected
        val opened = CopyOnWriteArrayList<Channel>()
        val takeovers = CopyOnWriteArrayList<Boolean>()
        val observers = CopyOnWriteArrayList<Boolean>()
        @Volatile var refuse = 0
        /** Each stream renders one frame, then its channel dies without terminal.closed. */
        @Volatile var flapping = false
        @Volatile var attempts = 0
        override suspend fun openTerminal(paneId: String, cols: Int, rows: Int, takeover: Boolean,
                                          readOnly: Boolean, scope: CoroutineScope): TerminalStream {
            attempts++
            if (refuse-- > 0) throw TerminalOpenException("open refused")
            val channel = Channel()
            takeovers += takeover
            observers += readOnly
            opened += channel
            return TerminalStream.fromChannel(channel, scope, readOnly).also {
                if (flapping) scope.launch(Dispatchers.IO) { channel.frame(); delay(50); channel.remote.close() }
            }
        }
    }
    private class Channel : CommandChannel {
        override val stdout = PipedInputStream(8192)
        val remote = PipedOutputStream(stdout)
        override val stdin = ByteArrayOutputStream()
        override val stderr = byteArrayOf().inputStream()
        @Volatile override var isOpen = true
        fun record(value: String) { remote.write((value + "\n").toByteArray()); remote.flush() }
        fun frame() = record("""{"type":"terminal.frame","width":80,"height":24,"bytes":"cmVhZHk="}""")
        override fun close() { isOpen = false; runCatching { remote.close() }; runCatching { stdout.close() } }
    }
}
