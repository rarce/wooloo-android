package dev.herdroid.e2e

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import dev.herdroid.data.OpenSshEd25519
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.TerminalEvent
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.ssh.HostKeyChallenge
import dev.herdroid.ssh.SshCredential
import dev.herdroid.ssh.SshSession
import dev.herdroid.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.security.Security

/**
 * Talks to a real herdr server over real SSH. Skipped unless HERDROID_E2E_HOST is set:
 *
 *   HERDROID_E2E_HOST=127.0.0.1 HERDROID_E2E_PORT=2222 HERDROID_E2E_USER=herd \
 *   HERDROID_E2E_AUTHORIZED_KEYS=/home/herd/.ssh/authorized_keys ./gradlew testDebugUnitTest
 *
 * The test generates its own key with the app's encoder and appends it to the given
 * authorized_keys file, so it also proves in-app key generation works with OpenSSH.
 */
class HerdrEndToEndTest {
    private val host = System.getenv("HERDROID_E2E_HOST")
    private val port = System.getenv("HERDROID_E2E_PORT")?.toInt() ?: 22
    private val user = System.getenv("HERDROID_E2E_USER") ?: System.getProperty("user.name")
    private val authorizedKeys = System.getenv("HERDROID_E2E_AUTHORIZED_KEYS")
    private val session = System.getenv("HERDROID_E2E_SESSION") ?: "default"

    @Before
    fun setUp() {
        assumeTrue("set HERDROID_E2E_HOST to run", host != null && authorizedKeys != null)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun generatedKeyProvider(): OpenSSHKeyV1KeyFile {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val pair = gen.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        val pem = OpenSshEd25519.encodePrivateKey(priv, pub, "herdroid-e2e")
        val line = "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(OpenSshEd25519.publicBlob(pub)) + " herdroid-e2e\n"
        File(authorizedKeys!!).appendText(line)
        return OpenSSHKeyV1KeyFile().apply { init(pem, null) }
    }

    @Test
    fun changedHostKeyIsRejectedBeforeCredentialsAreUsed(): Unit = runBlocking {
        var credentialUsed = false
        var challenged = false
        val previous = "SHA256:deliberately-different-fixture"
        val ssh = SshSession(host!!, port, user, knownFingerprint = { _, _ -> previous }) {
            challenged = true
            assertEquals(previous, it.previousFingerprint)
            assertTrue(it.fingerprint != previous)
            false
        }
        try {
            try {
                withTimeout(20_000) {
                    ssh.connect(SshCredential.Key { credentialUsed = true; error("Must not authenticate") })
                }
                org.junit.Assert.fail("Expected host-key rejection")
            } catch (_: dev.herdroid.ssh.HostKeyRejectedException) { }
            assertTrue(challenged)
            org.junit.Assert.assertFalse(credentialUsed)
            org.junit.Assert.assertFalse(ssh.isConnected)
        } finally { ssh.close() }
    }

    @Test
    fun fullRoundTrip(): Unit = runBlocking {
        val challenges = mutableListOf<HostKeyChallenge>()
        val ssh = SshSession(host!!, port, user, knownFingerprint = { _, _ -> null }) {
            challenges += it; true
        }
        val key = generatedKeyProvider()
        withTimeout(20_000) { ssh.connect(SshCredential.Key { key }) }
        assertEquals("host key prompt shown once for an unknown host", 1, challenges.size)
        assertTrue(challenges.single().fingerprint.startsWith("SHA256:"))

        val api = HerdrApi(ssh, session, herdrPath = null)
        val snapshot = api.snapshot()
        println("herdr ${snapshot.version} protocol ${snapshot.protocol}: " +
            snapshot.workspaces.map { it.label } + " panes=" + snapshot.panes.map { it.paneId })
        assertTrue(snapshot.version.isNotBlank())
        val pane = snapshot.panes.first()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Lifecycle events arrive over a long-lived bridge channel.
            val event = scope.async {
                api.subscribe(HerdrApi.LIFECYCLE_EVENTS).first()
            }
            kotlinx.coroutines.delay(800)
            val created = api.createTab(pane.workspaceId, "e2e-tab")
            val envelope = withTimeout(10_000) { event.await() }
            println("event: $envelope")
            assertTrue(envelope.toString().contains("tab"))
            val newTabId = created["tab"].toString().substringAfter("\"tab_id\":\"").substringBefore('"')
            api.closeTab(newTabId)

            // Stream the pane, type into it and watch our emulator render the result.
            val emu = TerminalEmulator()
            val stream = TerminalStream.open(ssh, scope, session, null, pane.paneId, 80, 24, takeover = true, readOnly = false)
            val marker = "herdroid-e2e-${System.nanoTime() % 100000}"
            val sawMarker = scope.async {
                var typed = false
                stream.events.first { ev ->
                    if (ev is TerminalEvent.Frame) {
                        if (ev.width != emu.cols || ev.height != emu.rows) emu.resize(ev.width, ev.height)
                        if (ev.full) emu.reset()
                        emu.feed(ev.bytes)
                        if (!typed) {
                            typed = true
                            stream.input("printf '%s \\345\\255\\227\\n' $marker\r")
                        }
                    }
                    ev is TerminalEvent.Closed || emu.screenText().lines().any { it.startsWith("$marker 字") }
                }
            }
            val last = withTimeout(15_000) { sawMarker.await() }
            println("--- emulator screen ---\n" + emu.screenText() + "\n-----------------------")
            assertTrue("stream closed early: $last", last !is TerminalEvent.Closed)
            assertEquals(80, emu.cols)
            assertEquals(24, emu.rows)
            val row = emu.screenText().lines().indexOfFirst { it.startsWith("$marker 字") }
            val wideCol = marker.length + 1
            assertEquals("字", emu.textAt(wideCol, row))
            assertEquals("", emu.textAt(wideCol + 1, row))

            // A second controller without takeover is refused with a takeover hint.
            val second = TerminalStream.open(ssh, scope, session, null, pane.paneId, 80, 24, takeover = false, readOnly = false)
            val refused = withTimeout(10_000) { second.events.first { it is TerminalEvent.Closed } } as TerminalEvent.Closed
            println("second controller: ${refused.reason}")
            assertTrue(refused.needsTakeover)

            // Read-only observers can watch alongside the controller.
            val observer = TerminalStream.open(ssh, scope, session, null, pane.paneId, 80, 24, takeover = false, readOnly = true)
            val observed = withTimeout(10_000) { observer.events.first() }
            assertTrue(observed is TerminalEvent.Frame)
            observer.close()

            // Resize is honoured by the next frame.
            stream.resize(60, 20)
            stream.input("\r")
            val resized = withTimeout(10_000) {
                stream.events.first { it is TerminalEvent.Closed || it is TerminalEvent.Frame && it.width == 60 }.also {
                    assertTrue("controller closed before resize: $it", it is TerminalEvent.Frame)
                } as TerminalEvent.Frame
            }
            assertEquals(20, resized.height)
            stream.close()

            // Agent prompt path (works on any pane; agent methods need a detected agent, so
            // just verify send_text round-trips through the API bridge).
            api.sendText(pane.paneId, "")
        } finally {
            scope.cancel()
            ssh.close()
        }
    }
}
