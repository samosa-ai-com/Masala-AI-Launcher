package com.samosaai.masala.secondbrain

import java.io.File
import java.time.LocalDate
import kotlin.io.extension

/**
 * File-based Second Brain under [root] (plan `docs/plans/second-brain-port.md`, P1):
 * SOUL.md (identity) + MEMORY.md (map) + user.md (profile), detail records in
 * areas/ + people/ + notes/ with YAML frontmatter, raw drops in raw/, `[[links]]`
 * between them. Reads are capped so a huge file can never stall a run; writes go
 * through [importPage]/[upsertByTitle] so every record carries valid frontmatter.
 *
 * Pure file logic except the [root] itself, so unit tests run on the JVM with a
 * temporary directory.
 */
class SecondBrainStore(
    private val root: File,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private fun dir(name: String) = File(root, name)
    private fun soulFile() = File(root, "SOUL.md")
    private fun memoryFile() = File(root, "MEMORY.md")
    private fun userFile() = File(root, "user.md")

    /** Creates the tree and seeds SOUL.md, MEMORY.md, area hubs and user.md when missing. */
    fun ensureDefaults() {
        synchronized(lock) {
            root.mkdirs()
            listOf("areas", "people", "notes", "raw").forEach { dir(it).mkdirs() }
            writeIfMissing(soulFile(), defaultSoul())
            writeIfMissing(memoryFile(), defaultMemoryIndex())
            ensureAreaHubs()
            if (!userFile().exists()) syncUser(emptyMap())
        }
    }

    /** Rewrites user.md, merging the incoming profile fields over the existing bullets. */
    fun syncUser(fields: Map<String, String>) {
        synchronized(lock) {
            val current = userFile().takeIf { it.exists() }?.let { readBullets(it) }.orEmpty()
            val merged = current.toMutableMap()
            KNOWN_PROFILE_KEYS.forEach { key ->
                val v = fields[key]?.trim().orEmpty()
                if (v.isEmpty()) merged.remove(key) else merged[key] = v
            }
            userFile().writeText(buildString {
                appendLine("---")
                appendLine("title: User")
                appendLine("area: Personal")
                appendLine("type: reference")
                appendLine("updated: ${today()}")
                appendLine("---")
                appendLine()
                appendLine("# User card")
                appendLine()
                appendLine("Synced from settings. Do not duplicate into notes.")
                appendLine()
                if (merged.isEmpty()) {
                    appendLine("- (nothing filled in yet)")
                } else {
                    for ((key, value) in merged) appendLine("- $key: $value")
                }
            })
        }
    }

    private fun readBullets(file: File): Map<String, String> = runCatching {
        bodyAfterFrontmatter(file.readText()).lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("- ")) return@mapNotNull null
            val kv = t.removePrefix("- ").split(":", limit = 2)
            if (kv.size != 2 || kv[0].isBlank() || kv[1].isBlank() || kv[1].trim().startsWith("(")) return@mapNotNull null
            kv[0].trim() to kv[1].trim()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun bodyAfterFrontmatter(content: String): String {
        val lines = content.lines()
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) return lines.drop(2 + end).joinToString("\n")
        }
        return content
    }

    /**
     * Adds a detail record, or updates the one with the same title (case-insensitive):
     * the approval flow replaces a routine rather than duplicating it.
     */
    fun upsertByTitle(title: String, markdown: String, area: String = "Personal", type: String = "note"): File {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title must not be blank" }
        synchronized(lock) {
            existingByTitle(cleanTitle)?.let { file ->
                val front = readFrontmatter(file)?.toMutableMap() ?: mutableMapOf()
                front["title"] = cleanTitle
                front["updated"] = today()
                if (front["area"].isNullOrBlank()) front["area"] = area
                if (front["type"].isNullOrBlank()) front["type"] = type
                file.writeText(renderRecord(front, markdown.trim()))
                return file
            }
            return importLocked(cleanTitle, markdown, area, type)
        }
    }

    /** Adds a new detail record under notes/ with valid frontmatter. Never overwrites. */
    fun importPage(title: String, markdown: String, area: String = "Personal", type: String = "note"): File {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title must not be blank" }
        synchronized(lock) {
            return importLocked(cleanTitle, markdown, area, type)
        }
    }

    private fun importLocked(title: String, markdown: String, area: String, type: String): File {
        var file = File(dir("notes"), slug(title) + ".md")
        var n = 2
        while (file.exists()) {
            file = File(dir("notes"), slug(title) + "-$n.md")
            n++
        }
        val front = linkedMapOf(
            "title" to title,
            "area" to area.trim().ifBlank { "Personal" },
            "type" to type.trim().ifBlank { "note" },
            "updated" to today(),
        )
        file.writeText(renderRecord(front, markdown.trim()))
        return file
    }

    private val lock = Any()

    fun loadSnapshot(): SecondBrainSnapshot {
        val soul = loadBrainFile(soulFile())
        val index = loadBrainFile(memoryFile())
        val user = loadBrainFile(userFile())
        return SecondBrainSnapshot(
            available = soul != null || index != null,
            soul = soul,
            memoryIndex = index,
            userCard = user,
            areas = listBrainFiles(dir("areas")),
            people = listBrainFiles(dir("people")),
            notes = listBrainFiles(dir("notes")),
            raw = listRawFiles(dir("raw")),
        )
    }

    fun buildGraph(snapshot: SecondBrainSnapshot): BrainGraph {
        val index = buildNodeIndex(snapshot)
        val texts = linkTexts(index)
        return BrainGraph(index.nodes, graphEdges(index, texts).sortedWith(compareBy({ it.fromId }, { it.toId })))
    }

    /**
     * Orphans, dangling links, schema violations, task-ref problems both ways, and
     * files too large for the link scan. Each markdown file is read once, bounded.
     */
    fun checkHealth(snapshot: SecondBrainSnapshot, taskLinks: List<TaskLink> = emptyList()): BrainHealth {
        val index = buildNodeIndex(snapshot)
        val texts = linkTexts(index)
        val linked = graphEdges(index, texts).flatMap { listOf(it.fromId, it.toId) }.toSet()
        val unlinked = index.nodes
            .map { it.id }
            .filter { it !in linked && it !in HEALTH_ROOT_IDS }
            .sorted()
        val dangling = danglingFor(index, texts)
        val byTaskId = taskLinks.associateBy { it.id }
        val vaultNotePaths = detailFiles(snapshot).mapNotNull { (file, _) -> canonicalInVault(file.absolutePath) }.toSet()
        val invalid = mutableListOf<RecordProblem>()
        val unknownTasks = mutableListOf<TaskNoteRef>()
        val mismatched = mutableListOf<TaskMismatch>()
        val unscanned = mutableListOf<String>()
        val noteRefs = mutableMapOf<String, Pair<String, String?>>()
        val knownAreas = knownAreaNames(snapshot)
        detailFiles(snapshot).forEach { (file, dirKind) ->
            val id = relativeMemoryId(file, "$dirKind/${file.name}")
            val full = texts[id] ?: fullText(file)
            if (full.length > LINK_SCAN_CAP_CHARS) unscanned.add(id)
            val check = validateRecord(file.name, full, dirKind, knownAreas)
            if (!check.valid) invalid.add(RecordProblem(id, check.errors))
            val ref = frontmatterOf(full)?.get("task_ref")?.takeIf { it.isNotBlank() }
            canonicalInVault(file.absolutePath)?.let { canonical ->
                noteRefs[canonical] = id to ref
            }
            ref?.let {
                val task = byTaskId[it]
                if (task == null) {
                    unknownTasks.add(TaskNoteRef(id, it))
                } else {
                    val linked = task.notePath?.let { p -> canonicalInVault(p) }
                    val here = canonicalInVault(file.absolutePath)
                    if (linked != here) mismatched.add(TaskMismatch(id, it, task.notePath))
                }
            }
        }
        val staleTaskLinks = mutableListOf<StaleTaskLink>()
        val missingBacklinks = mutableListOf<MissingBacklink>()
        taskLinks.forEach { task ->
            val path = task.notePath ?: return@forEach
            val canonical = canonicalInVault(path)
            if (canonical == null || canonical !in vaultNotePaths) {
                staleTaskLinks.add(StaleTaskLink(task.name, path))
            } else if (noteRefs[canonical]?.second != task.id) {
                val id = noteRefs[canonical]?.first ?: path
                missingBacklinks.add(MissingBacklink(id, task.name))
            }
        }
        return BrainHealth(unlinked, dangling, invalid, unknownTasks, mismatched, staleTaskLinks, unscanned, missingBacklinks)
    }

    /**
     * Validates a detail record's frontmatter and body. Pure string logic except the
     * area-vocabulary check against [knownAreas]. Returns every problem found.
     */
    fun validateRecord(
        fileName: String,
        content: String,
        dirKind: String? = null,
        knownAreas: Set<String> = STARTER_AREAS,
    ): RecordValidation {
        val errors = mutableListOf<String>()
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return RecordValidation(
                false,
                listOf(
                    "$fileName: missing frontmatter. Start the file with a --- block " +
                        "carrying ${RECORD_REQUIRED_KEYS.sorted().joinToString(", ")}.",
                ),
            )
        }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return RecordValidation(false, listOf("$fileName: frontmatter never closed with ---."))
        val front = mutableMapOf<String, String>()
        lines.drop(1).take(end).forEach { line ->
            if (':' !in line) return@forEach
            val key = line.substringBefore(':').trim()
            if (key.isNotEmpty()) front[key] = line.substringAfter(':').trim()
        }
        for (key in RECORD_REQUIRED_KEYS) {
            if (front[key].isNullOrBlank()) errors += "$fileName: missing required key '$key'."
        }
        for (key in front.keys) {
            if (key !in RECORD_ALLOWED_KEYS) errors += "$fileName: unknown key '$key'."
        }
        val type = front["type"].orEmpty()
        if (type.isNotBlank() && type !in RECORD_TYPES) {
            errors += "$fileName: unknown type '$type' (one of ${RECORD_TYPES.sorted().joinToString(", ")})."
        }
        if (dirKind != null && type.isNotBlank()) {
            val allowed = DIR_TYPES[dirKind]
            if (allowed != null && type !in allowed) {
                errors += "$fileName: type '$type' does not belong in $dirKind/ (one of ${allowed.sorted().joinToString(", ")})."
            }
        }
        val area = front["area"].orEmpty()
        if (area.isNotBlank() && area !in knownAreas) {
            errors += "$fileName: unknown area '$area'."
        }
        val updated = front["updated"].orEmpty()
        if (updated.isNotBlank() && !ISO_DATE_REGEX.matches(updated)) {
            errors += "$fileName: 'updated' must be an ISO date (yyyy-MM-dd)."
        }
        front["tags"]?.takeIf { it.isNotBlank() }?.let { tags ->
            val slugs = tags.trim().removePrefix("[").removeSuffix("]").split(Regex("[,\\s]+")).filter { it.isNotBlank() }
            slugs.filterNot { SLUG_REGEX.matches(it) }.forEach { bad ->
                errors += "$fileName: bad tag '$bad' (lowercase letters, digits and single hyphens only)."
            }
        }
        return RecordValidation(errors.isEmpty(), errors)
    }

    /** `[[links]]` in reading order; fenced code is not link text. Aliases resolve to the target. */
    fun parseWikiLinks(markdown: String): List<String> {
        val noFences = FENCE_REGEX.replace(markdown, "")
        return WIKI_LINK_REGEX.findAll(noFences).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Bounded read for display and startup: streams at most cap+1 chars. */
    fun readCapped(file: File, cap: Int): CappedText? {
        val text = readUpTo(file, cap) ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (text.length <= cap) CappedText(trimmed, false) else CappedText(trimmed.take(cap), true)
    }

    data class CappedText(val text: String, val truncated: Boolean)

    // ---- internals ----

    private fun writeIfMissing(file: File, content: String) {
        if (!file.exists()) file.writeText(content)
    }

    private fun slug(title: String): String {
        val slug = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(80).trim('-')
        return slug.ifBlank { "note" }
    }

    private fun readFrontmatter(file: File): Map<String, String>? =
        readUpTo(file, FRONTMATTER_SCAN_CHARS)?.let { frontmatterOf(it) }

    /** Vault-relative id ("notes/foo.md") for display and tool arguments. */
    fun relativeId(file: BrainFile): String = relativeMemoryId(file, file.name)

    /** Text previewable in the viewer: known text formats, or extensionless non-binary content. */
    fun isTextFile(name: String, content: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in TEXT_EXTENSIONS) return true
        if ('.' in name) return false
        return content.isNotBlank() && content.none { it == '\u0000' }
    }

    /** Whether the screen offers Edit/Delete for a vault id (detail dirs only, never roots). */
    fun isEditable(id: String): Boolean = id.substringBefore('/') in setOf("areas", "people", "notes")

    /** Rewrites one detail record, keeping its frontmatter (title and date refreshed). */
    fun writeEntry(id: String, title: String, markdown: String): File? = synchronized(lock) {
        val file = vaultFile(id) ?: return null
        if (!file.isFile || file.extension.lowercase() !in MARKDOWN_EXTENSIONS) return null
        if (file.parentFile?.name !in setOf("areas", "people", "notes")) return null
        val front = readFrontmatter(file)?.toMutableMap() ?: mutableMapOf()
        front["title"] = title.trim()
        front["updated"] = today()
        if (front["area"].isNullOrBlank()) front["area"] = "Personal"
        if (front["type"].isNullOrBlank()) front["type"] = "note"
        file.writeText(renderRecord(front, markdown.trim()))
        file
    }

    /** Deletes one vault file. Root files (SOUL.md, MEMORY.md, user.md) are refused. */
    fun deleteEntry(id: String): Boolean = synchronized(lock) {
        val file = vaultFile(id) ?: return false
        if (!file.isFile) return false
        if (file.parentFile == root && file.name in setOf("SOUL.md", "MEMORY.md", "user.md")) return false
        file.delete()
    }

    /** The Second Brain prompt block: capped SOUL.md + MEMORY.md plus entry titles. */
    fun buildPrompt(snapshot: SecondBrainSnapshot): SecondBrainPrompt {
        // Snapshot contents are already capped for display; the prompt takes the head of those.
        fun head(content: String?, cap: Int): Pair<String?, Boolean> {
            val trimmed = content?.trim()
            if (trimmed.isNullOrEmpty()) return null to false
            return if (trimmed.length <= cap) trimmed to false else trimmed.take(cap) to true
        }
        val (soul, soulTruncated) = head(snapshot.soul?.content, SOUL_CAP_CHARS)
        val (memory, memoryTruncated) = head(snapshot.memoryIndex?.content, MEMORY_CAP_CHARS)
        val titles = (snapshot.areas + snapshot.people + snapshot.notes).map { file ->
            frontmatterOf(file.content)?.get("title")?.takeIf { it.isNotBlank() }
                ?: file.name.removeSuffix(".md").removeSuffix(".markdown")
        }
        return SecondBrainPrompt(
            soul = soul,
            memory = memory,
            soulTruncated = soulTruncated,
            memoryTruncated = memoryTruncated,
            titles = titles,
        )
    }

    /** Resolves a vault-relative id to a file, refusing escapes from the vault. */
    private fun vaultFile(id: String): File? {
        if (id.isBlank() || ".." in id) return null
        val file = File(root, id)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        if (canonical != rootPath && !canonical.startsWith(rootPath.trimEnd('/') + '/')) return null
        return File(canonical)
    }

    private fun existingByTitle(title: String): File? {
        // User content only: seeded area hubs must never be overwritten by an upsert.
        for (dirKind in listOf("notes", "people")) {
            dir(dirKind).listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in MARKDOWN_EXTENSIONS }
                ?.forEach { file ->
                    val front = runCatching { readFrontmatter(file) }.getOrNull()
                    if (front?.get("title")?.equals(title, ignoreCase = true) == true) return file
                }
        }
        return null
    }

    private fun renderRecord(front: Map<String, String>, body: String): String = buildString {
        appendLine("---")
        for ((key, value) in front) appendLine("$key: $value")
        appendLine("---")
        appendLine()
        appendLine(body.trim())
    }

    fun frontmatterOf(content: String): Map<String, String>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        val front = linkedMapOf<String, String>()
        lines.drop(1).take(end).forEach { line ->
            if (':' !in line) return@forEach
            val key = line.substringBefore(':').trim()
            if (key.isNotEmpty()) front[key] = line.substringAfter(':').trim()
        }
        return front
    }

    private fun loadBrainFile(file: File): BrainFile? {
        return try {
            if (!file.exists() || !file.isFile) return null
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val size = file.length()
            if (ext.isNotEmpty() && ext !in TEXT_EXTENSIONS) {
                return BrainFile(file.name, "", false, size, file.absolutePath)
            }
            val text = readUpTo(file, VIEWER_FILE_CAP_CHARS) ?: return null
            if (text.length <= VIEWER_FILE_CAP_CHARS) {
                BrainFile(file.name, text, false, size, file.absolutePath)
            } else {
                BrainFile(file.name, text.take(VIEWER_FILE_CAP_CHARS), true, size, file.absolutePath)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun listBrainFiles(dir: File): List<BrainFile> {
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()
                ?.filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS }
                ?.sortedBy { it.name.lowercase() }
                ?.mapNotNull { loadBrainFile(it) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun listRawFiles(dir: File): List<BrainFile> {
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name.lowercase() }
                ?.mapNotNull { loadBrainFile(it) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readUpTo(file: File, cap: Int): String? {
        return try {
            file.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buf = CharArray(8192)
                var remaining = cap + 1
                while (remaining > 0) {
                    val n = reader.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    out.append(buf, 0, n)
                    remaining -= n
                }
                out.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun relativeMemoryId(file: BrainFile, fallback: String): String {
        val abs = file.absolutePath
        if (abs.isNotBlank()) {
            val rel = runCatching { File(abs).toRelativeString(root) }.getOrNull()
            if (rel != null && rel != abs && !rel.startsWith("..")) return rel.replace('\\', '/')
        }
        return fallback
    }

    /** Canonical path, resolving vault-relative task links against the vault root. */
    private fun canonicalInVault(path: String): String? = runCatching {
        val file = File(path).takeIf { it.isAbsolute } ?: File(root, path)
        file.canonicalPath
    }.getOrNull()

    private data class GraphSource(val id: String, val file: BrainFile)

    private data class NodeIndex(
        val nodes: List<BrainNode>,
        val sources: List<GraphSource>,
        val byFull: Map<String, String>,
    )

    private fun normGraphPath(path: String): String =
        path.lowercase().removeSuffix(".md").removeSuffix(".markdown")

    private fun buildNodeIndex(snapshot: SecondBrainSnapshot): NodeIndex {
        val nodes = mutableListOf<BrainNode>()
        val byFull = mutableMapOf<String, String>()
        val sources = mutableListOf<GraphSource>()
        fun addNode(id: String, label: String, kind: String) {
            nodes.add(BrainNode(id, label, kind))
            byFull.putIfAbsent(normGraphPath(id), id)
        }
        fun addSource(file: BrainFile?, fallbackId: String, label: String, kind: String) {
            if (file == null) return
            val id = relativeMemoryId(file, fallbackId)
            addNode(id, label, kind)
            sources.add(GraphSource(id, file))
        }
        addSource(snapshot.soul, "SOUL.md", "SOUL", "soul")
        addSource(snapshot.memoryIndex, "MEMORY.md", "MEMORY", "map")
        addSource(snapshot.userCard, "user.md", "User", "user")
        snapshot.areas.forEach { addSource(it, "areas/${it.name}", it.name.removeSuffix(".md"), "area") }
        snapshot.people.forEach { addSource(it, "people/${it.name}", it.name.removeSuffix(".md"), "person") }
        snapshot.notes.forEach { addSource(it, "notes/${it.name}", it.name.removeSuffix(".md"), "note") }
        snapshot.raw.forEach { addSource(it, "raw/${it.name}", it.name, "raw") }
        return NodeIndex(nodes, sources, byFull)
    }

    private fun fullText(file: BrainFile): String {
        val abs = file.absolutePath
        if (abs.isBlank()) return file.content
        return readUpTo(File(abs), LINK_SCAN_CAP_CHARS) ?: file.content
    }

    private fun linkTexts(index: NodeIndex): Map<String, String> = index.sources
        .filter { it.file.isMarkdown }
        .associate { it.id to fullText(it.file) }

    private fun graphCandidates(raw: String, sourceId: String): List<String> {
        val sourceDir = sourceId.substringBeforeLast('/', "")
        return buildList {
            if (sourceDir.isNotEmpty()) {
                add("$sourceDir/$raw")
                add("$sourceDir/$raw.md")
                add("$sourceDir/$raw.markdown")
            }
            add(raw)
            add("$raw.md")
            add("$raw.markdown")
        }
    }

    private fun resolveGraphLink(target: String, sourceId: String, index: NodeIndex): String? {
        val raw = target.trim()
        if (raw.isEmpty()) return null
        graphCandidates(raw, sourceId).forEach { candidate ->
            index.byFull[normGraphPath(candidate)]?.let { return it }
        }
        // Bare names only match without a path: [[notes/missing]] must never resolve to people/missing.md.
        if ('/' in raw) return null
        val base = normGraphPath(raw)
        return index.nodes
            .filter { normGraphPath(it.id).substringAfterLast('/') == base }
            .map { it.id }
            .singleOrNull()
    }

    private fun hasAmbiguousMatches(target: String, sourceId: String, index: NodeIndex): Boolean {
        val raw = target.trim()
        if (raw.isEmpty() || '/' in raw) return false
        if (graphCandidates(raw, sourceId).any { index.byFull.containsKey(normGraphPath(it)) }) return false
        val base = normGraphPath(raw)
        return index.nodes.count { normGraphPath(it.id).substringAfterLast('/') == base } > 1
    }

    private fun graphEdges(index: NodeIndex, texts: Map<String, String>): Set<BrainEdge> {
        val edges = mutableSetOf<BrainEdge>()
        index.sources.forEach { source ->
            val text = texts[source.id] ?: return@forEach
            parseWikiLinks(text).forEach { target ->
                val toId = resolveGraphLink(target, source.id, index) ?: return@forEach
                if (toId != source.id) edges += BrainEdge(source.id, toId)
            }
        }
        return edges
    }

    private fun danglingFor(index: NodeIndex, texts: Map<String, String>): List<DanglingLink> {
        val out = mutableListOf<DanglingLink>()
        index.sources.forEach { source ->
            val text = texts[source.id] ?: return@forEach
            parseWikiLinks(text).forEach { target ->
                if (resolveGraphLink(target, source.id, index) == null &&
                    !hasAmbiguousMatches(target, source.id, index)
                ) {
                    out += DanglingLink(source.id, target)
                }
            }
        }
        return out.sortedWith(compareBy({ it.fromId }, { it.target }))
    }

    private fun detailFiles(snapshot: SecondBrainSnapshot): List<Pair<BrainFile, String>> =
        snapshot.areas.map { it to "areas" } +
            snapshot.people.map { it to "people" } +
            snapshot.notes.map { it to "notes" }

    private fun knownAreaNames(snapshot: SecondBrainSnapshot): Set<String> =
        STARTER_AREAS + snapshot.areas.mapNotNull { frontmatterOf(it.content)?.get("title") } +
            snapshot.areas.map { it.name.removeSuffix(".md").removeSuffix(".markdown") }

    private fun defaultSoul(): String = buildString {
        appendLine("# SOUL")
        appendLine()
        appendLine("Who Masala is for this user. Edit this file to set persona and behavior.")
        appendLine("It is read at the start of every run that uses memory, before the first reply.")
        appendLine()
        appendLine("## Identity")
        appendLine()
        appendLine("- Assistant name: Masala")
        appendLine("- Tone: helpful, concise")
    }

    private fun defaultMemoryIndex(): String = buildString {
        appendLine("# MEMORY")
        appendLine()
        appendLine("Master map. Facts live in detail files, not here.")
        appendLine("Detail files are opened by their vault-relative id (areas/work.md).")
        appendLine()
        appendLine("## Areas")
        appendLine()
        appendLine("- People: [[areas/people.md]]")
        appendLine("- Work: [[areas/work.md]]")
        appendLine("- Personal: [[areas/personal.md]]")
        appendLine("- Health: [[areas/health.md]]")
        appendLine()
        appendLine("## Links")
        appendLine()
        appendLine("- User card: user.md")
        appendLine("- Identity: SOUL.md")
        appendLine("- Notes: notes/")
    }

    private fun ensureAreaHubs() {
        fun hub(title: String, area: String, body: String): String = buildString {
            appendLine("---")
            appendLine("title: $title")
            appendLine("area: $area")
            appendLine("type: area")
            appendLine("tags: []")
            appendLine("updated: ${today()}")
            appendLine("---")
            appendLine()
            append(body)
        }
        val areas = mapOf(
            "people.md" to hub(
                "People", "People",
                "# People\n\nContact cards live in people/.\n",
            ),
            "work.md" to hub(
                "Work", "Work",
                "# Work\n\nProjects and work threads. Detail notes live in notes/.\n",
            ),
            "personal.md" to hub(
                "Personal", "Personal",
                "# Personal\n\nHome, routine, finance admin. Detail notes live in notes/.\n",
            ),
            "health.md" to hub(
                "Health", "Health",
                "# Health\n\nHealth context the user chooses to keep. Never store diagnoses as facts.\n",
            ),
        )
        areas.forEach { (name, body) ->
            writeIfMissing(File(dir("areas"), name), body)
        }
    }

    companion object {
        const val SOUL_CAP_CHARS = 1500
        const val MEMORY_CAP_CHARS = 1500
        const val VIEWER_FILE_CAP_CHARS = 8000
        const val LINK_SCAN_CAP_CHARS = 200_000
        /** Frontmatter lives at the top; this is plenty to hold it. */
        const val FRONTMATTER_SCAN_CHARS = 4096

        val RECORD_REQUIRED_KEYS = setOf("title", "area", "type", "updated")
        val RECORD_ALLOWED_KEYS = setOf(
            "title", "area", "type", "tags", "updated",
            "source", "source_file", "stage", "project", "summary",
            "next_action", "task_ref",
        )
        val RECORD_TYPES = setOf("person", "note", "meeting", "decision", "reference", "area", "contact-card")
        val STARTER_AREAS = setOf("People", "Work", "Personal", "Health")

        /** Profile fields the editor writes; anything else in user.md is left alone. */
        val KNOWN_PROFILE_KEYS = setOf("name", "location", "occupation", "background")

        /** Which record types live in which directory. */
        val DIR_TYPES = mapOf(
            "areas" to setOf("area"),
            "people" to setOf("person", "contact-card"),
            "notes" to setOf("note", "meeting", "decision", "reference"),
        )

        /** Root files are navigation hubs, not content: never reported as unlinked. */
        val HEALTH_ROOT_IDS = setOf("SOUL.md", "MEMORY.md", "user.md")

        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "")
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "yaml", "yml", "json", "csv", "log")
        private val WIKI_LINK_REGEX = Regex("\\[\\[([^\\]|\\r\\n]+)(?:\\|[^\\]\\r\\n]*)?]]")
        private val FENCE_REGEX = Regex("(?s)(```|~~~).*?(\\1|\\Z)")
        private val SLUG_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val ISO_DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
