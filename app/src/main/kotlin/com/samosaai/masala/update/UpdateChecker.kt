package com.samosaai.masala.update

import android.content.Context
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.core.update.AppUpdates
import com.samosaai.masala.core.update.UpdateManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The network half of the in-app updater: reads `update.json` from the repository and downloads the
 * APK it names. Trust: https only, the link must be one of this repository's release downloads
 * ([AppUpdates.isTrustedDownloadUrl]), and the file must match the manifest's sha256 before it is
 * offered for install. Android then refuses an APK signed with any other key.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient,
    private val manifestUrl: String = AppUpdates.MANIFEST_URL,
) {
    sealed interface Check {
        data class Available(val manifest: UpdateManifest) : Check
        data object UpToDate : Check

        /** No `update.json` yet: nothing has been released. */
        data object NothingPublished : Check
        data class Failed(val reason: String) : Check
    }

    suspend fun check(installedVersionCode: Int = BuildConfig.VERSION_CODE): Check = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext Check.NothingPublished
                if (!response.isSuccessful) return@withContext Check.Failed("HTTP ${response.code}")
                val manifest = AppUpdates.parse(response.body?.string().orEmpty())
                when {
                    !AppUpdates.isNewer(installedVersionCode, manifest) -> Check.UpToDate
                    !AppUpdates.isTrustedDownloadUrl(manifest.downloadUrl) -> Check.Failed("untrusted download link")
                    else -> Check.Available(manifest)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Downloads [manifest]'s APK into the app cache and verifies it; refuses an untrusted link. */
    suspend fun download(context: Context, manifest: UpdateManifest, onProgress: (Int) -> Unit = {}): Result<File> {
        if (!AppUpdates.isTrustedDownloadUrl(manifest.downloadUrl)) {
            return Result.failure(IllegalArgumentException("Untrusted download link"))
        }
        return downloadTo(File(context.cacheDir, DIR), manifest, onProgress)
    }

    /** [download] without the link check, so JVM tests can serve the APK from a local server. */
    internal suspend fun downloadTo(dir: File, manifest: UpdateManifest, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            val apk = File(dir.apply { mkdirs() }, "masala-${manifest.versionCode}.apk")
            try {
                dir.listFiles { f -> f.isFile && f.extension == "apk" }?.forEach { it.delete() }
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(manifest.downloadUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
                    val body = response.body ?: error("Download failed (empty response)")
                    val total = body.contentLength()
                    var read = 0L
                    var last = -1
                    body.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                if (total > 0) {
                                    val percent = (read * 100 / total).toInt()
                                    if (percent != last) onProgress(percent.also { last = it })
                                }
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != manifest.sha256) error("The download doesn't match the published checksum")
                onProgress(100)
                Result.success(apk)
            } catch (e: CancellationException) {
                apk.delete()
                throw e
            } catch (e: Exception) {
                apk.delete()
                Result.failure(e)
            }
        }

    companion object {
        private const val DIR = "updates"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
