package com.samosaai.masala.buildlogic

/**
 * The manifest check and Play readiness report (arch §4, plan 0.4). Plain Kotlin over the manifest
 * merger's blame report, so the rules are unit-tested without running a build.
 */

/** A permission, component or `<queries>` package in the merged manifest. */
data class ManifestEntry(val kind: String, val name: String, val sources: Set<String>) {
    val key get() = "$kind $name"
}

/** Where an entry came from: `:pro` or another module, a library's coordinates, or [APP]. */
const val APP = "app"
const val PRO = ":pro"

/** Elements the check looks at. Activities aren't restricted, so they're left out. */
private val checkedTags = listOf(
    "uses-permission-sdk-23", "uses-permission", "permission", "service", "receiver", "provider",
    "package",
)
private val tagPattern = Regex("""<(${checkedTags.joinToString("|")})(?=[\s>/]|$)""")
private val namePattern = Regex("""android:name="([^"]+)"""")
private val authoritiesPattern = Regex("""android:authorities="([^"]+)"""")
private val lineNumber = Regex("""^\d+""")

/**
 * Reads `manifest-merger-blame-<variant>-report.txt`: each line of the merged manifest, prefixed with
 * its line number, followed by `N-->` lines naming where it came from. `-->[:pro] …` is a module,
 * `-->[androidx.work:work-runtime:2.10.0] …` a library, and `-->/path/…` the app's own manifest.
 */
fun parseBlameReport(text: String): List<ManifestEntry> {
    val entries = mutableListOf<ManifestEntry>()
    var kind: String? = null
    var name: String? = null
    var authorities: String? = null
    var sources = mutableSetOf<String>()
    var tagOpen = false

    fun finish() {
        val k = kind ?: return
        // Elements nothing claims were added by the build tools; hold them to the app's rules.
        // A <provider> inside <queries> has only authorities.
        val n = checkNotNull(name ?: authorities) { "<$k> without android:name" }
        entries += ManifestEntry(k, n, sources.ifEmpty { setOf(APP) })
        kind = null
    }

    for (line in text.lines()) {
        val rest = line.replaceFirst(lineNumber, "")
        if (rest.startsWith("-->")) {
            if (kind != null) sources += sourceOf(rest.removePrefix("-->"))
            continue
        }
        val tag = tagPattern.find(rest)
        if (tag != null) {
            finish()
            kind = if (tag.groupValues[1] == "uses-permission-sdk-23") "uses-permission" else tag.groupValues[1]
            name = null
            authorities = null
            sources = mutableSetOf()
            tagOpen = true
        } else if (!tagOpen && '<' in rest) {
            // A child element or the next sibling: its blame lines aren't this entry's.
            finish()
        }
        if (kind != null && tagOpen) {
            if (name == null) name = namePattern.find(rest)?.groupValues?.get(1)
            if (authorities == null) authorities = authoritiesPattern.find(rest)?.groupValues?.get(1)
            if ('>' in rest) tagOpen = false
        }
    }
    finish()
    return entries
}

private fun sourceOf(blame: String): String =
    if (blame.startsWith("[")) blame.substring(1, blame.indexOf(']')) else APP

/**
 * A list file: one `<kind> <name>` per line, then `#` and the reason it's there. Blank lines and
 * lines starting with `#` are skipped. A line without a reason is an error, so nothing gets added
 * to a list without one.
 */
data class ListLine(val kind: String, val name: String, val reason: String)

fun parseList(text: String, fileName: String): List<ListLine> =
    text.lines().withIndex().mapNotNull { (i, raw) ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val entry = line.substringBefore('#').trim()
        val reason = line.substringAfter('#', "").trim()
        val parts = entry.split(Regex("""\s+"""))
        require(parts.size == 2) { "$fileName:${i + 1}: expected '<kind> <name>  # reason'" }
        require(reason.isNotEmpty()) { "$fileName:${i + 1}: ${parts[1]} needs a reason after '#'" }
        ListLine(parts[0], parts[1], reason)
    }

/**
 * The core allowlist. `library <prefix>` lines accept every entry a matching library brings in
 * by Maven group (`androidx.work` matches `androidx.work:work-runtime:2.10.0`); other lines accept one entry.
 */
class Allowlist(lines: List<ListLine>) {
    private val libraries = lines.filter { it.kind == "library" }.map { it.name }
    private val entries = lines.filter { it.kind != "library" }.associateBy { "${it.kind} ${it.name}" }

    fun allowsLibrary(source: String) = libraries.any { source.startsWith("$it:") }

    fun reasonFor(entry: ManifestEntry) = entries[entry.key]?.reason
}

/**
 * Entries that fail the build: something outside `:pro` and the allowlisted libraries added them,
 * and they aren't on the allowlist. An entry `:pro` also declares still fails if the app declares it
 * too, since `:app` must work without `:pro`.
 */
fun violations(entries: List<ManifestEntry>, allowlist: Allowlist): List<ManifestEntry> =
    entries.filter { entry ->
        val coreSources = entry.sources.filterNot { it == PRO || allowlist.allowsLibrary(it) }
        coreSources.isNotEmpty() && allowlist.reasonFor(entry) == null
    }

fun violationMessage(violations: List<ManifestEntry>, allowlistName: String): String = buildString {
    appendLine("Manifest check failed: these entries come from outside :pro and aren't on $allowlistName.")
    violations.forEach { appendLine("  ${it.key}  (from ${it.sources.joinToString()})") }
    appendLine("Move the code that needs them into :pro, or add them to $allowlistName with a reason (arch §4).")
}

/** `build/reports/play-readiness.md`: what `:pro` adds and why, and the core entries with their reasons. */
fun readinessReport(
    variant: String,
    entries: List<ManifestEntry>,
    proNotes: List<ListLine>,
    allowlist: Allowlist,
): String = buildString {
    val notes = proNotes.associateBy { "${it.kind} ${it.name}" }
    val pro = entries.filter { PRO in it.sources }.sortedBy { it.key }
    val core = entries.filterNot { PRO in it.sources }.sortedBy { it.key }

    appendLine("# Play readiness report ($variant)")
    appendLine()
    appendLine("Generated by the manifest check from the merged manifest. See arch §4 and §9.")
    appendLine()
    appendLine("## Entries `:pro` adds")
    appendLine()
    if (pro.isEmpty()) {
        appendLine("None.")
    } else {
        appendLine("| Entry | Kind | Needed by (tool) and used by (Pro skills) |")
        appendLine("|---|---|---|")
        pro.forEach {
            val why = notes[it.key]?.reason ?: "**Not described in `pro/play-readiness.txt`**"
            appendLine("| `${it.name}` | ${it.kind} | $why |")
        }
    }
    appendLine()
    appendLine("## Core entries")
    appendLine()
    if (core.isEmpty()) {
        appendLine("None.")
        return@buildString
    }
    appendLine("| Entry | Kind | From | Reason |")
    appendLine("|---|---|---|---|")
    core.forEach {
        val from = it.sources.joinToString { s -> if (s == APP) "app" else "`$s`" }
        val reason = allowlist.reasonFor(it) ?: if (it.sources.all(allowlist::allowsLibrary)) "allowlisted library" else "—"
        appendLine("| `${it.name}` | ${it.kind} | $from | $reason |")
    }
}

/**
 * Plan 0.3: `:app` may use only `ProTools` from `:pro`. Returns `file:line: import` for every other
 * import of a `:pro` package.
 */
fun forbiddenProImports(sources: Map<String, String>, allowed: Set<String> = setOf("com.samosaai.masala.pro.ProTools")): List<String> =
    sources.flatMap { (path, text) ->
        text.lines().withIndex().mapNotNull { (i, line) ->
            val import = line.trim().takeIf { it.startsWith("import com.samosaai.masala.pro.") }
                ?.removePrefix("import ")?.substringBefore(" as ")?.trim()
            if (import != null && import !in allowed) "$path:${i + 1}: $import" else null
        }
    }
