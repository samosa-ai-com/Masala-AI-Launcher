package com.samosaai.masala.secondbrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** P1: the file vault, links, graph and health — all on a throwaway directory. */
class SecondBrainStoreTest {
    private lateinit var dir: File
    private lateinit var store: SecondBrainStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("secondbrain").toFile()
        store = SecondBrainStore(dir, today = { "2026-10-29" })
        store.ensureDefaults()
    }

    private fun record(title: String, area: String = "Personal", type: String = "note", extra: String = "") =
        "---\ntitle: $title\narea: $area\ntype: $type\nupdated: 2026-10-29\n$extra---\n\nBody.\n"

    @Test fun freshVaultIsClean() {
        val snapshot = store.loadSnapshot()
        assertTrue(snapshot.available)
        val health = store.checkHealth(snapshot)
        assertTrue("issues: $health", health.isClean)
        assertTrue(graphIsConnected(store.buildGraph(snapshot)))
    }

    private fun graphIsConnected(graph: BrainGraph): Boolean {
        val linked = graph.edges.flatMap { listOf(it.fromId, it.toId) }.toSet()
        return graph.nodes.all { it.id in linked || it.kind in setOf("soul", "map", "user") }
    }

    @Test fun pathQualifiedLinksDoNotCrossResolve() {
        // [[people/missing]] must dangle even though notes/missing.md exists.
        File(dir, "notes/missing.md").writeText(record("Missing"))
        File(dir, "notes/a.md").writeText(record("A") + "\nSee [[people/missing]].\n")
        val health = store.checkHealth(store.loadSnapshot())
        assertEquals(listOf(DanglingLink("notes/a.md", "people/missing")), health.dangling.filter { it.fromId == "notes/a.md" })
    }

    @Test fun taskLinksFlagBothDirections() {
        File(dir, "notes/claimed.md").writeText(
            "---\ntitle: Claimed\narea: Personal\ntype: note\nupdated: 2026-10-29\ntask_ref: t-9\n---\n\nB.\n",
        )
        File(dir, "notes/unclaimed.md").writeText(record("Unclaimed"))
        val snapshot = store.loadSnapshot()
        val tasks = listOf(
            TaskLink("t-9", "Known", File(dir, "notes/claimed.md").absolutePath),
            TaskLink("t-8", "Linked elsewhere", File(dir, "notes/unclaimed.md").absolutePath),
        )
        val health = store.checkHealth(snapshot, tasks)
        assertTrue(health.unknownTasks.isEmpty())
        assertTrue(health.mismatchedTasks.isEmpty())
        // unclaimed.md is pointed at but never names t-8 back.
        assertEquals(listOf(MissingBacklink("notes/unclaimed.md", "Linked elsewhere")), health.missingBacklinks)
        // A task_ref nobody carries is unknown.
        File(dir, "notes/lost.md").writeText(
            "---\ntitle: Lost\narea: Personal\ntype: note\nupdated: 2026-10-29\ntask_ref: ghost\n---\n\nB.\n",
        )
        val health2 = store.checkHealth(store.loadSnapshot(), tasks)
        assertEquals(listOf(TaskNoteRef("notes/lost.md", "ghost")), health2.unknownTasks)
    }

    @Test fun frontmatterEdgeCases() {
        val unclosed = store.validateRecord("x.md", "---\ntitle: T\n", "notes")
        assertFalse(unclosed.valid)
        assertTrue(unclosed.errors.any { it.contains("never closed") })
        val colons = store.validateRecord(
            "x.md",
            "---\ntitle: Review: Part 1\narea: Personal\ntype: note\nupdated: 2026-10-29\nsource: https://example.com/a:b\n---\n\nB.\n",
            "notes",
        )
        assertTrue("errors: ${colons.errors}", colons.valid)
        val comment = store.validateRecord(
            "x.md",
            "---\ntitle: T\narea: Personal\ntype: note\nupdated: 2026-10-29\njust a comment line\n---\n\nB.\n",
            "notes",
        )
        assertTrue("errors: ${comment.errors}", comment.valid)
    }

    @Test fun blankFilesStayAndAreFlagged() {
        File(dir, "notes/empty.md").writeText("")
        val snapshot = store.loadSnapshot()
        assertTrue(snapshot.notes.any { it.name == "empty.md" })
        val health = store.checkHealth(snapshot)
        assertTrue(health.invalid.any { it.fileId == "notes/empty.md" })
    }

    @Test fun syncUserMergesWithoutClobbering() {
        store.syncUser(mapOf("name" to "R", "location" to "Home"))
        File(dir, "user.md").appendText("- nickname: Roo\n")
        store.syncUser(mapOf("name" to "", "location" to "Home", "occupation" to "Dev", "background" to ""))
        val text = File(dir, "user.md").readText()
        assertTrue(text.contains("- location: Home"))
        assertTrue(text.contains("- occupation: Dev"))
        assertTrue(text.contains("- nickname: Roo"))
        assertTrue(!text.contains("- name:"))
    }

    @Test fun seedsCreateTheWholeTree() {
        listOf("SOUL.md", "MEMORY.md", "user.md").forEach { assertTrue(File(dir, it).exists()) }
        listOf("areas", "people", "notes", "raw").forEach { assertTrue(File(dir, it).isDirectory) }
        assertEquals(4, File(dir, "areas").listFiles()!!.size)
        // Idempotent: a second run keeps user content.
        File(dir, "notes/custom.md").writeText(record("Custom"))
        store.ensureDefaults()
        assertTrue(File(dir, "notes/custom.md").exists())
        val snapshot = store.loadSnapshot()
        assertTrue(snapshot.available)
        assertEquals(4, snapshot.areas.size)
    }

    @Test fun importWritesValidFrontmatterAndUpsertUpdates() {
        val file = store.importPage("Oat flat white", "Morning drink.", "Personal", "note")
        assertTrue(file.exists())
        val validation = store.validateRecord(file.name, file.readText(), "notes")
        assertTrue("errors: ${validation.errors}", validation.valid)

        val same = store.upsertByTitle("oat FLAT white", "Evening decaf.", "Personal", "note")
        assertEquals(file.absolutePath, same.absolutePath)
        assertTrue(same.readText().contains("Evening decaf."))
        assertFalse(same.readText().contains("Morning drink."))
        val front = store.frontmatterOf(same.readText())!!
        assertEquals("2026-10-29", front["updated"])
    }

    @Test fun validationFindsEveryProblem() {
        val missing = store.validateRecord("x.md", "No fence here.", "notes")
        assertFalse(missing.valid)
        val bad = store.validateRecord(
            "x.md",
            "---\ntitle: T\narea: Nowhere\ntype: thing\nupdated: yesterday\ntags: [Bad_Tag]\nbogus: 1\n---\n\nB.\n",
            "notes",
        )
        assertFalse(bad.valid)
        val joined = bad.errors.joinToString("\n")
        assertTrue(joined.contains("unknown area"))
        assertTrue(joined.contains("unknown type"))
        assertTrue(joined.contains("updated"))
        assertTrue(joined.contains("Bad_Tag"))
        assertTrue(joined.contains("bogus"))
        val wrongDir = store.validateRecord("x.md", record("T", "Work", "person"), "notes")
        assertFalse(wrongDir.valid)
        assertTrue(wrongDir.errors.any { it.contains("notes/") })
        val goodPerson = store.validateRecord("x.md", record("Ann", "People", "person"), "people")
        assertTrue("errors: ${goodPerson.errors}", goodPerson.valid)
    }

    @Test fun linksResolveRelativeVaultAndBareName() {
        File(dir, "notes/trip.md").writeText(record("Trip") + "\nSee [[people/ann]] and [[ann]] and [[missing]] and [[trip]].\n")
        File(dir, "people/ann.md").writeText(record("Ann", "People", "person"))
        val snapshot = store.loadSnapshot()
        val graph = store.buildGraph(snapshot)
        val trip = "notes/trip.md"
        val targets = graph.edges.filter { it.fromId == trip }.map { it.toId }
        assertEquals(listOf("people/ann.md"), targets)
        val health = store.checkHealth(snapshot)
        assertEquals(listOf(DanglingLink(trip, "missing")), health.dangling)
        // Self-links and uniquely-resolved bare names never dangle.
        assertTrue(health.dangling.none { it.target == "trip" || it.target == "ann" })
    }

    @Test fun ambiguousBareNamesDrawNoEdgeAndDoNotDangle() {
        // MEMORY.md lives at the root: [[plan]] matches two nodes by bare name only.
        File(dir, "MEMORY.md").appendText("\nSee [[plan]].\n")
        File(dir, "notes/plan.md").writeText(record("Plan A"))
        File(dir, "people/plan.md").writeText(record("Plan B", "People", "person"))
        val snapshot = store.loadSnapshot()
        val graph = store.buildGraph(snapshot)
        val memoryEdges = graph.edges.filter { it.fromId == "MEMORY.md" }.map { it.toId }
        assertTrue(memoryEdges.none { it.contains("plan") })
        assertTrue(memoryEdges.isNotEmpty())
        val health = store.checkHealth(snapshot)
        assertTrue(health.dangling.none { it.target == "plan" })
    }

    @Test fun healthFindsUnlinkedSchemaAndTaskProblems() {
        File(dir, "notes/orphan.md").writeText(record("Orphan"))
        File(dir, "notes/broken.md").writeText("---\ntitle: Broken\n---\n\nB.\n")
        File(dir, "notes/tasked.md").writeText(
            "---\ntitle: Tasked\narea: Personal\ntype: note\nupdated: 2026-10-29\ntask_ref: t-1\n---\n\nB.\n",
        )
        val snapshot = store.loadSnapshot()
        val tasks = listOf(TaskLink("t-1", "Water plants", File(dir, "notes/gone.md").absolutePath))
        val health = store.checkHealth(snapshot, tasks)
        assertTrue(health.unlinked.contains("notes/orphan.md"))
        assertTrue(health.invalid.any { it.fileId == "notes/broken.md" })
        assertTrue(health.unknownTasks.isEmpty())
        assertEquals(1, health.staleTaskLinks.size)
        // t-1 points at a deleted note while tasked.md claims it: both directions flag.
        assertTrue(health.mismatchedTasks.any { it.fileId == "notes/tasked.md" })
        assertTrue(health.missingBacklinks.isEmpty())
    }

    @Test fun cappedReadsNeverReadTheWholeFile() {
        val big = File(dir, "notes/big.md")
        big.writeText("---\ntitle: Big\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\n" + "x".repeat(300_000))
        val capped = store.readCapped(big, 1000)!!
        assertTrue(capped.truncated)
        assertTrue(capped.text.length <= 1000)
        val snapshot = store.loadSnapshot()
        val note = snapshot.notes.first { it.name == "big.md" }
        assertTrue(note.truncated)
        assertTrue(note.content.length <= SecondBrainStore.VIEWER_FILE_CAP_CHARS)
    }

    @Test fun hugeFileIsUnscannedNotDangling() {
        // The link sits past the scan cap: unscanned, and not reported as dangling.
        val big = File(dir, "notes/big.md")
        big.writeText("---\ntitle: Big\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\n" + "x".repeat(210_000) + "\nSee [[nowhere]].\n")
        val snapshot = store.loadSnapshot()
        val health = store.checkHealth(snapshot)
        assertTrue(health.unscanned.contains("notes/big.md"))
        // Links past the scan cap are not reported as dangling.
        assertTrue(health.dangling.none { it.fromId == "notes/big.md" })
    }

    @Test fun forceLayoutIsDeterministicAndBounded() {
        val pairs = listOf(0 to 1, 1 to 2, 2 to 0, 2 to 3)
        val a = store.forceLayout(4, pairs, 60)
        val b = store.forceLayout(4, pairs, 60)
        assertEquals(a, b)
        assertEquals(4, a.size)
        assertTrue(a.all { p -> p.x.isFinite() && p.y.isFinite() && p.x in -500f..500f && p.y in -350f..350f })
        assertTrue(a.distinct().size > 1)
    }

    @Test fun forceLayoutHandlesEdges() {
        assertEquals(emptyList<androidx.compose.ui.geometry.Offset>(), store.forceLayout(0, emptyList(), 10))
        assertEquals(listOf(androidx.compose.ui.geometry.Offset.Zero), store.forceLayout(1, emptyList(), 10))
        // Out-of-range and self pairs are ignored, not crashed on.
        val pts = store.forceLayout(3, listOf(0 to 9, -1 to 2, 1 to 1, 0 to 1), 20)
        assertEquals(3, pts.size)
        assertTrue(pts.all { p -> p.x.isFinite() && p.y.isFinite() })
    }

    @Test fun confineToBrainPullsOutliersInside() {
        val (pts, depths) = store.confineToBrain(
            listOf(androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(2000f, 0f)),
            listOf(0f, 2000f),
        )
        assertEquals(2, pts.size)
        assertEquals(androidx.compose.ui.geometry.Offset(0f, 0f), pts[0])
        fun inside(p: androidx.compose.ui.geometry.Offset, z: Float): Boolean {
            val e = (p.x / 280f) * (p.x / 280f) + (p.y / 360f) * (p.y / 360f) + (z / 520f) * (z / 520f)
            return e <= 0.81f + 1e-3f
        }
        assertTrue(inside(pts[1], depths[1]))
        // Mismatched sizes pass through untouched.
        assertEquals(1, store.confineToBrain(listOf(androidx.compose.ui.geometry.Offset.Zero), emptyList()).first.size)
    }

    @Test fun filterSnapshotMatchesNamesPreviewsAndFullText() {
        File(dir, "notes/mum.md").writeText(record("Mum") + "\nLikes tulips.\n")
        File(dir, "notes/big.md").writeText(
            "---\ntitle: Big\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\n" +
                "x".repeat(9000) + "\nburied treasure\n",
        )
        val snapshot = store.loadSnapshot()
        assertTrue(store.filterSnapshot(snapshot, "").notes.size >= 2)
        assertEquals(listOf("Mum"), store.filterSnapshot(snapshot, "mum").notes.map { store.frontmatterOf(it.content)?.get("title") })
        // "treasure" sits past the viewer cap: found through the full-text re-read.
        assertEquals(1, store.filterSnapshot(snapshot, "treasure").notes.size)
        assertTrue(store.filterSnapshot(snapshot, "no such thing anywhere").isEmpty())
    }
}
