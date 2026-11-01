package com.samosaai.masala.widgets

import com.samosaai.masala.data.WidgetDao
import com.samosaai.masala.data.WidgetEntity
import com.samosaai.masala.data.WidgetVersionDao
import com.samosaai.masala.data.WidgetVersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review fix: an emptied library stays empty — templates seed only on a fresh install. */
class WidgetLibraryTest {

    private class FakeDao : WidgetDao {
        val rows = mutableMapOf<String, WidgetEntity>()
        override suspend fun all(): List<WidgetEntity> = rows.values.toList()
        override fun watch(): Flow<List<WidgetEntity>> = MutableStateFlow(emptyList())
        override suspend fun get(id: String): WidgetEntity? = rows[id]
        override suspend fun upsert(entity: WidgetEntity) { rows[entity.id] = entity }
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    private class FakeVersions : WidgetVersionDao {
        override suspend fun versions(widgetId: String): List<WidgetVersionEntity> = emptyList()
        override suspend fun insert(version: WidgetVersionEntity) = Unit
        override suspend fun prune(widgetId: String) = Unit
        override suspend fun deleteForWidget(widgetId: String) = Unit
    }

    @Test
    fun `fresh install seeds templates, an emptied library stays empty`() = runBlocking {
        val lib = WidgetLibrary(FakeDao(), FakeVersions())
        lib.load(seedIfEmpty = true)
        assertTrue(lib.current().isNotEmpty())

        lib.current().keys.toList().forEach { lib.delete(it) }
        assertTrue(lib.current().isEmpty())

        // The next startup must not resurrect them.
        lib.load(seedIfEmpty = false)
        assertTrue(lib.current().isEmpty())
    }
}
