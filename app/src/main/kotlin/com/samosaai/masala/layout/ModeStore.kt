package com.samosaai.masala.layout

import android.content.Context
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.ModePatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * The modes the user has and which are on. Phase 2 needs just this much for `ModeActive` rules,
 * `SetMode` actions and the debug panel; the mode manager, triggers and DND policy come with the mode
 * UI (plan Phase 5). Small enough for SharedPreferences, and read at once so the first frame is right.
 */
class ModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("modes", Context.MODE_PRIVATE)
    private val patchesSerializer = ListSerializer(ModePatch.serializer())

    private val _patches = MutableStateFlow(
        prefs.getString(KEY_PATCHES, null)?.let { runCatching { MasalaJson.lenient.decodeFromString(patchesSerializer, it) }.getOrNull() }.orEmpty(),
    )
    val patches: StateFlow<List<ModePatch>> = _patches.asStateFlow()

    private val _active = MutableStateFlow(prefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty().toSet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    /** Adds or replaces a mode. */
    fun put(patch: ModePatch) {
        _patches.value = _patches.value.filterNot { it.id == patch.id } + patch
        prefs.edit().putString(KEY_PATCHES, MasalaJson.strict.encodeToString(patchesSerializer, _patches.value)).apply()
    }

    fun setActive(id: String, on: Boolean) {
        _active.value = if (on) _active.value + id else _active.value - id
        prefs.edit().putStringSet(KEY_ACTIVE, _active.value).apply()
    }

    fun isActive(id: String) = id in _active.value

    /** The patches that are on, for the resolver. */
    fun activePatches(): List<ModePatch> = _patches.value.filter { it.id in _active.value }

    private companion object {
        const val KEY_PATCHES = "patches"
        const val KEY_ACTIVE = "active"
    }
}
