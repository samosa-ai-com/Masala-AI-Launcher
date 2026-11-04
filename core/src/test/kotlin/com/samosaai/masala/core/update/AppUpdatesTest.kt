package com.samosaai.masala.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatesTest {
    private val sha = "48023bcf308e3da869910acfacac57f8c34ebcf9e127d03f57a1084f469e276f"
    private val url = "https://github.com/samosa-ai-com/Masala-AI-Launcher/releases/download/v0.2.0/Masala-0.2.0.apk"

    private fun manifest(code: Int = 2, link: String = url) = UpdateManifest(code, "0.2.0", link, sha, "notes")

    @Test
    fun parsesWhatTheWorkflowWrites() {
        val m = AppUpdates.parse(
            """{"versionCode":2,"versionName":"0.2.0","downloadUrl":"$url","releaseNotes":"### Added\n- x","sha256":"${sha.uppercase()}","extra":1}""",
        )
        assertEquals(2, m.versionCode)
        assertEquals("0.2.0", m.versionName)
        assertEquals(sha, m.sha256, "sha256 is normalised to lower case")
        assertEquals("### Added\n- x", m.releaseNotes)
    }

    @Test
    fun rejectsManifestsThatCannotBeVerified() {
        assertFailsWith<Exception> { AppUpdates.parse("""{"versionCode":2,"versionName":"0.2.0","downloadUrl":"$url"}""") }
        assertFailsWith<IllegalArgumentException> {
            AppUpdates.parse("""{"versionCode":2,"versionName":"0.2.0","downloadUrl":"$url","sha256":"abc"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            AppUpdates.parse("""{"versionCode":0,"versionName":"0.2.0","downloadUrl":"$url","sha256":"$sha"}""")
        }
        assertFailsWith<Exception> { AppUpdates.parse("<html>404</html>") }
    }

    @Test
    fun trustsOnlyThisRepositorysReleaseDownloads() {
        assertTrue(AppUpdates.isTrustedDownloadUrl(url))
        listOf(
            url.replace("https://", "http://"),
            url.replace("github.com", "github.com.evil.example"),
            url.replace("github.com", "evil.example"),
            url.replace("github.com", "github.com:8443"),
            url.replace("https://", "https://user@"),
            url.replace("Masala-AI-Launcher", "Other-Repo"),
            "https://github.com/samosa-ai-com/Masala-AI-Launcher/releases/download/../../../evil/x.apk",
            "https://github.com/samosa-ai-com/Masala-AI-Launcher/archive/main.zip",
            "not a url",
            "",
        ).forEach { assertFalse(AppUpdates.isTrustedDownloadUrl(it), it) }
    }

    @Test
    fun notifiesOncePerNewerVersion() {
        assertTrue(AppUpdates.shouldNotify(installedVersionCode = 1, manifest = manifest(2), lastNotifiedVersionCode = 0))
        assertFalse(AppUpdates.shouldNotify(1, manifest(2), lastNotifiedVersionCode = 2), "already announced")
        assertFalse(AppUpdates.shouldNotify(2, manifest(2), 0), "already installed")
        assertFalse(AppUpdates.shouldNotify(3, manifest(2), 0), "older than installed")
        assertFalse(AppUpdates.shouldNotify(1, manifest(2, link = "https://evil.example/a.apk"), 0), "untrusted link")
        assertTrue(AppUpdates.shouldNotify(1, manifest(3), lastNotifiedVersionCode = 2), "a later version is announced again")
    }
}
