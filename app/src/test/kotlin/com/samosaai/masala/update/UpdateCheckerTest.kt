package com.samosaai.masala.update

import com.samosaai.masala.core.update.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        assertEquals(listOf(ok.getOrThrow().name), dir.listFiles().orEmpty().map { it.name }, "the mismatched download deleted only its own file")
    }

    @Test
    fun downloadRefusesUntrustedLinks() = runBlocking<Unit> {
        var installed = false
        val error = checker().downloadAndInstall(
            context = android.content.ContextWrapper(null),
            manifest = UpdateManifest(2, "0.2.0", server.url("/a.apk").toString(), apkSha),
        ) { installed = true; null }
        assertEquals("Untrusted download link", error)
        assertFalse(installed)
        assertEquals(0, server.requestCount)
    }

    private fun manifest() = UpdateManifest(2, "0.2.0", server.url("/a.apk").toString(), apkSha)

    @Test
    fun cancellingOneAttemptLeavesAnotherAttemptsFileAlone() = runBlocking<Unit> {
        // A: a slow download that gets cancelled part-way. B: a normal one running at the same time.
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)).throttleBody(4_096, 50, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        val c = checker()
        val a = async(Dispatchers.IO) { c.downloadTo(dir, manifest()) }
        server.takeRequest(5, TimeUnit.SECONDS)
        val b = async(Dispatchers.IO) { c.downloadTo(dir, manifest()) }
        val bFile = b.await().getOrThrow()
        a.cancelAndJoin()
        assertTrue(bFile.isFile, "B's file survives A's cleanup")
        assertTrue(bFile.readBytes().contentEquals(apkBytes))
        assertEquals(listOf(bFile.name), dir.listFiles().orEmpty().map { it.name }, "A removed only its own file")
    }

    @Test
    fun cancellingEndsABlockedRead() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)).setBodyDelay(30, TimeUnit.SECONDS))
        val a = async(Dispatchers.IO) { checker().downloadTo(dir, manifest()) }
        server.takeRequest(5, TimeUnit.SECONDS)
        val started = System.nanoTime()
        a.cancelAndJoin()
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "cancel didn't wait for the 30 s body")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun updatesInstallOneAtATimeAndCleanUp() = runBlocking<Unit> {
        repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(apkBytes))) }
        val c = checker()
        val active = AtomicInteger()
        var most = 0
        val install: suspend (File) -> String? = { apk ->
            most = maxOf(most, active.incrementAndGet())
            assertTrue(apk.readBytes().contentEquals(apkBytes), "the installer gets the verified bytes")
            delay(200)
            active.decrementAndGet()
            null
        }
        val results = listOf(
            async(Dispatchers.IO) { c.downloadAndInstallFrom(dir, manifest(), install = install) },
            async(Dispatchers.IO) { c.downloadAndInstallFrom(dir, manifest(), install = install) },
        ).awaitAll()
        assertEquals(listOf<String?>(null, null), results)
        assertEquals(1, most, "never two installs at once")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the APK is deleted after the installer took it")
    }

    @Test
    fun checksumOrInstallFailureIsReported() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes.copyOf(10))))
        var installs = 0
        val bad = checker().downloadAndInstallFrom(dir, manifest()) { installs++; null }
        assertEquals("The download doesn't match the published checksum", bad)
        assertEquals(0, installs)

        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        assertEquals("Nobody confirmed", checker().downloadAndInstallFrom(dir, manifest()) { "Nobody confirmed" })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
