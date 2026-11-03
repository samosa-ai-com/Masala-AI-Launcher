package com.samosaai.masala.wallpapers

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.wallpaper.RoutineConditions
import com.samosaai.masala.core.wallpaper.RoutineRule
import com.samosaai.masala.core.wallpaper.WallpaperRoutine
import com.samosaai.masala.data.WallpaperRoutineDao
import com.samosaai.masala.data.WallpaperRoutineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WallpaperRoutineTest {

    private class FakeDao : WallpaperRoutineDao {
        val rows = mutableMapOf<String, WallpaperRoutineEntity>()
        override suspend fun upsert(routine: WallpaperRoutineEntity) { rows[routine.id] = routine }
        override suspend fun all(): List<WallpaperRoutineEntity> = rows.values.toList()
        override fun watch(): Flow<List<WallpaperRoutineEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    private fun routine(id: String = "day") = WallpaperRoutine(
        id = id,
        name = "Day cycle",
        rules = listOf(RoutineRule(RoutineConditions(from = "06:00", to = "10:00"), "asset:aurora.webp")),
        defaultSource = "id:3",
    )

    @Test
    fun `a routine round-trips, toggles and deletes`() = runBlocking {
        val dao = FakeDao()
        val store = WallpaperRoutineStore(dao)
        store.save(routine())
        assertEquals(listOf("day"), store.all().map { it.id })
        assertEquals("asset:aurora.webp", store.all().first().rules.first().source)

        store.setEnabled("day", false)
        assertFalse(store.all().first().enabled)
        store.delete("day")
        assertEquals(emptyList(), store.all())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `an unreadable row is skipped, never fatal`() = runBlocking {
        val dao = FakeDao()
        dao.rows["broken"] = WallpaperRoutineEntity("broken", "{not json", 1)
        dao.rows["day"] = WallpaperRoutineEntity(
            "day",
            MasalaJson.strict.encodeToString(WallpaperRoutine.serializer(), routine()),
            2,
        )
        val store = WallpaperRoutineStore(dao)
        assertEquals(listOf("day"), store.all().map { it.id })
        assertEquals(1, store.watch().first().size)
    }

    @Test
    fun `the set tool derives an id, rejects bad routines and saves good ones`() = runBlocking {
        val store = WallpaperRoutineStore(FakeDao())
        var refreshed = 0
        val tools = WallpaperRoutineTools(store, refresh = { refreshed++ })

        val good = buildJsonObject {
            put("name", "Evening")
            putJsonArray("rules") {
                add(
                    buildJsonObject {
                        put("source", "asset:aurora.webp")
                        putJsonObject("when") {
                            put("from", "18:00")
                            put("to", "23:00")
                            putJsonArray("weather") { add("rain") }
                        }
                    },
                )
            }
        }
        val saved = tools.set(good)
        assertTrue(saved.success, saved.message)
        assertEquals("evening", store.all().single().id)
        assertEquals(1, refreshed)

        // A window with a non-time is refused with the path, not stored.
        val bad = buildJsonObject {
            put("name", "Broken")
            putJsonArray("rules") {
                add(
                    buildJsonObject {
                        put("source", "teleport")
                        putJsonObject("when") { put("from", "99:00"); put("to", "10:00") }
                    },
                )
            }
        }
        val refused = tools.set(bad)
        assertFalse(refused.success)
        assertTrue(refused.message.contains("$.rules[0].when.from"), refused.message)
        assertTrue(refused.message.contains("$.rules[0].source"), refused.message)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `status explains what is showing, or that nothing is`() = runBlocking {
        val store = WallpaperRoutineStore(FakeDao())
        val empty = WallpaperRoutineTools(store)
        assertTrue(empty.status().message.contains("No wallpaper routines"))

        store.save(routine())
        val status = WallpaperRoutineTools(
            store,
            statusOf = {
                WallpaperRoutineEngine.Status(
                    routineName = "Day cycle",
                    ruleIndex = 1,
                    source = "asset:aurora.webp",
                    nextChange = java.time.LocalDateTime.of(2026, 11, 3, 18, 0),
                )
            },
        ).status()
        assertTrue(status.message.contains("Day cycle"), status.message)
        assertTrue(status.message.contains("rule 2"), status.message)
        assertTrue(status.message.contains("18:00"), status.message)
    }

    @Test
    fun `a payload with an unknown field is refused rather than silently dropped`() = runBlocking<Unit> {
        val store = WallpaperRoutineStore(FakeDao())
        val tools = WallpaperRoutineTools(store)
        val typo = JsonObject(
            buildJsonObject {
                put("name", "Typo")
                put("rulez", buildJsonArray { })
            },
        )
        val result = tools.set(typo)
        assertFalse(result.success)
        assertNotNull(result.message)
    }
}
