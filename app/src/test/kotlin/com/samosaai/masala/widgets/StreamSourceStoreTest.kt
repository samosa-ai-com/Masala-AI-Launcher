package com.samosaai.masala.widgets

import com.samosaai.masala.data.StreamSourceDao
import com.samosaai.masala.data.StreamSourceEntity
import com.samosaai.masala.llm.KeyCipher
import com.samosaai.masala.widgets.media.isLanUrl
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The source registry without Android: validation, secrets sealing, credentialed URLs. */
class StreamSourceStoreTest {

    private class FakeDao : StreamSourceDao {
        val rows = HashMap<String, StreamSourceEntity>()
        val flow = MutableStateFlow<List<StreamSourceEntity>>(emptyList())
        override suspend fun all(): List<StreamSourceEntity> = rows.values.toList()
        override fun watch(): Flow<List<StreamSourceEntity>> = flow
        override suspend fun get(id: String): StreamSourceEntity? = rows[id]
        override suspend fun upsert(entity: StreamSourceEntity) { rows[entity.id] = entity }
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val dao = FakeDao()
    private val store = StreamSourceStore(dao, KeyCipher { key })

    private fun add(
        name: String = "Front door",
        kind: StreamKind = StreamKind.RTSP,
        url: String = "rtsp://192.168.1.10/stream",
        user: String? = null,
        pass: String? = null,
    ) = runBlocking { store.add(name, kind, url, null, user, pass, "tcp") }

    @Test
    fun cleanSourceSavesWithoutCredentials() = runBlocking {
        val (id, message) = add()
        assertNotNull(id, message)
        store.load()
        val source = store.current().getValue(id!!)
        assertEquals("Front door", source.name)
        assertTrue(!source.hasCredentials)
        assertEquals("rtsp://192.168.1.10/stream", store.urlWithCredentials(id))
    }

    @Test
    fun credentialsSealAndReassemble() = runBlocking {
        val (id, message) = add(user = "admin", pass = "s3cret")
        assertNotNull(id, message)
        store.load()
        assertEquals("rtsp://admin:s3cret@192.168.1.10/stream", store.urlWithCredentials(id!!))
        // The row holds ciphertext, never the password.
        assertTrue(dao.rows.getValue(id).secret?.contains("s3cret") == false)
    }

    @Test
    fun userinfoAndSecretParamsRejected() = runBlocking {
        assertNull(add(url = "rtsp://admin:s3cret@192.168.1.10/stream").first)
        assertNull(add(url = "http://192.168.1.10/mjpeg?password=x").first)
        assertNull(add(url = "not a url").first)
        assertNull(add(url = "ftp://192.168.1.10/x").first)
        assertNull(add(user = "admin").first)
        assertNull(add(name = "  ").first)
        assertTrue(store.current().isEmpty())
    }

    @Test
    fun unknownSourceHasNoUrl() {
        assertNull(store.urlWithCredentials("missing"))
    }

    @Test
    fun lanHosts() {
        assertTrue(isLanUrl("rtsp://192.168.1.10/x"))
        assertTrue(isLanUrl("http://10.0.0.5:8080/m.jpeg"))
        assertTrue(isLanUrl("http://172.16.4.1/x"))
        assertTrue(isLanUrl("http://172.31.255.1/x"))
        assertTrue(isLanUrl("http://localhost:8090/x"))
        assertTrue(isLanUrl("http://127.0.0.1/x"))
        assertTrue(isLanUrl("http://printer.local/x"))
        assertTrue(!isLanUrl("rtsp://203.0.113.10/x"))
        assertTrue(!isLanUrl("https://example.com/x"))
        assertTrue(!isLanUrl("http://172.15.0.1/x"))
        assertTrue(!isLanUrl("http://172.32.0.1/x"))
        assertTrue(!isLanUrl("not a url"))
    }
}
