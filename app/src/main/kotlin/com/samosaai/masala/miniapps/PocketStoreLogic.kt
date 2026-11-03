package com.samosaai.masala.miniapps

/**
 * Pure browse helpers for the bundled pocket-app store (`ui/miniapps/PocketStore.kt`): grouping,
 * search filtering, the asset-loader URL mapping and stable card-tile hues. JVM-only on purpose,
 * so the catalogue behaviour is unit-tested without Android.
 */
object PocketStoreLogic {
    /** Where a bundled app loads from: the pack served by `WebViewAssetLoader` (see `BundledMiniApp`). */
    const val ASSET_BASE = "https://appassets.androidplatform.net/assets/"

    /** The URL [BundledMiniAppView] loads for a catalogue entry. */
    fun assetUrl(app: PocketApp): String = ASSET_BASE + app.assetPath

    /** True when the loader may serve this URL: only the pack directory, never the app's own assets. */
    fun isServable(url: String): Boolean =
        url.startsWith(ASSET_BASE) && (url.removePrefix(ASSET_BASE).startsWith("miniapps/"))

    /** Subcategories for the group chip row, for the selected category tab (`""` = all). */
    fun groups(apps: List<PocketApp>, category: String): List<String> =
        apps.asSequence()
            .filter { category.isEmpty() || it.category == category }
            .map { it.group }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

    fun filter(apps: List<PocketApp>, query: String, category: String, group: String): List<PocketApp> {
        val q = query.trim().lowercase()
        return apps.filter { app ->
            (category.isEmpty() || app.category == category) &&
                (group.isEmpty() || app.group == group) &&
                (q.isEmpty() || app.matches(q))
        }
    }

    /** Stable tile hue (0..360) from the id, so a card keeps its colour across launches. */
    fun tileHue(id: String): Float {
        var h = 0
        for (c in id) h = h * 31 + c.code
        return ((h % 360) + 360) % 360f
    }
}

/** Whether a lower-cased, trimmed query hits this entry's title, description, group or category. */
fun PocketApp.matches(q: String): Boolean {
    if (q.isEmpty()) return true
    return title.lowercase().contains(q) ||
        description.lowercase().contains(q) ||
        group.lowercase().contains(q) ||
        category.lowercase().contains(q)
}
