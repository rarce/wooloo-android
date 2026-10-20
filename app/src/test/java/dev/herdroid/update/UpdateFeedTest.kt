package dev.herdroid.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateFeedTest {
    private val sha = "a".repeat(64)

    private fun feed(
        versionCode: Long = 1_002_003,
        url: String = "https://github.com/rarce/herdroid/releases/download/v1.2.3/wooloo-mobile-1.2.3.apk",
        sha256: String = sha,
        size: Long = 1234,
    ) = """{"versionCode":$versionCode,"versionName":"1.2.3","url":"$url","sha256":"$sha256","size":$size,
        |"notes":"Fixes","publishedAt":"2026-10-13"}""".trimMargin()

    @Test fun parsesAPublishedReleaseIgnoringUnknownFields() {
        val release = UpdateFeed.parse(feed())
        assertEquals(1_002_003L, release.versionCode)
        assertEquals("1.2.3", release.versionName)
        assertEquals(sha, release.sha256)
        assertEquals(1234L, release.size)
        assertEquals("Fixes", release.notes)
    }

    @Test fun rejectsFeedsThatCouldNotBeInstalledSafely() {
        listOf(
            feed(url = "http://example.com/app.apk"),
            feed(sha256 = "A".repeat(64)),
            feed(sha256 = "abc"),
            feed(versionCode = 0),
            feed(size = 0),
            feed(size = UpdateFeed.MAX_APK_BYTES + 1),
        ).forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { UpdateFeed.parse(text) } }
    }

    @Test fun parsesFeedGeneratedByReleaseMetadataScript() {
        val text = checkNotNull(javaClass.getResource("/update/generated-feed.json")).readText(Charsets.UTF_8)
        val release = UpdateFeed.parse(text)
        assertEquals(1_002_003L, release.versionCode)
        assertEquals("1.2.3", release.versionName)
        assertEquals(3L, release.size)
        assertEquals("Mejoras 🐑\n更新", release.notes)
        assertEquals("https://example.com/app.apk", release.url)
    }

    @Test fun rejectsMalformedJson() {
        assertThrows(IllegalArgumentException::class.java) { UpdateFeed.parse("""{"versionCode":1}""") }
    }
}
