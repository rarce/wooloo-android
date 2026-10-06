package dev.herdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTest {
    @Test
    fun quotesUnsafeArguments() {
        assertEquals("default", RemoteCommand.shellQuote("default"))
        assertEquals("w1:p1", RemoteCommand.shellQuote("w1:p1"))
        assertEquals("'it'\\''s'", RemoteCommand.shellQuote("it's"))
        assertEquals("'a b'", RemoteCommand.shellQuote("a b"))
    }

    @Test
    fun bridgeCommandSelectsSessionAndRunsUnderSh() {
        val cmd = RemoteCommand.apiBridge("agents", null)
        assertTrue(cmd.startsWith("/bin/sh -c '"))
        assertTrue(cmd.contains("--session agents remote-api-bridge"))
        assertTrue(cmd.contains("\$HOME/.local/bin/herdr"))
    }

    @Test
    fun terminalControlIncludesSizeAndTakeover() {
        val cmd = RemoteCommand.terminalControl("default", "~/bin/herdr", "w2:p3", 80, 24, takeover = true)
        assertTrue(cmd.contains("terminal session control w2:p3 --cols 80 --rows 24 --takeover"))
        assertTrue(cmd.contains("\$HOME/bin/herdr"))
    }
}
