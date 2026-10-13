package dev.herdroid.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One published release, as described by `update.json` (an asset of the latest GitHub release,
 * written by `.github/workflows/release.yml`). The APK must match [sha256] and [size], and Android
 * only installs it over this app when it is signed with the same key.
 */
@Serializable
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val notes: String = "",
)

object UpdateFeed {
    const val MAX_FEED_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Hex = Regex("[0-9a-f]{64}")

    /** Parses and validates a feed; throws [IllegalArgumentException] (or a serialization error) otherwise. */
    fun parse(text: String): UpdateRelease {
        val release = json.decodeFromString(UpdateRelease.serializer(), text)
        require(release.versionCode > 0) { "Update feed has no version code" }
        require(release.versionName.isNotBlank()) { "Update feed has no version name" }
        require(release.url.startsWith("https://")) { "Update download must use HTTPS" }
        require(sha256Hex.matches(release.sha256)) { "Update feed has no valid SHA-256" }
        require(release.size in 1..MAX_APK_BYTES) { "Update size is out of range" }
        return release
    }
}
