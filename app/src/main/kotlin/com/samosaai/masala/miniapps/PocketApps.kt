package com.samosaai.masala.miniapps

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One app from the bundled pocket pack (`assets/miniapps/catalog.json`): a single HTML file that
 * runs offline and keeps its own data on the device. The pack is read-only; installing one copies
 * nothing, it just makes the app visible in the store and the launcher.
 */
@Serializable
data class PocketApp(
    /** Stable id: the file path without `.html`, e.g. `games/puzzle/stack-tower`. */
    val id: String,
    val title: String,
    /** `apps` or `games`; [group] is the subcategory (`health`, `puzzle`…). */
    val category: String,
    val group: String = "",
    /** Emoji shown on the card until a real icon ships with the app. */
    val icon: String = "",
    val description: String = "",
    val players: String = "",
    /** Path under `assets/miniapps/`, e.g. `games/puzzle/stack-tower.html`. */
    val rel: String,
) {
    val assetPath: String get() = "miniapps/$rel"
}

/** Reads the bundled app catalogue. Pure parsing so the catalogue can be checked without Android. */
object PocketCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<PocketApp> = json.decodeFromString(text)

    /** The whole pack, or an empty list when the asset is missing (a build without the pack). */
    fun load(context: Context): List<PocketApp> = runCatching {
        parse(context.assets.open(CATALOG).bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    @Volatile private var cache: List<PocketApp>? = null

    /** [load], memoized for the process: the viewer resolves a bundled id without re-parsing. */
    fun cached(context: Context): List<PocketApp> =
        cache ?: load(context.applicationContext).also { cache = it }

    const val CATALOG = "miniapps/catalog.json"
}
