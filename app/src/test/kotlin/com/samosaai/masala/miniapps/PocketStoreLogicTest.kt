package com.samosaai.masala.miniapps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Browse behaviour for the bundled store: tab shelves, search and the asset-loader URL mapping. */
class PocketStoreLogicTest {
    private val apps = listOf(
        PocketApp("apps/health/calm", "CalmLoop Breath", "apps", "health", "🌬️", "A guided breathing pacer.", "offline", "apps/health/calm.html"),
        PocketApp("apps/finance/loan", "Loan Planner", "apps", "finance", "💰", "Plan loan repayments.", "offline", "apps/finance/loan.html"),
        PocketApp("games/puzzle/tower", "Stack Tower", "games", "puzzle", "🧱", "A one-tap neon stacking game.", "1 player", "games/puzzle/tower.html"),
        PocketApp("games/quiz/angles", "Angle Trainer", "games", "quiz", "📐", "Train your eye for angles.", "1 player", "games/quiz/angles.html"),
    )

    /** A minimal catalogue entry for the shelf tests. */
    private fun app(id: String, title: String, category: String, group: String) =
        PocketApp(id, title, category, group, "✨", "", "1 player", "$id.html")

    @Test fun `asset urls point at the loader domain`() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/miniapps/games/puzzle/tower.html",
            PocketStoreLogic.assetUrl(apps[2]),
        )
    }

    @Test fun `only the pack directory is servable`() {
        assertTrue(PocketStoreLogic.isServable(PocketStoreLogic.assetUrl(apps[0])))
        assertFalse(PocketStoreLogic.isServable("https://appassets.androidplatform.net/assets/wallpapers/x.jpg"))
        assertFalse(PocketStoreLogic.isServable("https://example.com/miniapps/games/puzzle/tower.html"))
        assertFalse(PocketStoreLogic.isServable("file:///android_asset/miniapps/games/puzzle/tower.html"))
    }

    @Test fun `tile hues are stable and in range`() {
        val again = PocketStoreLogic.tileHue("games/puzzle/tower")
        assertEquals(PocketStoreLogic.tileHue("games/puzzle/tower"), again)
        apps.forEach {
            val h = PocketStoreLogic.tileHue(it.id)
            assertTrue(h in 0f..360f, "hue $h out of range for ${it.id}")
        }
    }

    @Test fun `group names prettify and title by category`() {
        assertEquals("Word Guessing", PocketStoreLogic.pretty("word-guessing"))
        assertEquals("Puzzle games", PocketStoreLogic.sectionTitle("games", "puzzle"))
        assertEquals("Tools", PocketStoreLogic.sectionTitle("apps", "tools"))
    }

    @Test fun `search hits title, description, group and category`() {
        assertEquals(listOf("apps/health/calm"), PocketStoreLogic.search(apps, "breath").map { it.id })
        assertEquals(listOf("games/puzzle/tower"), PocketStoreLogic.search(apps, "neon stacking").map { it.id })
        assertEquals(
            listOf("games/puzzle/tower", "games/quiz/angles"),
            PocketStoreLogic.search(apps, "games").map { it.id },
        )
        assertEquals(listOf("apps/finance/loan"), PocketStoreLogic.search(apps, "FINANCE").map { it.id })
    }

    @Test fun `blank search returns nothing`() {
        assertEquals(emptyList(), PocketStoreLogic.search(apps, ""))
        assertEquals(emptyList(), PocketStoreLogic.search(apps, "   "))
    }

    @Test fun `pill apps scope to the tab and the pill`() {
        assertEquals(
            listOf("games/quiz/angles"),
            PocketStoreLogic.pillApps(apps, "games", "quiz").map { it.id },
        )
        assertEquals(2, PocketStoreLogic.pillApps(apps, "games", "").size)
        assertEquals(emptyList(), PocketStoreLogic.pillApps(apps, "games", "health"))
        assertEquals(emptyList(), PocketStoreLogic.pillApps(apps, "nope", ""))
    }

    @Test fun `tab shelves span groups and never repeat hero into featured`() {
        // Enough entries to make several groups section-worthy.
        val many = (1..6).flatMap { n ->
            listOf(
                app("apps/tools/t$n", "Tool $n", "apps", "tools"),
                app("apps/health/h$n", "Health $n", "apps", "health"),
                app("games/puzzle/p$n", "Puzzle $n", "games", "puzzle"),
                app("games/arcade/a$n", "Arcade $n", "games", "arcade"),
            )
        }
        val shelves = PocketStoreLogic.tabShelves(many, "games")

        // The hero spans groups instead of showing one group's apps five times.
        assertEquals(5, shelves.hero.size)
        assertTrue(shelves.hero.map { it.group }.distinct().size > 1, "hero covers one group only")
        // Featured follows without repeating the hero.
        assertEquals(3, shelves.featured.size)
        val top = (shelves.hero + shelves.featured).map { it.id }
        assertEquals(top.size, top.distinct().size, "an app appears in hero and featured")
        // Pills list the tab's groups; sections carry titled top lists.
        assertEquals(listOf("arcade", "puzzle"), shelves.pills.sorted())
        assertTrue(shelves.sections.isNotEmpty())
        assertTrue(shelves.sections.all { it.apps.isNotEmpty() })
        assertTrue(shelves.sections.any { it.title == "Puzzle games" })
    }

    @Test fun `tab shelves titling and pills follow group size`() {
        val many = (1..4).map { n -> app("games/puzzle/p$n", "Puzzle $n", "games", "puzzle") } +
            app("games/quiz/q1", "Quiz One", "games", "quiz")
        val shelves = PocketStoreLogic.tabShelves(many, "games", maxSections = 2, perSection = 3)
        // Biggest group first in both pills and sections.
        assertEquals("puzzle", shelves.pills.first())
        assertEquals("puzzle", shelves.sections.first().group)
        assertEquals(3, shelves.sections.first().apps.size)
    }

    @Test fun `an empty tab has empty shelves`() {
        val shelves = PocketStoreLogic.tabShelves(apps, "nope")
        assertEquals(PocketStoreLogic.TabShelves(emptyList(), emptyList(), emptyList(), emptyList()), shelves)
    }
}
