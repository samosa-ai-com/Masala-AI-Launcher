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

    // ---- S1 record rules ----

    @Test fun slugsCompareTitlesAndFileNames() {
        assertEquals("asha-sharma", store.slugify("Asha Sharma"))
        assertEquals("asha-sharma", store.slugify("asha-sharma.md"))
        assertEquals("asha-sharma", store.slugify("  Asha--Sharma  "))
        assertEquals("v1-2-plan", store.slugify("v1.2 plan"))
    }

    @Test fun duplicatesMatchByFileNameOrTitle() {
        File(dir, "people/asha-sharma.md").writeText(record("Asha Sharma", area = "People", type = "person"))
        File(dir, "notes/physio.md").writeText(record("Asha's physio plan"))
        val snapshot = store.loadSnapshot()
        assertEquals(listOf("people/asha-sharma.md"), store.findPossibleDuplicates("Asha Sharma", snapshot))
        assertEquals(listOf("people/asha-sharma.md"), store.findPossibleDuplicates("notes/asha-sharma.md", snapshot))
        assertEquals(listOf("notes/physio.md"), store.findPossibleDuplicates("physio", snapshot))
        assertTrue(store.findPossibleDuplicates("Ravi", snapshot).isEmpty())
    }

    @Test fun datedNotesNeedARealDate() {
        assertTrue(store.isDatedNote("2026-10-29-standup.md"))
        assertTrue(store.isDatedNote("notes/2026-10-29.md"))
        assertFalse(store.isDatedNote("2026-13-40-standup.md"))
        assertFalse(store.isDatedNote("standup-2026-10-29.md"))
    }

    @Test fun recordDirKindOnlyForMarkdownInDetailDirs() {
        assertEquals("people", store.recordDirKind("people/asha.md"))
        assertEquals("notes", store.recordDirKind("notes/plan.markdown"))
        assertEquals(null, store.recordDirKind("raw/memo.md"))
        assertEquals(null, store.recordDirKind("notes/memo.pdf"))
        assertEquals(null, store.recordDirKind("notes/sub/plan.md"))
        assertEquals(null, store.recordDirKind("MEMORY.md"))
    }

    @Test fun tagsKeepQuotedCommas() {
        assertEquals(listOf("k99", "cerebral-palsy"), store.parseTags("[k99, cerebral-palsy]"))
        assertEquals(listOf("bad,tag", "ok"), store.parseTags("[\"bad,tag\", ok]"))
        assertEquals(listOf("solo"), store.parseTags("solo"))
        val check = store.validateRecord("x.md", record("X", extra = "tags: [\"bad,tag\", ok]\n"))
        assertEquals(listOf("x.md: bad tag 'bad,tag' (lowercase letters, digits and single hyphens only)."), check.errors)
    }

    @Test fun validatorCatchesStricterProblems() {
        fun errors(text: String) = store.validateRecord("x.md", text, "notes").errors
        assertTrue(errors("---\ntitle: X\narea: Personal\ntype: note\nupdated: 2026-02-30\n---\n\nBody.\n").single().contains("ISO date"))
        assertTrue(errors("---\ntitle: X\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\n").single().contains("empty body"))
        assertTrue(errors(record("X", extra = "source:\n")).single().contains("'source' is present but blank"))
        assertTrue(errors(record("X", extra = "title: Y\n")).single().contains("duplicate key 'title'"))
        assertTrue(errors("---\ntitle: \"\"\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\nBody.\n").single().contains("'title'"))
        // A block scalar's indented lines are part of its value, not new keys.
        assertTrue(errors(record("X", extra = "summary: |\n  line one\n  line: two\n")).isEmpty())
    }

    @Test fun ephemeralPaths() {
        assertTrue(store.isEphemeralPath("/tmp/x.pdf"))
        assertTrue(store.isEphemeralPath("/data/user/0/app/cache/x.pdf"))
        assertTrue(store.isEphemeralPath("/sdcard/Download/x.tmp"))
        assertFalse(store.isEphemeralPath("/sdcard/Documents/x.pdf"))
    }

    @Test fun staleRawIsListedNotDeleted() {
        val old = File(dir, "raw/old.mp3").apply { writeText("a"); setLastModified(1_000L) }
        File(dir, "raw/new.mp3").writeText("b")
        assertEquals(listOf("raw/old.mp3"), store.listStaleRaw(nowMillis = System.currentTimeMillis()))
        assertTrue(old.exists())
    }

    @Test fun legacyHubsGainFrontmatterOnce() {
        val hub = File(dir, "areas/garden.md").apply { writeText("# Garden\n\nTomatoes.\n") }
        store.ensureDefaults()
        val once = hub.readText()
        assertTrue(once.startsWith("---\ntitle: Garden\narea: Garden\ntype: area\n"))
        assertTrue(once.endsWith("# Garden\n\nTomatoes.\n"))
        store.ensureDefaults()
        assertEquals(once, hub.readText())
        assertTrue(store.validateRecord("garden.md", once, "areas", setOf("Garden")).valid)
    }

    // ---- S2 gated writes ----

    private fun person(title: String, body: String = "Physio.") =
        "---\ntitle: $title\narea: Health\ntype: person\nupdated: 2026-10-29\n---\n\n$body\n"

    @Test fun writeCreatesAValidRecordAndRejectsWithReasons() {
        val ok = store.writeRecord("people/asha-sharma.md", person("Asha Sharma"))
        assertTrue(ok.errors.toString(), ok.ok)
        assertTrue(ok.created)
        assertTrue(File(dir, "people/asha-sharma.md").isFile)
        val bad = store.writeRecord("people/ravi.md", "---\ntitle: Ravi\ntype: note\nupdated: 2026-10-29\n---\n\nX.\n")
        assertFalse(bad.ok)
        assertTrue(bad.errors.any { "missing required key 'area'" in it })
        assertTrue(bad.errors.any { "does not belong in people/" in it })
        assertFalse(File(dir, "people/ravi.md").exists())
    }

    @Test fun writeRefusesTargetsOutsideRecords() {
        for (path in listOf("user.md", "raw/a.md", "notes/sub/a.md", "../escape.md", "notes/a.txt", "stray.md")) {
            assertFalse(path, store.writeRecord(path, record("A")).ok)
        }
        assertFalse(store.writeRecord("notes/bin.md", record("Bin") + "\u0000").ok)
    }

    @Test fun rootFilesKeepTheirCaps() {
        assertTrue(store.writeRecord("MEMORY.md", "# MEMORY\n\n- [[areas/work.md]]\n").ok)
        val tooLong = store.writeRecord("SOUL.md", "x".repeat(SecondBrainStore.SOUL_CAP_CHARS + 1))
        assertTrue(tooLong.errors.single().contains("under 1500"))
    }

    @Test fun datedNotesOnlyGrow() {
        val path = "notes/2026-10-29-standup.md"
        assertTrue(store.writeRecord(path, record("Standup", type = "meeting")).ok)
        val replace = store.writeRecord(path, record("Standup", type = "meeting"))
        assertTrue(replace.errors.single().contains("dated note"))
        assertTrue(store.writeRecord(path, "Superseded by [[notes/2026-10-30-standup.md]]\n", append = true).ok)
        assertTrue(File(dir, path).readText().endsWith("Body.\nSuperseded by [[notes/2026-10-30-standup.md]]\n"))
    }

    @Test fun duplicatesAreRefusedUnlessAllowed() {
        assertTrue(store.writeRecord("people/asha-sharma.md", person("Asha Sharma")).ok)
        val byName = store.writeRecord("notes/asha-sharma.md", record("Asha notes"))
        assertTrue(byName.errors.single().contains("people/asha-sharma.md already covers this"))
        val byTitle = store.writeRecord("people/asha.md", person("Asha Sharma"))
        assertTrue(byTitle.errors.single().contains("people/asha-sharma.md"))
        assertTrue(store.writeRecord("people/asha.md", person("Asha Sharma"), allowDuplicate = true).ok)
        // Rewriting the same file is an update, not a duplicate.
        assertTrue(store.writeRecord("people/asha-sharma.md", person("Asha Sharma", "Wednesdays.")).ok)
    }

    @Test fun newAreasBootstrapFromTheirHub() {
        val hub = "---\ntitle: Garden\narea: Garden\ntype: area\nupdated: 2026-10-29\n---\n\n# Garden\n"
        assertFalse(store.writeRecord("notes/tomatoes.md", record("Tomatoes", area = "Garden")).ok)
        assertTrue(store.writeRecord("areas/garden.md", hub).ok)
        assertTrue(store.writeRecord("notes/tomatoes.md", record("Tomatoes", area = "Garden")).ok)
        assertTrue(store.writeRecord("notes/basil.md", record("Basil", area = "garden")).ok)
    }

    @Test fun editReplacesExactTextAndKeepsTheSchema() {
        store.writeRecord("people/asha-sharma.md", person("Asha Sharma", "Tuesdays 6pm. Tuesdays only."))
        assertTrue(store.editRecord("people/asha-sharma.md", "nope", "x").errors.single().contains("not found"))
        assertTrue(store.editRecord("people/asha-sharma.md", "Tuesdays", "Wednesdays").errors.single().contains("2 times"))
        assertTrue(store.editRecord("people/asha-sharma.md", "Tuesdays", "Wednesdays", replaceAll = true).ok)
        assertTrue(File(dir, "people/asha-sharma.md").readText().contains("Wednesdays 6pm. Wednesdays only."))
        val broken = store.editRecord("people/asha-sharma.md", "type: person", "type: banana")
        assertFalse(broken.ok)
        assertTrue(File(dir, "people/asha-sharma.md").readText().contains("type: person"))
        assertFalse(store.editRecord("people/missing.md", "a", "b").ok)
    }

    @Test fun deleteRefusesRootsAndAllowsRaw() {
        assertFalse(store.deleteRecord("MEMORY.md").ok)
        File(dir, "raw/memo.mp3").writeText("a")
        assertTrue(store.deleteRecord("raw/memo.mp3").ok)
        assertFalse(store.deleteRecord("raw/memo.mp3").ok)
    }

    @Test fun undoRestoresCreateEditAndDelete() {
        val path = "people/asha-sharma.md"
        val created = store.writeRecord(path, person("Asha Sharma", "Tuesdays."))
        val edited = store.editRecord(path, "Tuesdays", "Wednesdays")
        val deleted = store.deleteRecord(path)
        assertEquals(path, store.undoTarget(deleted.undoId!!))
        assertTrue(store.undo(deleted.undoId!!))
        assertTrue(File(dir, path).readText().contains("Wednesdays."))
        assertTrue(store.undo(edited.undoId!!))
        assertTrue(File(dir, path).readText().contains("Tuesdays."))
        assertTrue(store.undo(created.undoId!!))
        assertFalse(File(dir, path).exists())
        assertFalse(store.undo(created.undoId!!))
        assertFalse(store.undo("../../etc"))
        // The journal is invisible to the vault.
        assertTrue(store.checkHealth(store.loadSnapshot()).isClean)
    }

    @Test fun journalKeepsTheNewestFifty() {
        repeat(SecondBrainStore.JOURNAL_KEEP + 5) { i ->
            store.writeRecord("notes/n.md", record("N", extra = "summary: v$i\n"), allowDuplicate = true)
        }
        assertEquals(SecondBrainStore.JOURNAL_KEEP, File(dir, ".history").listFiles { f -> f.name.endsWith(".path") }!!.size)
    }

    @Test fun readTextStaysInTheVault() {
        store.writeRecord("people/asha-sharma.md", person("Asha Sharma"))
        assertTrue(store.readText("people/asha-sharma.md")!!.text.contains("Physio."))
        assertTrue(store.readText("/MEMORY.md")!!.text.startsWith("# MEMORY"))
        assertEquals(null, store.readText("../secret.md"))
        assertEquals(null, store.readText(".history/x.path"))
        assertEquals(null, store.readText("people/missing.md"))
        File(dir, "raw/song.mp3").writeText("ID3")
        assertEquals(null, store.readText("raw/song.mp3"))
        File(dir, "notes/long.md").writeText(record("Long") + "x".repeat(SecondBrainStore.VIEWER_FILE_CAP_CHARS))
        assertTrue(store.readText("notes/long.md")!!.truncated)
    }

    @Test fun searchFindsLinesAndNames() {
        store.writeRecord("people/asha-sharma.md", person("Asha Sharma", "Physio on Tuesdays."))
        File(dir, "raw/asha-voice.m4a").writeText("bytes")
        val hits = store.search("ASHA")
        assertTrue(hits.toString(), hits.contains("people/asha-sharma.md:2: title: Asha Sharma"))
        assertTrue(hits.toString(), hits.contains("raw/asha-voice.m4a: (name matches)"))
        assertEquals(listOf("people/asha-sharma.md:8: Physio on Tuesdays."), store.search("tuesdays"))
        assertEquals(1, store.search("a", limit = 1).size)
        assertTrue(store.search("  ").isEmpty())
    }

    // ---- S4 raw drops ----

    @Test fun rawFilingCopiesDeclashesAndUndoes() {
        val first = store.fileRaw("Stand up/2026-10-29 memo.m4a") { "abc".byteInputStream() }
        assertEquals("raw/2026-10-29-memo.m4a", first.id)
        val second = store.fileRaw("2026-10-29 memo.m4a") { "def".byteInputStream() }
        assertEquals("raw/2026-10-29-memo-2.m4a", second.id)
        assertEquals("abc", File(dir, "raw/2026-10-29-memo.m4a").readText())
        assertTrue(store.undo(second.undoId!!))
        assertFalse(File(dir, "raw/2026-10-29-memo-2.m4a").exists())
        assertFalse(store.writeRecord("raw/2026-10-29-memo.m4a", "x").ok)
    }

    @Test fun rawFilingOverTheCapLeavesNothing() {
        val big = store.fileRaw("big.bin", maxBytes = 10) { ByteArray(64).inputStream() }
        assertFalse(big.ok)
        assertTrue(big.errors.single().contains("not filed"))
        assertTrue(File(dir, "raw").listFiles()!!.isEmpty())
        val broken = store.fileRaw("broken.pdf") { throw java.io.IOException("gone") }
        assertFalse(broken.ok)
        assertFalse(File(dir, "raw/broken.pdf").exists())
        assertFalse(store.fileRaw("///") { "x".byteInputStream() }.ok)
    }

    @Test fun staleRawShowsInHealth() {
        File(dir, "raw/old.mp3").apply { writeText("a"); setLastModified(1_000L) }
        val health = store.checkHealth(store.loadSnapshot())
        assertEquals(listOf("raw/old.mp3"), health.staleRaw)
        assertFalse(health.isClean)
    }
}
