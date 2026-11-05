package com.samosaai.masala.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * What `update.json` on the repository's main branch says about the newest release. The release
 * workflow writes it after publishing the APK; release builds read it to offer the update.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    /** Lower-case hex SHA-256 of the published APK; the download must match it. */
    val sha256: String,
    val releaseNotes: String = "",
)

/** The pure half of the in-app updater: parsing and the trust and notification decisions. */
object AppUpdates {
    const val MANIFEST_URL = "https://raw.githubusercontent.com/samosa-ai-com/Masala-AI-Launcher/main/update.json"

    private const val TRUSTED_HOST = "github.com"
    private const val TRUSTED_PATH_PREFIX = "/samosa-ai-com/Masala-AI-Launcher/releases/download/"

    /** How often the background check runs. */
    const val CHECK_INTERVAL_HOURS = 12L

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Hex = Regex("^[0-9a-f]{64}$")

    /** Parses `update.json`; fails on a missing field, a non-positive version code or a malformed sha256. */
    fun parse(text: String): UpdateManifest {
        val m = json.decodeFromString(UpdateManifest.serializer(), text)
        val sha = m.sha256.trim().lowercase()
        require(m.versionCode > 0) { "update.json has no valid versionCode" }
        require(m.versionName.isNotBlank()) { "update.json has no versionName" }
        require(sha256Hex.matches(sha)) { "update.json has no valid sha256" }
        return m.copy(sha256 = sha)
    }

    /**
     * Only an https link to this repository's release downloads. Redirects from there to GitHub's
     * asset storage are followed by the HTTP client and are not under the manifest's control.
     */
    fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(TRUSTED_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            (uri.path ?: "").startsWith(TRUSTED_PATH_PREFIX) &&
            !(uri.path ?: "").contains("/../")
    }.getOrDefault(false)

    fun isNewer(installedVersionCode: Int, manifest: UpdateManifest): Boolean =
        manifest.versionCode > installedVersionCode

    /** A version gets one notification: newer than what's installed and than the last one announced. */
    fun shouldNotify(installedVersionCode: Int, manifest: UpdateManifest, lastNotifiedVersionCode: Int): Boolean =
        isNewer(installedVersionCode, manifest) &&
            manifest.versionCode > lastNotifiedVersionCode &&
            isTrustedDownloadUrl(manifest.downloadUrl)
}
