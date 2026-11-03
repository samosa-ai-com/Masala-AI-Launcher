package com.samosaai.masala.miniapps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bundled pack must stay in step with its catalogue: every entry has a file, ids are unique. */
class PocketCatalogTest {
    private val assets = File("src/main/assets/miniapps")

    @Test fun `catalogue parses and every entry has its file`() {
        val file = File(assets, "catalog.json")
        assertTrue(file.isFile, "missing ${file.path}")
        val apps = PocketCatalog.parse(file.readText())

        assertTrue(apps.size >= 300, "expected the full pack, got ${apps.size}")
        assertEquals(apps.size, apps.map { it.id }.distinct().size, "duplicate ids in the catalogue")
        val missing = apps.filterNot { File(assets, it.rel).isFile }.map { it.rel }
        assertEquals(emptyList(), missing, "catalogue lists files that are not in assets")
        assertTrue(apps.all { it.title.isNotBlank() && it.rel.endsWith(".html") })
        // Both halves are present, each with its own subcategories.
        assertTrue(apps.any { it.category == "games" })
        assertTrue(apps.any { it.category == "apps" })
    }

    @Test fun `paths are asset-relative`() {
        val app = PocketCatalog.parse(
            """[{"id":"games/puzzle/x","title":"X","category":"games","group":"puzzle","rel":"games/puzzle/x.html"}]""",
        ).single()
        assertEquals("miniapps/games/puzzle/x.html", app.assetPath)
    }
}
