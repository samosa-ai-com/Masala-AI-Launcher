package com.samosaai.masala.secondbrain

import androidx.compose.ui.geometry.Offset
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
            migrateLegacyHubs()
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
        return BrainHealth(unlinked, dangling, invalid, unknownTasks, mismatched, staleTaskLinks, unscanned, missingBacklinks, listStaleRaw())
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
        var inBlock = false
        lines.drop(1).take(end).forEach { raw ->
            // Indented or blank lines after `key: |` belong to that value, not to new keys.
            if (inBlock && (raw.isBlank() || raw.startsWith(" ") || raw.startsWith("\t"))) return@forEach
            inBlock = false
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            // A line without a colon is free text and is skipped, as before.
            val key = line.substringBefore(':', "").trim()
            if (key.isEmpty()) return@forEach
            if (key in front) errors += "$fileName: duplicate key '$key'."
            val value = unquote(line.substringAfter(':').trim())
            front[key] = value
            if (value == "|" || value == ">") inBlock = true
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
        if (updated.isNotBlank() && !(ISO_DATE_REGEX.matches(updated) && runCatching { LocalDate.parse(updated) }.isSuccess)) {
            errors += "$fileName: 'updated' must be an ISO date (yyyy-MM-dd), got '$updated'."
        }
        front["tags"]?.takeIf { it.isNotBlank() }?.let { tags ->
            parseTags(tags).filterNot { SLUG_REGEX.matches(it) }.forEach { bad ->
                errors += "$fileName: bad tag '$bad' (lowercase letters, digits and single hyphens only)."
            }
        }
        for (key in OPTIONAL_NON_BLANK_KEYS) {
            if (key in front && front[key].isNullOrBlank()) errors += "$fileName: '$key' is present but blank."
        }
        if (lines.drop(end + 2).joinToString("\n").isBlank()) {
            errors += "$fileName: empty body. Facts live in the body, not the frontmatter."
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

    // ---- record rules (plan `second-brain-robust.md` S1) ----

    /**
     * Duplicate guard: "Asha Sharma", "asha-sharma.md" and "Asha-Sharma" all compare equal. A real
     * record extension is dropped first; dots inside titles stay.
     */
    fun slugify(text: String): String {
        val lower = text.trim().lowercase()
        val base = when {
            lower.endsWith(".markdown") -> lower.dropLast(".markdown".length)
            lower.endsWith(".md") -> lower.dropLast(".md".length)
            else -> lower
        }
        return base.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-')
    }

    /** Ids of records whose file name or frontmatter title has the same slug as [title]. */
    fun findPossibleDuplicates(title: String, snapshot: SecondBrainSnapshot): List<String> {
        val slug = slugify(title.substringAfterLast('/'))
        if (slug.isEmpty()) return emptyList()
        return detailFiles(snapshot).mapNotNull { (file, dirKind) ->
            val id = relativeMemoryId(file, "$dirKind/${file.name}")
            val titleSlug = frontmatterOf(file.content)?.get("title")?.let { slugify(unquote(it)) }
            if (slug == slugify(file.name) || slug == titleSlug) id else null
        }.distinct().sorted()
    }

    /** History rule: a name starting with a real ISO date describes what was true then. */
    fun isDatedNote(fileName: String): Boolean {
        val prefix = DATED_NAME_REGEX.find(fileName.substringAfterLast('/'))?.value ?: return false
        return runCatching { LocalDate.parse(prefix) }.isSuccess
    }

    /** `areas`, `people` or `notes` for a vault-relative Markdown record path, else null. */
    fun recordDirKind(path: String): String? {
        val parts = path.trim().trimStart('/').split('/')
        if (parts.size != 2 || parts[1].isBlank()) return null
        if (parts[0] !in DIR_TYPES.keys) return null
        val ext = parts[1].substringAfterLast('.', "").lowercase()
        return if (ext == "md" || ext == "markdown") parts[0] else null
    }

    /** A `tags:` value: an inline `[a, b]` list (commas inside quotes stay) or one slug. */
    fun parseTags(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!(trimmed.startsWith("[") && trimmed.endsWith("]"))) return listOf(unquote(trimmed)).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        fun flush() {
            unquote(cur.toString().trim()).takeIf { it.isNotEmpty() }?.let(out::add)
            cur.clear()
        }
        for (ch in trimmed.substring(1, trimmed.length - 1)) {
            when {
                quote != null && ch == quote -> { cur.append(ch); quote = null }
                quote == null && (ch == '"' || ch == '\'') -> { cur.append(ch); quote = ch }
                quote == null && ch == ',' -> flush()
                else -> cur.append(ch)
            }
        }
        flush()
        return out
    }

    /** Staging paths that can vanish (temp dirs, caches, `.tmp`): a raw drop from there is always copied. */
    fun isEphemeralPath(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        return lower == "/tmp" || lower.startsWith("/tmp/") || "/tmp/" in lower || "/cache/" in lower ||
            "/temp/" in lower || lower.endsWith(".tmp") || lower.endsWith(".temp")
    }

    /** Raw ids older than [maxAgeDays], to propose for cleanup. Lists only; never deletes. */
    fun listStaleRaw(maxAgeDays: Int = RAW_STALE_DAYS, nowMillis: Long = System.currentTimeMillis()): List<String> {
        val cutoff = nowMillis - maxAgeDays * 24L * 60 * 60 * 1000
        return dir("raw").listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.map { "raw/${it.name}" }
            ?.sortedBy { it.lowercase() }
            .orEmpty()
    }

    /** Hubs written before the record schema gain frontmatter in place; bodies stay as they are. */
    private fun migrateLegacyHubs() {
        dir("areas").listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "md" }
            ?.forEach { hub ->
                runCatching {
                    val text = hub.readText()
                    if (text.lineSequence().firstOrNull()?.trim() == "---") return@runCatching
                    val base = hub.nameWithoutExtension
                    val area = STARTER_AREAS.firstOrNull { it.equals(base, ignoreCase = true) }
                        ?: base.replaceFirstChar { it.uppercase() }
                    hub.writeText("---\ntitle: $area\narea: $area\ntype: area\ntags: []\nupdated: ${today()}\n---\n\n$text")
                }
            }
    }

    /** Strips one layer of matching quotes, so `title: ""` counts as blank. */
    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        return if ((first == '"' || first == '\'') && value.last() == first) value.substring(1, value.length - 1) else value
    }

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
        journal(id, file)
        file.writeText(renderRecord(front, markdown.trim()))
        file
    }

    /** Deletes one vault file. Root files (SOUL.md, MEMORY.md, user.md) are refused. */
    fun deleteEntry(id: String): Boolean = synchronized(lock) {
        val file = vaultFile(id) ?: return false
        if (!file.isFile) return false
        if (file.parentFile == root && file.name in ROOT_FILES) return false
        journal(id, file)
        file.delete()
    }

    // ---- gated writes (plan `second-brain-robust.md` S2) ----

    /** What a gated write did: the vault id written, or every reason it was refused. */
    data class WriteOutcome(
        val id: String,
        val errors: List<String> = emptyList(),
        val created: Boolean = false,
        /** Restores the file as it was before this change; null when nothing was written. */
        val undoId: String? = null,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /**
     * Writes a whole record at a vault path (or appends to it). Records in areas/, people/ and notes/
     * must pass [validateRecord]; MEMORY.md and SOUL.md stay under their startup caps; user.md and raw/
     * are never written here. An existing dated note can only be appended to, and a new file whose
     * name or title matches an existing record is refused unless [allowDuplicate].
     */
    fun writeRecord(path: String, content: String, append: Boolean = false, allowDuplicate: Boolean = false): WriteOutcome =
        synchronized(lock) {
            val id = normalizeId(path)
            refuseTarget(id)?.let { return WriteOutcome(id, listOf(it)) }
            if ('\u0000' in content) return WriteOutcome(id, listOf("$id: memory files are text; refusing binary content."))
            val file = vaultFile(id) ?: return WriteOutcome(id, listOf("$id: not a path inside the Second Brain."))
            val exists = file.isFile
            if (exists && !append && isDatedNote(id)) {
                return WriteOutcome(
                    id,
                    listOf(
                        "$id is a dated note: it records what was true then and is never replaced. Write the new " +
                            "decision as a new note with a 'Superseded by [[$id]]'-style link both ways, or append.",
                    ),
                )
            }
            val effective = if (append && exists) {
                val old = file.readText()
                if (old.isEmpty() || old.endsWith("\n")) old + content else old + "\n" + content
            } else {
                content
            }
            val dirKind = recordDirKind(id)
            val snapshot = loadSnapshot()
            if (!exists && dirKind != null && !allowDuplicate) {
                val title = frontmatterOf(effective)?.get("title")?.let(::unquote).orEmpty()
                val dups = (findPossibleDuplicates(id, snapshot) +
                    title.takeIf { it.isNotBlank() }?.let { findPossibleDuplicates(it, snapshot) }.orEmpty()).distinct().sorted()
                if (dups.isNotEmpty()) {
                    return WriteOutcome(
                        id,
                        listOf(
                            "$id: ${dups.joinToString(", ")} already covers this. Update that file instead " +
                                "(one fact in one place), or pass allow_duplicate if it really is something else.",
                        ),
                    )
                }
            }
            checkContent(id, dirKind, effective, snapshot).takeIf { it.isNotEmpty() }?.let { return WriteOutcome(id, it) }
            val undoId = journal(id, file)
            file.parentFile?.mkdirs()
            file.writeText(effective)
            WriteOutcome(id, created = !exists, undoId = undoId)
        }

    /** Replaces exact text in a vault file; the result must still pass the same checks as a write. */
    fun editRecord(path: String, oldText: String, newText: String, replaceAll: Boolean = false): WriteOutcome =
        synchronized(lock) {
            val id = normalizeId(path)
            refuseTarget(id)?.let { return WriteOutcome(id, listOf(it)) }
            val file = vaultFile(id)?.takeIf { it.isFile } ?: return WriteOutcome(id, listOf("$id does not exist."))
            if (oldText.isEmpty()) return WriteOutcome(id, listOf("$id: old_text is empty."))
            val text = file.readText()
            var count = 0
            var at = text.indexOf(oldText)
            while (at >= 0) {
                count++
                at = text.indexOf(oldText, at + oldText.length)
            }
            when {
                count == 0 -> return WriteOutcome(id, listOf("$id: old_text was not found. Read the file and copy the text exactly."))
                count > 1 && !replaceAll -> return WriteOutcome(
                    id,
                    listOf("$id: old_text appears $count times. Give more surrounding text, or pass replace_all."),
                )
            }
            val updated = if (replaceAll) text.replace(oldText, newText) else text.replaceFirst(oldText, newText)
            checkContent(id, recordDirKind(id), updated, loadSnapshot()).takeIf { it.isNotEmpty() }?.let { return WriteOutcome(id, it) }
            val undoId = journal(id, file)
            file.writeText(updated)
            WriteOutcome(id, undoId = undoId)
        }

    /** Deletes a record or raw file. Root files are refused. */
    fun deleteRecord(path: String): WriteOutcome = synchronized(lock) {
        val id = normalizeId(path)
        if (id in ROOT_FILES) return WriteOutcome(id, listOf("$id is a root file and is never deleted."))
        if (recordDirKind(id) == null && !isRawId(id)) {
            return WriteOutcome(id, listOf("$id: only files in areas/, people/, notes/ and raw/ can be deleted."))
        }
        val file = vaultFile(id)?.takeIf { it.isFile } ?: return WriteOutcome(id, listOf("$id does not exist."))
        val undoId = journal(id, file)
        if (!file.delete()) return WriteOutcome(id, listOf("$id could not be deleted."))
        WriteOutcome(id, undoId = undoId)
    }

    /**
     * Copies a raw drop into raw/ under [name] (made safe, never overwriting: `-2`, `-3` … on a clash),
     * streaming at most [maxBytes]. Raw files are immutable afterwards; undo removes the copy.
     */
    fun fileRaw(name: String, maxBytes: Long = RAW_COPY_MAX_BYTES, size: Long? = null, open: () -> java.io.InputStream): WriteOutcome = synchronized(lock) {
        // A known size is refused before anything is copied; an unknown one is checked while copying.
        if (size != null && size > maxBytes) return WriteOutcome("raw/", listOf("'$name': not filed (larger than ${maxBytes / (1024 * 1024)} MB)."))
        val safe = name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-', '.').take(120)
        if (safe.isEmpty()) return WriteOutcome("raw/", listOf("'$name' has no usable file name."))
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty() || it == safe) "" else ".$it" }
        var target = File(dir("raw"), safe)
        var n = 2
        while (target.exists()) target = File(dir("raw"), "$base-${n++}$ext")
        val id = "raw/${target.name}"
        dir("raw").mkdirs()
        val undoId = journal(id, target)
        val copied = try {
            open().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) error("larger than ${maxBytes / (1024 * 1024)} MB")
                        out.write(buf, 0, read)
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (copied != null) {
            target.delete()
            undo(undoId)
            return WriteOutcome(id, listOf("$id: not filed ($copied)."))
        }
        WriteOutcome(id, created = true, undoId = undoId)
    }

    /**
     * Sets (or with null clears) a record's `task_ref`, touching only that frontmatter line. Setting it
     * must leave a valid record; clearing always works, so a broken note can still be unlinked.
     */
    fun setTaskRef(path: String, taskId: String?): WriteOutcome = synchronized(lock) {
        val id = normalizeId(path)
        val dirKind = recordDirKind(id) ?: return WriteOutcome(id, listOf("$id: only records in areas/, people/ or notes/ link to tasks."))
        val file = vaultFile(id)?.takeIf { it.isFile } ?: return WriteOutcome(id, listOf("$id does not exist."))
        val lines = file.readText().lines()
        val end = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).indexOfFirst { it.trim() == "---" } + 1 else 0
        if (end <= 0) return WriteOutcome(id, listOf("$id has no frontmatter to hold task_ref."))
        val front = lines.subList(1, end).filterNot { it.substringBefore(':').trim() == "task_ref" }
        val updated = (listOf(lines[0]) + front + listOfNotNull(taskId?.let { "task_ref: $it" }) + lines.drop(end)).joinToString("\n")
        if (taskId != null) {
            checkContent(id, dirKind, updated, loadSnapshot()).takeIf { it.isNotEmpty() }?.let { return WriteOutcome(id, it) }
        }
        val undoId = journal(id, file)
        file.writeText(updated)
        WriteOutcome(id, undoId = undoId)
    }

    /** One vault text file by path for the agent, capped like the viewer; null when missing, binary or outside. */
    fun readText(path: String, cap: Int = VIEWER_FILE_CAP_CHARS): CappedText? {
        val id = normalizeId(path)
        if (id.isBlank() || ".." in id.split('/') || id.startsWith(".")) return null
        val file = vaultFile(id)?.takeIf { it.isFile } ?: return null
        if (!isTextFile(file.name, "x")) return null
        return readCapped(file, cap)
    }

    /**
     * Grep for the agent: `path:line: text` for each line containing [query] (case-insensitive), plus
     * `path: (name matches)` when only the file name matches. Root files first, then areas, people,
     * notes and text raw files; every file read is bounded.
     */
    fun search(query: String, limit: Int = 20): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val files = listOf(soulFile(), memoryFile(), userFile()) +
            listOf("areas", "people", "notes", "raw").flatMap { d ->
                dir(d).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
            }
        val out = mutableListOf<String>()
        for (file in files) {
            if (out.size >= limit) break
            if (!file.isFile) continue
            val id = file.toRelativeString(root).replace('\\', '/')
            val lines = if (isTextFile(file.name, "x")) readUpTo(file, LINK_SCAN_CAP_CHARS)?.lines().orEmpty() else emptyList()
            val hits = lines.withIndex().filter { q in it.value.lowercase() }
            if (hits.isEmpty() && q in file.name.lowercase()) out += "$id: (name matches)"
            hits.take(limit - out.size).forEach { (i, line) -> out += "$id:${i + 1}: ${line.trim().take(SEARCH_LINE_CHARS)}" }
        }
        return out
    }

    /** Puts a file back as it was before the change [undoId] names. False when the entry is gone. */
    fun undo(undoId: String): Boolean = synchronized(lock) {
        if (!JOURNAL_ID_REGEX.matches(undoId)) return false
        val pathFile = File(journalDir(), "$undoId.path").takeIf { it.isFile } ?: return false
        val id = pathFile.readText().trim()
        val file = vaultFile(id) ?: return false
        val prev = File(journalDir(), "$undoId.prev")
        if (prev.isFile) {
            file.parentFile?.mkdirs()
            prev.copyTo(file, overwrite = true)
        } else {
            file.delete()
        }
        pathFile.delete()
        prev.delete()
        true
    }

    /** The vault id a journal entry restores, or null. */
    fun undoTarget(undoId: String): String? =
        undoId.takeIf { JOURNAL_ID_REGEX.matches(it) }
            ?.let { File(journalDir(), "$it.path") }?.takeIf { it.isFile }?.readText()?.trim()

    private fun journalDir() = File(root, ".history")

    private var journalSeq = 0

    /** Saves [file]'s current bytes (or that it didn't exist) before a change; keeps the newest [JOURNAL_KEEP]. */
    private fun journal(id: String, file: File): String {
        val dir = journalDir().apply { mkdirs() }
        val undoId = "%013d-%04d".format(System.currentTimeMillis(), journalSeq++ % 10_000)
        File(dir, "$undoId.path").writeText(id)
        if (file.isFile) file.copyTo(File(dir, "$undoId.prev"), overwrite = true)
        dir.listFiles { f -> f.name.endsWith(".path") }
            ?.map { it.name.removeSuffix(".path") }
            ?.sortedDescending()
            ?.drop(JOURNAL_KEEP)
            ?.forEach { old ->
                File(dir, "$old.path").delete()
                File(dir, "$old.prev").delete()
            }
        return undoId
    }

    private fun normalizeId(path: String): String = path.trim().replace('\\', '/').trimStart('/')

    private fun isRawId(id: String): Boolean = id.startsWith("raw/") && id.count { it == '/' } == 1 && id.length > 4

    /** Why [id] can't be written or edited at all, or null. */
    private fun refuseTarget(id: String): String? = when {
        id.isBlank() || ".." in id.split('/') -> "'$id' is not a path inside the Second Brain."
        id == "user.md" -> "user.md is synced from Settings → Profile; change the profile instead."
        id.startsWith("raw/") -> "$id: raw files are never edited. File new drops with memory_file_raw."
        id in ROOT_FILES -> null
        recordDirKind(id) == null -> "$id: records live in areas/, people/ or notes/ as one .md file each (no subfolders)."
        else -> null
    }

    /** Schema for records, caps for the two root files. */
    private fun checkContent(id: String, dirKind: String?, content: String, snapshot: SecondBrainSnapshot): List<String> {
        val cap = when (id) {
            "SOUL.md" -> SOUL_CAP_CHARS
            "MEMORY.md" -> MEMORY_CAP_CHARS
            else -> null
        }
        if (cap != null) {
            return if (content.length > cap) listOf("$id must stay under $cap characters (now ${content.length}): it is read at the start of every run.") else emptyList()
        }
        if (dirKind == null) return emptyList()
        // Writing areas/<name>.md with area <name> is how a new area is created.
        val bootstrap = if (dirKind == "areas") {
            setOf(id.substringAfter('/').substringBeforeLast('.')) + listOfNotNull(frontmatterOf(content)?.get("title")?.let(::unquote))
        } else {
            emptySet()
        }
        val known = knownAreaNames(snapshot) + bootstrap
        val areaOk = { area: String -> known.any { it.equals(area, ignoreCase = true) } }
        val area = frontmatterOf(content)?.get("area")?.let(::unquote).orEmpty()
        val result = validateRecord(id, content, dirKind, if (area.isNotBlank() && areaOk(area)) known + area else known)
        return result.errors
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
        // Only the filled-in profile bullets; the "(nothing filled in yet)" placeholder is left out.
        val user = snapshot.userCard?.content?.let(::bodyAfterFrontmatter)?.lines()
            ?.map { it.trim() }
            ?.filter { it.startsWith("- ") && ':' in it && !it.substringAfter(':').trim().let { v -> v.isEmpty() || v.startsWith("(") } }
            ?.joinToString("\n")?.takeIf { it.isNotEmpty() }?.take(USER_CAP_CHARS)
        return SecondBrainPrompt(
            soul = soul,
            memory = memory,
            soulTruncated = soulTruncated,
            memoryTruncated = memoryTruncated,
            titles = titles,
            user = user,
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
            val home = dir.canonicalFile
            dir.listFiles()
                ?.filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS }
                // Like vaultFile: a link pointing out of the vault is never read into the prompt or graph.
                ?.filter { runCatching { it.canonicalFile.parentFile == home }.getOrDefault(false) }
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

    /**
     * Force-directed layout (Fruchterman-Reingold): repulsion pushes all nodes
     * apart, springs pull linked nodes together, gravity keeps the cloud
     * centered. Fixed seed, so the same vault always draws the same brain.
     * Coordinates are centered at the origin in a 1000x700 box; the viewer
     * scales them to the canvas.
     */
    fun forceLayout(
        nodeCount: Int,
        edgeIndexPairs: List<Pair<Int, Int>>,
        iterations: Int = 160,
    ): List<Offset> {
        if (nodeCount == 0) return emptyList()
        if (nodeCount == 1) return listOf(Offset.Zero)
        val width = 1000f
        val height = 700f
        val random = kotlin.random.Random(42)
        val pos = Array(nodeCount) {
            Offset(random.nextFloat() * width - width / 2f, random.nextFloat() * height - height / 2f)
        }
        val area = width * height
        val k = kotlin.math.sqrt(area / nodeCount)
        var temperature = minOf(width, height) / 4f
        repeat(iterations.coerceIn(1, 500)) {
            val disp = Array(nodeCount) { Offset.Zero }
            for (i in 0 until nodeCount) {
                for (j in i + 1 until nodeCount) {
                    val delta = pos[i] - pos[j]
                    val dist = delta.getDistance().coerceAtLeast(0.01f)
                    val force = k * k / dist
                    val push = delta / dist * force
                    disp[i] += push
                    disp[j] -= push
                }
            }
            edgeIndexPairs.forEach { (a, b) ->
                if (a in 0 until nodeCount && b in 0 until nodeCount && a != b) {
                    val delta = pos[a] - pos[b]
                    val dist = delta.getDistance().coerceAtLeast(0.01f)
                    val force = dist * dist / k
                    val pull = delta / dist * force
                    disp[a] -= pull
                    disp[b] += pull
                }
            }
            for (i in 0 until nodeCount) {
                disp[i] -= pos[i] * 0.02f
                val len = disp[i].getDistance().coerceAtLeast(0.01f)
                val capped = disp[i] / len * minOf(len, temperature)
                pos[i] = Offset(
                    (pos[i].x + capped.x).coerceIn(-width / 2f, width / 2f),
                    (pos[i].y + capped.y).coerceIn(-height / 2f, height / 2f),
                )
            }
            temperature *= 0.95f
        }
        return pos.toList()
    }

    /** Deterministic depth spread per node, inside the shell's z-radius. */
    fun nodeZ(index: Int): Float = ((index * 53 % 100) / 100f - 0.5f) * 520f

    /**
     * Pulls every point inside the brain ellipsoid (with margin), so no neuron
     * can sit outside the shell no matter how many files are added later.
     * Returns the adjusted positions and depths.
     */
    fun confineToBrain(points: List<Offset>, depths: List<Float>): Pair<List<Offset>, List<Float>> {
        if (points.size != depths.size) return points to depths
        return points.zip(depths).map { (p, z) ->
            val e = (p.x / BRAIN_RX) * (p.x / BRAIN_RX) +
                (p.y / BRAIN_RY) * (p.y / BRAIN_RY) +
                (z / BRAIN_RZ) * (z / BRAIN_RZ)
            if (e <= 0.81f) {
                p to z
            } else {
                val s = (0.9f / kotlin.math.sqrt(e))
                Offset(p.x * s, p.y * s) to (z * s)
            }
        }.unzip()
    }

    /**
     * The snapshot narrowed to files matching [query] (name, preview, or full
     * text past the viewer cap for truncated files). The screen runs it off
     * the main thread; truncated re-reads are bounded like every vault read.
     */
    fun filterSnapshot(snapshot: SecondBrainSnapshot, query: String): SecondBrainSnapshot {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return snapshot
        fun match(file: BrainFile): Boolean {
            if (file.name.lowercase().contains(q)) return true
            if (file.content.lowercase().contains(q)) return true
            // Truncated previews stop at the viewer cap: search the full file
            // before reporting no match.
            if (!file.truncated) return false
            val abs = file.absolutePath
            if (abs.isBlank()) return false
            return readUpTo(File(abs), LINK_SCAN_CAP_CHARS)?.lowercase()?.contains(q) ?: false
        }
        fun matchOpt(file: BrainFile?): BrainFile? = if (file != null && match(file)) file else null
        return snapshot.copy(
            soul = matchOpt(snapshot.soul),
            memoryIndex = matchOpt(snapshot.memoryIndex),
            userCard = matchOpt(snapshot.userCard),
            areas = snapshot.areas.filter(::match),
            people = snapshot.people.filter(::match),
            notes = snapshot.notes.filter(::match),
            raw = snapshot.raw.filter(::match),
        )
    }

    companion object {
        const val SOUL_CAP_CHARS = 1500
        const val MEMORY_CAP_CHARS = 1500
        const val USER_CAP_CHARS = 800
        const val VIEWER_FILE_CAP_CHARS = 8000
        const val LINK_SCAN_CAP_CHARS = 200_000
        /** Brain shell radii in graph units. Layout content is confined inside these. */
        const val BRAIN_RX = 280f
        const val BRAIN_RY = 360f
        const val BRAIN_RZ = 520f
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

        /** The three root files: never deleted; user.md is never written by a record write. */
        val ROOT_FILES = setOf("SOUL.md", "MEMORY.md", "user.md")
        /** Undo depth, the same as the layout history. */
        const val JOURNAL_KEEP = 50
        /** Longest line a search hit shows. */
        const val SEARCH_LINE_CHARS = 200
        private val JOURNAL_ID_REGEX = Regex("^\\d{13}-\\d{4}$")

        /** Root files are navigation hubs, not content: never reported as unlinked. */
        val HEALTH_ROOT_IDS = setOf("SOUL.md", "MEMORY.md", "user.md")

        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "")
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "yaml", "yml", "json", "csv", "log")
        private val WIKI_LINK_REGEX = Regex("\\[\\[([^\\]|\\r\\n]+)(?:\\|[^\\]\\r\\n]*)?]]")
        private val FENCE_REGEX = Regex("(?s)(```|~~~).*?(\\1|\\Z)")
        private val SLUG_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val ISO_DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val DATED_NAME_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}")
        /** Optional keys that, when present, must carry a value. */
        private val OPTIONAL_NON_BLANK_KEYS = listOf("source", "source_file", "next_action", "task_ref")
        /** Raw drops older than this are proposed for cleanup. */
        const val RAW_STALE_DAYS = 30
        /** Raw files over this size on durable disk are linked in place, never duplicated. */
        const val RAW_LINK_THRESHOLD_BYTES = 10L * 1024 * 1024
        /** Largest raw drop copied into the vault. */
        const val RAW_COPY_MAX_BYTES = 200L * 1024 * 1024
    }
}
