package com.samosaai.masala.update

import com.samosaai.masala.core.update.UpdateManifest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val trustedUrl = "https://github.com/samosa-ai-com/Masala-AI-Launcher/releases/download/v0.2.0/Masala-0.2.0.apk"

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("updates").toFile()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun checker() = UpdateChecker(manifestUrl = server.url("/update.json").toString())

    private fun manifestJson(code: Int, link: String = trustedUrl) =
        """{"versionCode":$code,"versionName":"0.2.0","downloadUrl":"$link","releaseNotes":"notes","sha256":"$apkSha"}"""

    @Test
    fun newerTrustedVersionIsAvailable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson(2)))
        val result = checker().check(installedVersionCode = 1)
        assertIs<UpdateChecker.Check.Available>(result)
        assertEquals("0.2.0", result.manifest.versionName)
    }

    @Test
    fun sameVersionIsUpToDate() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson(2)))
        assertEquals(UpdateChecker.Check.UpToDate, checker().check(installedVersionCode = 2))
    }

    @Test
    fun missingManifestMeansNothingPublishedYet() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateChecker.Check.NothingPublished, checker().check(installedVersionCode = 1))
    }

    @Test
    fun untrustedLinkOrBrokenManifestFails() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson(2, link = "https://evil.example/masala.apk")))
        assertIs<UpdateChecker.Check.Failed>(checker().check(installedVersionCode = 1))
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertIs<UpdateChecker.Check.Failed>(checker().check(installedVersionCode = 1))
        server.enqueue(MockResponse().setResponseCode(500))
        assertIs<UpdateChecker.Check.Failed>(checker().check(installedVersionCode = 1))
    }

    @Test
    fun downloadVerifiesTheChecksum() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        val progress = mutableListOf<Int>()
        val ok = checker().downloadTo(dir, UpdateManifest(2, "0.2.0", server.url("/a.apk").toString(), apkSha)) { progress += it }
        assertTrue(ok.isSuccess)
        assertTrue(ok.getOrThrow().readBytes().contentEquals(apkBytes))
        assertEquals(100, progress.last())

        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes.copyOf(apkBytes.size - 1))))
        val bad = checker().downloadTo(dir, UpdateManifest(2, "0.2.0", server.url("/a.apk").toString(), apkSha))
        assertTrue(bad.isFailure)
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "apk" }, "a mismatched download is deleted")
    }

    @Test
    fun downloadRefusesUntrustedLinks() = runBlocking<Unit> {
        val result = checker().download(
            context = android.content.ContextWrapper(null),
            manifest = UpdateManifest(2, "0.2.0", server.url("/a.apk").toString(), apkSha),
        )
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }
}
