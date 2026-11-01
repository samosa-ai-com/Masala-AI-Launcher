package com.samosaai.masala.widgets

import android.util.Log
import com.samosaai.masala.data.StreamSourceDao
import com.samosaai.masala.data.StreamSourceEntity
import com.samosaai.masala.llm.KeyCipher
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A camera or stream a widget may show: the URL never carries credentials (plan §4.2). */
data class StreamSource(
    val id: String,
    val name: String,
    val kind: StreamKind,
    val url: String,
    val snapshotUrl: String?,
    val hasCredentials: Boolean,
    /** rtsp transport: tcp or udp. */
    val transport: String?,
)

enum class StreamKind {
    RTSP, HLS, DASH, MJPEG, SNAPSHOT, PROGRESSIVE;

    companion object {
        fun parse(raw: String): StreamKind? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * The `stream_sources` registry (plan `docs/plans/widget-maker.md` §4.2): names, kinds and URLs
 * without userinfo. Usernames and passwords are sealed by the Keystore ([KeyCipher]) into the
 * row's `secret` blob; only [urlWithCredentials] ever reassembles a credentialed URL, and only
 * for the player, the snapshot poller and `test_stream` — never for a layout, a prompt or the log.
 */
class StreamSourceStore(
    private val dao: StreamSourceDao,
    private val cipher: KeyCipher,
) {
    private val _sources = MutableStateFlow<List<StreamSource>>(emptyList())
    val sources: StateFlow<List<StreamSource>> = _sources.asStateFlow()

    fun current(): Map<String, StreamSource> = _sources.value.associateBy { it.id }

    suspend fun load() {
        val rows = runCatching { dao.all() }.getOrElse {
            Log.w(TAG, "Stream sources unreadable", it)
            return
        }
        loadedRows = rows.associateBy { it.id }
        _sources.value = rows.map { it.toSource() }
    }

    /** Adds or replaces a source. Returns the id, or null with [message] saying why not. */
    suspend fun add(
        name: String,
        kind: StreamKind,
        url: String,
        snapshotUrl: String?,
        username: String?,
        password: String?,
        transport: String?,
    ): Pair<String?, String?> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null to "A source needs a name."
        val cleanUrl = url.trim()
        val parsed = runCatching { URI(cleanUrl) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank() || parsed.scheme.isNullOrBlank()) {
            return null to "That URL doesn't parse."
        }
        if (!parsed.userInfo.isNullOrBlank()) return null to "Put the username and password in their own fields, not in the URL."
        if (!ALLOWED_SCHEMES.contains(parsed.scheme.lowercase())) {
            return null to "Only ${ALLOWED_SCHEMES.sorted().joinToString(", ")} URLs."
        }
        secretQueryKey(cleanUrl)?.let { return null to "The URL must not carry '$it'." }
        val cleanSnapshot = snapshotUrl?.trim().orEmpty()
        if (cleanSnapshot.isNotEmpty()) {
            val snap = runCatching { URI(cleanSnapshot) }.getOrNull()
            if (snap == null || snap.host.isNullOrBlank()) return null to "That snapshot URL doesn't parse."
            if (!snap.userInfo.isNullOrBlank()) return null to "Put the username and password in their own fields, not in the snapshot URL."
            if (snap.scheme.lowercase() != "http" && snap.scheme.lowercase() != "https") return null to "Snapshot URLs are http(s)."
        }
        val user = username?.trim().orEmpty()
        val pass = password.orEmpty()
        if ((user.isEmpty()) != pass.isEmpty()) return null to "A username needs a password and the other way round."
        val id = "cam-" + cleanName.slug()
        val secret = if (pass.isEmpty()) null else runCatching { cipher.encrypt("$user\n$pass") }.getOrNull()
            ?: return null to "The password couldn't be sealed; try again."
        val now = System.currentTimeMillis()
        val existing = runCatching { dao.get(id) }.getOrNull()
        runCatching {
            dao.upsert(
                StreamSourceEntity(id, cleanName, kind.name.lowercase(), cleanUrl, cleanSnapshot.ifEmpty { null }, secret, transport?.lowercase(), existing?.createdAt ?: now, now),
            )
        }.onFailure { Log.w(TAG, "Stream source save failed: $id", it); return null to "Couldn't save the source." }
        load()
        return id to null
    }

    suspend fun remove(id: String) {
        runCatching { dao.delete(id) }.onFailure { Log.w(TAG, "Stream source delete failed: $id", it); return }
        load()
    }

    /**
     * The URL with credentials injected, for the player, the snapshot poller and `test_stream`
     * only. Null when the source is unknown or its secret no longer opens.
     */
    fun urlWithCredentials(id: String): String? {
        val entity = loadedRows[id] ?: return null
        val secret = entity.secret ?: return entity.url
        val (user, pass) = cipher.decrypt(secret)?.split('\n', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return runCatching {
            val uri = URI(entity.url)
            URI(uri.scheme, "$user:$pass", uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull()
    }

    fun snapshotUrlWithCredentials(id: String): String? {
        val entity = loadedRows[id] ?: return null
        val snap = entity.snapshotUrl ?: return urlWithCredentials(id)?.let { snapshotFallback(it) }
        val secret = entity.secret ?: return snap
        val (user, pass) = cipher.decrypt(secret)?.split('\n', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return runCatching {
            val uri = URI(snap)
            URI(uri.scheme, "$user:$pass", uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull()
    }

    private var loadedRows: Map<String, StreamSourceEntity> = emptyMap()

    private fun StreamSourceEntity.toSource() = StreamSource(
        id = id, name = name, kind = StreamKind.parse(kind) ?: StreamKind.SNAPSHOT,
        url = url, snapshotUrl = snapshotUrl, hasCredentials = secret != null, transport = transport,
    )

    companion object {
        private const val TAG = "StreamSourceStore"
        private val ALLOWED_SCHEMES = setOf("rtsp", "rtsps", "http", "https")
        private val SECRET_QUERY_KEYS = setOf("password", "passwd", "pwd", "token", "auth", "secret", "key")

        internal fun secretQueryKey(url: String): String? {
            val query = runCatching { URI(url).query }.getOrNull() ?: return null
            return query.split('&').mapNotNull {
                val key = it.substringBefore('=').trim().lowercase()
                key.takeIf { key in SECRET_QUERY_KEYS }
            }.firstOrNull()
        }

        /** MJPEG and single-snapshot kinds play through their stream URL when no snapshot URL is set. */
        internal fun snapshotFallback(streamUrl: String): String = streamUrl

        private fun String.slug(): String {
            var clean = lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
                .replace(Regex("_+"), "_").take(48)
            if (clean.firstOrNull()?.isLetter() != true) clean = "cam"
            return clean
        }
    }
}
