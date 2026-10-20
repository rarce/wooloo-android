package dev.herdroid.herdr

import dev.herdroid.connection.AgentQuotaReader
import dev.herdroid.connection.QuotaHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class AgentQuotaTest {
    @Test
    fun claudeUsageMapsSessionWeeklyAndScopedLimits() {
        val json = """
            {"five_hour": {"utilization": 40.0, "resets_at": "2026-09-30T21:30:00.226707+00:00"},
             "seven_day": {"utilization": 30, "resets_at": "2026-10-06T13:00:00+00:00"},
             "seven_day_opus": null, "tangelo": {"utilization": 99},
             "limits": [
               {"kind": "weekly_all", "group": "weekly", "percent": 30, "scope": null},
               {"kind": "weekly_scoped", "group": "weekly", "percent": 12.5,
                "resets_at": "2026-10-06T13:00:00+00:00", "scope": {"model": {"id": null, "display_name": "Fable"}}},
               {"kind": "weekly_scoped", "percent": 1, "scope": {"model": {"display_name": "All models"}}}
             ],
             "extra_usage": {"is_enabled": false, "utilization": null}}
        """.trimIndent()
        val windows = AgentQuotaParser.claudeUsage(json)
        assertEquals(listOf("5H", "7D", "FABLE 7D"), windows.map { it.label })
        assertEquals(0.4, windows[0].usedFraction, 0.0001)
        assertEquals(1_790_803_800_226L, windows[0].resetsAt)
        assertEquals(1_791_291_600_000L, windows[1].resetsAt)
        assertEquals(0.125, windows[2].usedFraction, 0.0001)
    }

    @Test
    fun claudeUsageIncludesEnabledExtraUsage() {
        val windows = AgentQuotaParser.claudeUsage("""{"extra_usage": {"is_enabled": true, "utilization": 150}}""")
        assertEquals(listOf("EXTRA"), windows.map { it.label })
        assertEquals(1.0, windows[0].usedFraction, 0.0)
    }

    @Test
    fun claudeCredentialReadsTokenExpiryAndPlan() {
        val json = """{"mcpOAuth": {}, "claudeAiOauth": {"accessToken": "abc", "expiresAt": 1790831706368, "subscriptionType": "max"}}"""
        val credential = AgentQuotaParser.claudeCredential(json)!!
        assertEquals("abc", credential.token)
        assertEquals("max", credential.plan)
        assertEquals(1_790_831_706_368L, credential.expiresAt)
        assertTrue(credential.isExpired(1_790_831_700_000L))
        assertFalse(credential.isExpired(1_790_831_000_000L))
        assertFalse("never logs the token", credential.toString().contains("abc"))
        assertNull(AgentQuotaParser.claudeCredential("""{"mcpOAuth": {}}"""))
    }

    @Test
    fun codexUsageLabelsWindowsByTheirLength() {
        val json = """
            {"plan_type": "plus",
             "rate_limit": {"primary_window": {"used_percent": 15, "limit_window_seconds": 18000, "reset_at": 1735401600},
                            "secondary_window": {"used_percent": 5, "limit_window_seconds": 604800, "reset_at": 1735920000}},
             "additional_rate_limits": [{"limit_name": "GPT-5.3-Codex-Spark",
                "rate_limit": {"primary_window": {"used_percent": 50, "limit_window_seconds": 18000}, "secondary_window": null}}]}
        """.trimIndent()
        val (windows, plan) = AgentQuotaParser.codexUsage(json)
        assertEquals("plus", plan)
        assertEquals(listOf("5H", "7D", "SPARK 5H"), windows.map { it.label })
        assertEquals(1_735_920_000_000L, windows[1].resetsAt)
        assertEquals(0.5, windows[2].usedFraction, 0.0)
        val weekly = """{"rate_limit": {"primary_window": {"used_percent": 74, "limit_window_seconds": 604800}, "secondary_window": null}}"""
        assertEquals(listOf("7D"), AgentQuotaParser.codexUsage(weekly).first.map { it.label })
    }

    @Test
    fun codexLogLineReadsRateLimitsInMinutes() {
        val line = """{"timestamp":"2026-09-30T20:07:22.462Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":74.0,"window_minutes":10080,"resets_at":1791181422},"secondary":null,"plan_type":"prolite"}}}"""
        val quota = AgentQuotaParser.codexLogLine(line)!!
        assertTrue(quota.fromLog)
        assertEquals("prolite", quota.plan)
        assertEquals(listOf("7D"), quota.windows.map { it.label })
        assertEquals(1_790_798_842_462L, quota.capturedAt)
        assertNull(AgentQuotaParser.codexLogLine("""{"payload":{"rate_limits":null}}"""))
        assertNull(AgentQuotaParser.codexLogLine("""{"payload":{"type":"message"}}"""))
    }

    @Test
    fun jwtExpiry() {
        fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        assertEquals(1_790_831_706_000L, AgentQuotaParser.jwtExpiry("${b64("{}")}.${b64("""{"exp":1790831706,"sub":"x"}""")}.sig"))
        assertNull(AgentQuotaParser.jwtExpiry("opaque-token"))
        assertNull(AgentQuotaParser.jwtExpiry("a.${b64("""{"exp":true}""")}.c"))
    }

    @Test
    fun windowsStartAgainAfterTheirReset() {
        val window = QuotaWindow("w", "5H", 0.4, resetsAt = 1_000)
        assertEquals(0.4, window.usedFraction(999), 0.0)
        assertEquals(0.0, window.usedFraction(1_000), 0.0)
    }

    @Test
    fun probeParsesAFailedKeychainRead() {
        val files = AgentQuotaProbe.parse("claude=1\nclaude_keychain=failed\ncodex=1\n")
        assertTrue(files.claudeInstalled)
        assertTrue(files.claudeKeychainFailed)
        assertNull(files.claudeCredentials)
        assertTrue(files.codexInstalled)
        assertNull(files.codexAuth)
    }

    /** A home with both agents signed in (fake tokens) and two days of Codex rollouts. */
    private fun makeHome(): File {
        val home = Files.createTempDirectory("herdroid-quota").toFile()
        fun write(path: String, text: String) = File(home, path).apply { parentFile!!.mkdirs() }.writeText(text)
        fun rollout(day: String, used: Int) =
            """{"timestamp":"2026-09-${day}T10:00:00Z","payload":{"rate_limits":{"primary":{"used_percent":$used,"window_minutes":300}}}}""" +
                "\n" + """{"payload":{"type":"token_count","rate_limits":null}}""" + "\n"
        write(".claude/.credentials.json", "{\n  \"claudeAiOauth\": {\"accessToken\": \"claude-token\"}\n}\n")
        write(".codex/auth.json", """{"tokens": {"access_token": "codex-token", "account_id": "acct"}}""")
        write(".codex/sessions/2026/09/29/rollout-a.jsonl", rollout("29", 10))
        write(".codex/sessions/2026/09/30/rollout-b.jsonl", rollout("30", 20))
        File(home, ".codex/sessions/2026/10/01").mkdirs()
        return home
    }

    private fun runProbe(home: File, keychain: Boolean = false): AgentQuotaFiles {
        val process = ProcessBuilder("/bin/sh", "-c", AgentQuotaProbe.command(keychain)).apply {
            environment()["HOME"] = home.path
            environment().remove("CODEX_HOME")
        }.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor())
        return AgentQuotaProbe.parse(output)
    }

    @Test
    fun probeReadsCredentialsAndTheNewestRollout() {
        val home = makeHome()
        try {
            val files = runProbe(home)
            assertTrue(files.claudeInstalled)
            assertEquals("claude-token", files.claudeCredentials?.let(AgentQuotaParser::claudeCredential)?.token)
            assertFalse(files.claudeKeychainFailed)
            assertTrue(files.codexInstalled)
            val codex = files.codexAuth?.let(AgentQuotaParser::codexCredential)!!
            assertEquals("codex-token", codex.token)
            assertEquals("acct", codex.accountId)
            val logged = files.codexLogLine?.let(AgentQuotaParser::codexLogLine)!!
            assertEquals("5H", logged.windows.first().label)
            assertEquals(0.2, logged.windows.first().usedFraction, 0.0)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun probeWithoutAgents() {
        val home = Files.createTempDirectory("herdroid-quota").toFile()
        try {
            assertEquals(AgentQuotaFiles(), runProbe(home, keychain = true))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun readerFetchesClaudeAndFallsBackToCodexLogs() = runBlocking {
        val home = makeHome()
        val files = try { runProbe(home) } finally { home.deleteRecursively() }
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val reader = AgentQuotaReader(readFiles = { files }, http = QuotaHttp { url, headers ->
            requests += url to headers
            if (url.contains("anthropic")) 200 to """{"five_hour": {"utilization": 40}}""" else 503 to ""
        })
        val results = reader.read(now = 1_790_000_000_000L)
        val claude = results[AgentProvider.CLAUDE]!!.getOrThrow()!!
        assertEquals(listOf("5H"), claude.windows.map { it.label })
        assertEquals("Bearer claude-token", requests.first { it.first.contains("anthropic") }.second["Authorization"])
        val codex = results[AgentProvider.CODEX]!!.getOrThrow()!!
        assertTrue("the endpoint failed, so the log is used", codex.fromLog)
        assertEquals("acct", requests.first { it.first.contains("chatgpt") }.second["ChatGPT-Account-Id"])
    }

    @Test
    fun readerNeverExposesHttpExceptionCredentials() = runBlocking {
        val token = "private-token-fixture"
        val files = AgentQuotaFiles(claudeInstalled = true,
            claudeCredentials = """{"claudeAiOauth":{"accessToken":"$token"}}""")
        val reader = AgentQuotaReader(readFiles = { files }, http = QuotaHttp { _, _ ->
            throw IllegalArgumentException("Invalid Authorization: Bearer $token")
        })
        val failure = reader.read(0)[AgentProvider.CLAUDE]!!.exceptionOrNull()!!
        assertEquals("Usage request failed. Check connectivity and try again later.", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(token))
    }

    @Test
    fun forbiddenAndRateLimitedBodiesAreNotExposed() = runBlocking {
        for (status in listOf(403, 429)) {
            val files = AgentQuotaFiles(claudeInstalled = true,
                claudeCredentials = """{"claudeAiOauth":{"accessToken":"token"}}""")
            val reader = AgentQuotaReader(readFiles = { files }, http = QuotaHttp { _, _ ->
                status to "sensitive provider response"
            })
            val message = reader.read(0)[AgentProvider.CLAUDE]!!.exceptionOrNull()!!.message!!
            assertFalse(message.contains("sensitive"))
            assertTrue(message.contains(if (status == 403) "rejected" else "rate limited"))
        }
    }

    @Test
    fun readerStopsSendingARejectedClaudeToken() = runBlocking {
        val files = AgentQuotaFiles(claudeInstalled = true, claudeCredentials = """{"claudeAiOauth": {"accessToken": "t"}}""")
        var calls = 0
        val reader = AgentQuotaReader(readFiles = { files }, http = QuotaHttp { _, _ -> calls++; 401 to "" })
        val first = reader.read(now = 0)[AgentProvider.CLAUDE]!!
        assertTrue(first.exceptionOrNull()!!.message!!.contains("rejected"))
        val second = reader.read(now = 1)[AgentProvider.CLAUDE]!!
        assertTrue(second.isFailure)
        assertEquals(1, calls)
        assertNull("Codex isn't set up", reader.read(now = 2)[AgentProvider.CODEX]!!.getOrThrow())
    }

    @Test
    fun keychainFailureIsStillExplainedWhileTheKeychainIsSkipped() = runBlocking {
        val keychainAsked = mutableListOf<Boolean>()
        val reader = AgentQuotaReader(readFiles = { keychain ->
            keychainAsked += keychain
            AgentQuotaFiles(claudeInstalled = true, claudeKeychainFailed = keychain)
        }, http = QuotaHttp { _, _ -> error("no request without a sign-in") })
        val first = reader.read(now = 0)[AgentProvider.CLAUDE]!!.exceptionOrNull()!!.message!!
        val second = reader.read(now = 120_000)[AgentProvider.CLAUDE]!!.exceptionOrNull()!!.message!!
        assertEquals(listOf(true, false), keychainAsked)
        assertTrue(first, first.contains("Keychain"))
        assertTrue(second, second.contains("Keychain"))
    }
}
