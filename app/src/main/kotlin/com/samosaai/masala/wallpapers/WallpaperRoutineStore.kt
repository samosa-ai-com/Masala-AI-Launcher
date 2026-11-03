package com.samosaai.masala.wallpapers

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.wallpaper.WallpaperRoutine
import com.samosaai.masala.data.WallpaperRoutineDao
import com.samosaai.masala.data.WallpaperRoutineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wallpaper routines on disk (plan `docs/plans/wallpaper-routines.md` §6): one JSON row each, so
 * the schema can grow without a migration. Unreadable rows are skipped, never fatal.
 */
class WallpaperRoutineStore(private val dao: WallpaperRoutineDao) {

    fun watch(): Flow<List<WallpaperRoutine>> =
        dao.watch().map { rows -> rows.mapNotNull { it.routine() } }

    suspend fun all(): List<WallpaperRoutine> = dao.all().mapNotNull { it.routine() }

    suspend fun get(id: String): WallpaperRoutine? = all().firstOrNull { it.id == id }

    suspend fun save(routine: WallpaperRoutine) {
        dao.upsert(
            WallpaperRoutineEntity(
                id = routine.id,
                json = MasalaJson.strict.encodeToString(WallpaperRoutine.serializer(), routine),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** The switch on a routine row; a name or id that no longer exists does nothing. */
    suspend fun setEnabled(id: String, on: Boolean) {
        get(id)?.let { save(it.copy(enabled = on)) }
    }

    private fun WallpaperRoutineEntity.routine(): WallpaperRoutine? =
        runCatching { MasalaJson.lenient.decodeFromString(WallpaperRoutine.serializer(), json) }
            .getOrNull()
            ?.let { if (it.id == id) it else it.copy(id = id) }
}
