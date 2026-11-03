package com.samosaai.masala.miniapps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Browse behaviour for the bundled store: grouping, search and the asset-loader URL mapping. */
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

    @Test fun `groups are distinct, sorted and scoped to the tab`() {
        assertEquals(listOf("finance", "health"), PocketStoreLogic.groups(apps, "apps"))
        assertEquals(listOf("puzzle", "quiz"), PocketStoreLogic.groups(apps, "games"))
        assertEquals(listOf("finance", "health", "puzzle", "quiz"), PocketStoreLogic.groups(apps, ""))
        assertEquals(emptyList(), PocketStoreLogic.groups(apps, "nope"))
    }

    @Test fun `empty filter returns everything`() {
        assertEquals(apps, PocketStoreLogic.filter(apps, "", "", ""))
    }

    @Test fun `search hits title, description, group and category`() {
        assertEquals(listOf("apps/health/calm"), PocketStoreLogic.filter(apps, "breath", "", "").map { it.id })
        assertEquals(listOf("games/puzzle/tower"), PocketStoreLogic.filter(apps, "neon stacking", "", "").map { it.id })
        assertEquals(
            listOf("games/puzzle/tower", "games/quiz/angles"),
            PocketStoreLogic.filter(apps, "games", "", "").map { it.id },
        )
        assertEquals(listOf("apps/finance/loan"), PocketStoreLogic.filter(apps, "FINANCE", "", "").map { it.id })
    }

    @Test fun `category and group narrow the search`() {
        assertEquals(listOf("games/quiz/angles"), PocketStoreLogic.filter(apps, "", "games", "quiz").map { it.id })
        assertEquals(emptyList(), PocketStoreLogic.filter(apps, "angles", "apps", ""))
        assertEquals(emptyList(), PocketStoreLogic.filter(apps, "", "games", "health"))
    }

    @Test fun `tile hues are stable and in range`() {
        val again = PocketStoreLogic.tileHue("games/puzzle/tower")
        assertEquals(PocketStoreLogic.tileHue("games/puzzle/tower"), again)
        apps.forEach {
            val h = PocketStoreLogic.tileHue(it.id)
            assertTrue(h in 0f..360f, "hue $h out of range for ${it.id}")
        }
    }

    @Test fun `shelves start with featured and never repeat an app`() {
        // Enough entries to make several groups shelf-worthy.
        val many = (1..6).flatMap { n ->
            listOf(
                app("apps/tools/t$n", "Tool $n", "apps", "tools"),
                app("apps/health/h$n", "Health $n", "apps", "health"),
                app("games/puzzle/p$n", "Puzzle $n", "games", "puzzle"),
                app("games/arcade/a$n", "Arcade $n", "games", "arcade"),
            )
        }
        val shelves = PocketStoreLogic.shelves(many)

        assertEquals(PocketStoreLogic.ShelfKind.FEATURED, shelves.first().kind)
        // Featured is always full, and it starts from the tool groups rather than games.
        assertEquals(PocketStoreLogic.FEATURED_COUNT, shelves.first().apps.size)
        assertTrue(shelves.first().apps.any { it.category == "apps" })
        val seen = mutableListOf<String>()
        shelves.forEach { shelf ->
            assertTrue(shelf.apps.isNotEmpty(), "empty shelf ${shelf.kind} ${shelf.title}")
            seen += shelf.apps.map { it.id }
        }
        assertEquals(seen.size, seen.distinct().size, "an app appears on two shelves")
        assertTrue(shelves.any { it.kind == PocketStoreLogic.ShelfKind.TOP_GAMES })
        // The remaining tool apps still earn a shelf of their own.
        assertTrue(shelves.any { it.kind == PocketStoreLogic.ShelfKind.GROUP && it.title == "Tools" })
    }

    @Test fun `an empty catalogue has no shelves`() {
        assertEquals(emptyList(), PocketStoreLogic.shelves(emptyList()))
    }
}
