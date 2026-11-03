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

    /** One row of the storefront: a header kind, the group name for a category shelf, and the apps. */
    enum class ShelfKind { FEATURED, TOP_GAMES, GROUP }

    data class Shelf(val kind: ShelfKind, val apps: List<PocketApp>, val title: String = "")

    /**
     * The shelves the storefront shows, built once per catalogue:
     *
     * 1. **Featured** — one app from each of the tool groups, so the row spans the pack instead
     *    of showing four puzzle games; games fill any gap when the pack has fewer tools.
     * 2. **Top games** — the first games by title (there is no usage data to rank by yet).
     * 3. **One shelf per category** with at least [SHELF_MIN] apps, the biggest first.
     *
     * An app appears on one shelf only, so the page never pads itself with repeats.
     */
    fun shelves(apps: List<PocketApp>, maxShelves: Int = 12, perShelf: Int = 20): List<Shelf> {
        if (apps.isEmpty()) return emptyList()
        val used = HashSet<String>()
        val featured = mutableListOf<PocketApp>()
        for (group in listOf("tools", "health", "money", "home")) {
            apps.firstOrNull { it.category == "apps" && it.group == group && it.id !in used }?.let {
                featured += it
                used += it.id
            }
        }
        if (featured.size < FEATURED_COUNT) {
            apps.asSequence()
                .filter { it.category == "games" && it.id !in used }
                .sortedBy { it.title.lowercase() }
                .take(FEATURED_COUNT - featured.size)
                .forEach { featured += it; used += it.id }
        }

        val topGames = apps.asSequence()
            .filter { it.category == "games" && it.id !in used }
            .sortedBy { it.title.lowercase() }
            .take(perShelf)
            .toList()
            .onEach { used += it.id }

        val byGroup = apps.asSequence()
            .filter { it.id !in used }
            .groupBy { it.category to it.group }
            .filter { it.value.size >= SHELF_MIN }
            .entries
            .sortedByDescending { it.value.size }
            .take(maxShelves)
            .map { (key, list) ->
                val shelfApps = list.sortedBy { it.title.lowercase() }.take(perShelf)
                shelfApps.forEach { used += it.id }
                Shelf(ShelfKind.GROUP, shelfApps, shelfTitle(key.first, key.second))
            }

        return buildList {
            if (featured.isNotEmpty()) add(Shelf(ShelfKind.FEATURED, featured))
            if (topGames.isNotEmpty()) add(Shelf(ShelfKind.TOP_GAMES, topGames))
            addAll(byGroup)
        }
    }

    /** A shelf header: "Puzzle games" for a game group, the group's own name for an app group. */
    fun shelfTitle(category: String, group: String): String {
        val name = group.split('-', '_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return if (category == "games") "$name games" else name
    }

    /** The featured row's width. */
    const val FEATURED_COUNT = 4

    /** A category needs this many apps before it earns its own shelf. */
    const val SHELF_MIN = 4
}

/** Whether a lower-cased, trimmed query hits this entry's title, description, group or category. */
fun PocketApp.matches(q: String): Boolean {
    if (q.isEmpty()) return true
    return title.lowercase().contains(q) ||
        description.lowercase().contains(q) ||
        group.lowercase().contains(q) ||
        category.lowercase().contains(q)
}
