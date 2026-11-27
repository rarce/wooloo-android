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
        assertEquals(PromptOptions(), controller.promptOptions)
        // "❯ 1. Yes\r\n  2. No"
        connection.opened.last().record("""{"type":"terminal.frame","width":80,"height":24,"full":true,"bytes":"4p2vIDEuIFllcw0KICAyLiBObw=="}""")
        await { controller.promptOptions.options.isNotEmpty() }
        assertEquals(listOf(1, 2), controller.promptOptions.options.map { it.number })
        assertEquals(1, controller.promptOptions.options.single { it.focused }.number)
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

    @Test(timeout = 10_000) fun watchersOpenAtThePaneSizeAndFollowItsChanges(): Unit = runBlocking {
        controller.setPaneSize(147, 50)
        startLive(observe = true)
        assertEquals(147 to 50, connection.sizes.last())
        // A wider desktop pane reopens the watcher at its new size, without the loading state.
        controller.setPaneSize(180, 50)
        assertEquals(PaneState.Live, controller.state)
        await { connection.opened.size == 2 }
        assertEquals(180 to 50, connection.sizes.last())
        assertTrue(connection.observers.last())
        assertFalse(connection.takeovers.last())
        // Bursts settle into one reopen; an unchanged size reopens nothing.
        controller.setPaneSize(150, 50)
        controller.setPaneSize(180, 50)
        delay(400)
        assertEquals(2, connection.opened.size)
    }

    @Test(timeout = 10_000) fun aPaneSizeThatArrivesWhileOpeningIsAppliedOnceLive(): Unit = runBlocking {
        controller.setObserveOnly(true)
        controller.onGridSize(80, 24, 8, 16)
        await { connection.opened.size == 1 }
        assertEquals(80 to 24, connection.sizes.last())
        assertEquals(PaneState.Opening, controller.state)
        controller.setPaneSize(147, 50)
        delay(300)
        assertEquals(1, connection.opened.size)
        connection.opened.last().frame()
        await { connection.opened.size == 2 }
        assertEquals(147 to 50, connection.sizes.last())
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
        delay(400)
        assertEquals("Exactly one reopen", 2, connection.opened.size)
    }

    @Test(timeout = 10_000) fun aPaneSizeThatArrivesDuringTheFirstOpenDebounceIsUsedDirectly(): Unit = runBlocking {
        controller.setObserveOnly(true)
        controller.onGridSize(80, 24, 8, 16)
        controller.setPaneSize(147, 50)
        await { connection.opened.size == 1 }
        assertEquals(147 to 50, connection.sizes.last())
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
        delay(400)
        assertEquals(1, connection.opened.size)
    }

    @Test(timeout = 10_000) fun theMenusTakeControlTakesOver(): Unit = runBlocking {
        startLive(observe = true)
        controller.toggleWatching()
        await { connection.opened.size == 2 }
        assertTrue(connection.takeovers.last())
        assertFalse(controller.readOnly)
        connection.opened.last().frame()
        await { controller.state == PaneState.Live }
        controller.toggleWatching()
        await { connection.opened.size == 3 }
        assertTrue(connection.observers.last())
        assertFalse(connection.takeovers.last())
    }

    @Test(timeout = 10_000) fun controllersKeepSizingThePaneToTheView(): Unit = runBlocking {
        controller.setPaneSize(147, 50)
        startLive()
        assertEquals(80 to 24, connection.sizes.last())
        controller.setPaneSize(180, 50)
        delay(400)
        assertEquals(1, connection.opened.size)
    }

    @Test fun observeSizeUsesThePaneWithinHerdrsLimits() {
        assertEquals(80 to 24, observeSize(80, 24, null))
        assertEquals(147 to 50, observeSize(80, 24, 147 to 50))
        assertEquals(80 to 30, observeSize(80, 30, 40 to 10))
        assertEquals(512 to 128, observeSize(80, 24, 900 to 300))
        assertEquals(300 to 218, observeSize(80, 24, 300 to 300))
        assertTrue(isOversizeFrame(147, 24, 80, 24))
        assertTrue("taller only", isOversizeFrame(80, 50, 80, 24))
        assertFalse(isOversizeFrame(80, 24, 80, 24))
        assertFalse(isOversizeFrame(147, 50, 0, 0))
    }

    @Test fun onlyAViewThatLostRowsKeepsTheWatchersStream() {
        assertFalse(needsObserveReopen(80 to 24, 80 to 24, paneChanged = false))
        assertFalse("keyboard opened", needsObserveReopen(80 to 24, 80 to 14, paneChanged = false))
        assertTrue("pane shrank", needsObserveReopen(80 to 50, 80 to 40, paneChanged = true))
        assertTrue("view grew", needsObserveReopen(80 to 14, 80 to 24, paneChanged = false))
        assertTrue("narrower", needsObserveReopen(80 to 24, 60 to 24, paneChanged = false))
    }

    @Test(timeout = 10_000) fun aWatcherKeepsItsStreamWhenTheKeyboardShrinksTheView(): Unit = runBlocking {
        startLive(observe = true)
        controller.onGridSize(80, 14, 8, 16)
        delay(400)
        assertEquals(1, connection.opened.size)
        controller.onGridSize(80, 30, 8, 16)
        await { connection.opened.size == 2 }
        assertEquals(80 to 30, connection.sizes.last())
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
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
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
            sizes += cols to rows
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
