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

    private class FakeSeeds(var ids: MutableSet<String> = mutableSetOf()) : WidgetSeedStore {
        override fun known(): Set<String> = ids
        override fun remember(ids: Set<String>) { this.ids += ids }
    }

    @Test
    fun `an upgrade adds the new templates once, and never a deleted one`() = runBlocking {
        // An install from before seeding bookkeeping: it has the original eight, minus one the
        // user deleted, and has never recorded what it was offered.
        val seeds = FakeSeeds()
        val dao = FakeDao()
        com.samosaai.masala.core.widgets.WidgetTemplates.legacyIds.dropLast(1).forEachIndexed { i, id ->
            dao.rows[id] = WidgetEntity(
                id = id,
                specJson = com.samosaai.masala.core.contract.MasalaJson.strict.encodeToString(
                    com.samosaai.masala.core.contract.WidgetSpec.serializer(),
                    com.samosaai.masala.core.widgets.WidgetTemplates.load(id).widget,
                ),
                tier = "spec", title = null, createdBy = "template", createdAt = 0, updatedAt = 0,
            )
        }
        val lib = WidgetLibrary(dao, FakeVersions(), seeds)
        lib.load(seedIfEmpty = true)
        val afterUpgrade = lib.current().keys
        // The new shelves arrived...
        assertTrue("pomodoro" in afterUpgrade)
        assertTrue("second_brain" in afterUpgrade)
        // ...and the deleted legacy template stayed deleted.
        val deleted = com.samosaai.masala.core.widgets.WidgetTemplates.legacyIds.last()
        assertFalse(deleted in afterUpgrade)

        // A second start adds nothing and keeps a fresh deletion gone.
        lib.delete("pomodoro")
        lib.load(seedIfEmpty = true)
        assertFalse("pomodoro" in lib.current())
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
