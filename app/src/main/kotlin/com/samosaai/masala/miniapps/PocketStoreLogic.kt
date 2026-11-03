package com.samosaai.masala.miniapps

/**
 * Pure browse helpers for the bundled pocket-app store (`ui/miniapps/PocketStore.kt`): the
 * per-tab shelves, search filtering, the asset-loader URL mapping and stable card-tile hues.
 * JVM-only on purpose, so the catalogue behaviour is unit-tested without Android.
 */
object PocketStoreLogic {
    /** Where a bundled app loads from: the pack served by `WebViewAssetLoader` (see `BundledMiniApp`). */
    const val ASSET_BASE = "https://appassets.androidplatform.net/assets/"

    /** The URL [BundledMiniAppView] loads for a catalogue entry. */
    fun assetUrl(app: PocketApp): String = ASSET_BASE + app.assetPath

    /** True when the loader may serve this URL: only the pack directory, never the app's own assets. */
    fun isServable(url: String): Boolean =
        url.startsWith(ASSET_BASE) && (url.removePrefix(ASSET_BASE).startsWith("miniapps/"))

    /** Stable tile hue (0..360) from the id, so a card keeps its colour across launches. */
    fun tileHue(id: String): Float {
        var h = 0
        for (c in id) h = h * 31 + c.code
        return ((h % 360) + 360) % 360f
    }

    /** `word-guessing` → `Word guessing`; the catalogue stores machine names. */
    fun pretty(group: String): String =
        group.split('-', '_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** A shelf header: "Puzzle games" for a game group, the group's own name for an app group. */
    fun sectionTitle(category: String, group: String): String {
        val name = pretty(group)
        return if (category == "games") "$name games" else name
    }

    /** One titled block of a tab home: the machine group name and its apps. */
    data class Section(val group: String, val title: String, val apps: List<PocketApp>)

    /**
     * Everything a Games/Apps tab home shows, partitioned once per catalogue load:
     *
     * - [pills]: the tab's groups, biggest first, for the filter-pill row (the leading "Top"
     *   pill means "all" and is added by the UI, not listed here).
     * - [hero]: one app per group, round-robin by group size, for the wide-card carousel — the
     *   row spans the tab instead of showing four puzzle games.
     * - [featured]: the next apps by title, for the compact rows under the carousel.
     * - [sections]: the biggest groups with their own top apps, for the titled list blocks.
     *
     * Hero and featured never repeat an app; sections are each group's own top list, so an app
     * may appear there as well as above (the same app in two different blocks is normal shelf
     * behaviour, unlike two identical shelves).
     */
    data class TabShelves(
        val pills: List<String>,
        val hero: List<PocketApp>,
        val featured: List<PocketApp>,
        val sections: List<Section>,
    )

    fun tabShelves(
        apps: List<PocketApp>,
        category: String,
        heroCount: Int = 5,
        featuredCount: Int = 3,
        maxSections: Int = 4,
        perSection: Int = 6,
        maxPills: Int = 8,
    ): TabShelves {
        val inTab = apps.asSequence()
            .filter { it.category == category }
            .sortedBy { it.title.lowercase() }
            .toList()
        if (inTab.isEmpty()) return TabShelves(emptyList(), emptyList(), emptyList(), emptyList())
        val byGroup = inTab.groupBy { it.group }.entries.sortedByDescending { it.value.size }

        val hero = mutableListOf<PocketApp>()
        var round = 0
        while (hero.size < heroCount) {
            var added = false
            for (entry in byGroup) {
                val pick = entry.value.getOrNull(round)
                if (pick != null && hero.size < heroCount) {
                    hero += pick
                    added = true
                }
            }
            if (!added) break
            round++
        }
        val heroIds = hero.map { it.id }.toHashSet()
        val featured = inTab.asSequence()
            .filter { it.id !in heroIds }
            .take(featuredCount)
            .toList()
        val sections = byGroup
            .take(maxSections)
            .map { entry ->
                Section(entry.key, sectionTitle(category, entry.key), entry.value.take(perSection))
            }
        return TabShelves(
            pills = byGroup.take(maxPills).map { it.key }.filter { it.isNotBlank() },
            hero = hero,
            featured = featured,
            sections = sections,
        )
    }

    /** The apps of one pill ("" = the whole tab), by title. */
    fun pillApps(apps: List<PocketApp>, category: String, pill: String): List<PocketApp> =
        apps.asSequence()
            .filter { it.category == category && (pill.isEmpty() || it.group == pill) }
            .sortedBy { it.title.lowercase() }
            .toList()

    /** Search over the whole catalogue: title, description, group or category. */
    fun search(apps: List<PocketApp>, query: String): List<PocketApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return apps.filter { it.matches(q) }
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
