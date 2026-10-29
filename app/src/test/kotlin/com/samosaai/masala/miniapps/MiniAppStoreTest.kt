package com.samosaai.masala.miniapps

import com.samosaai.masala.data.MiniAppDao
import com.samosaai.masala.data.MiniAppEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniAppStoreTest {
    private class FakeDao : MiniAppDao {
        val rows = MutableStateFlow<List<MiniAppEntity>>(emptyList())
        override fun all(): Flow<List<MiniAppEntity>> = rows
        override suspend fun get(id: String): MiniAppEntity? = rows.value.find { it.id == id }
        override suspend fun upsert(entity: MiniAppEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun setNetworkAllowed(id: String, allowed: Boolean, now: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(networkAllowed = allowed, updatedAt = now) else it }
        }
    }

    @Test
    fun `create then update, rename, flag and delete`() = runBlocking {
        val store = MiniAppStore(FakeDao())
        val id = store.create("Game", "<html>hi</html>")!!
        assertTrue(id.isNotBlank())
        assertEquals("<html>hi</html>", store.get(id)?.html)
        assertTrue(store.update(id, "<html>v2</html>"))
        assertEquals("<html>v2</html>", store.get(id)?.html)
        assertTrue(store.rename(id, "Snake"))
        assertEquals("Snake", store.get(id)?.title)
        assertFalse(store.rename(id, "  "))
        store.setNetworkAllowed(id, true)
        assertEquals(true, store.get(id)?.networkAllowed)
        assertFalse(store.update("nope", "<html/>"))
        store.delete(id)
        assertNull(store.get(id))
    }

    @Test
    fun `blank title falls back and oversized html is refused`() = runBlocking {
        val store = MiniAppStore(FakeDao())
        val id = store.create("  ", "<html>ok</html>")!!
        assertEquals("Mini app", store.get(id)?.title)
        // Refused rather than cut: a sliced document would be silently broken.
        assertNull(store.create("Big", "x".repeat(MiniAppStore.MAX_HTML_CHARS + 1)))
        assertFalse(store.update(id, "x".repeat(MiniAppStore.MAX_HTML_CHARS + 1)))
        assertEquals("<html>ok</html>", store.get(id)?.html)
    }
}
