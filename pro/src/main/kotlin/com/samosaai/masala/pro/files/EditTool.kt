package com.samosaai.masala.pro.files

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Replaces one section of a text file, so the model doesn't have to pass the whole file back
 * through `write_file`. The match is literal (no regex) and must be exact, including whitespace
 * and indentation. The file is still buffered (up to 1 MB) and rewritten, but the tool call only
 * carries the changed span. Files that aren't valid UTF-8 text are refused rather than rewritten.
 */
class EditTool(private val files: ToolFiles) {

    companion object {
        const val MAX_FILE_BYTES = 1024L * 1024
        private const val SNIPPET_LINES = 5
        private const val SNIPPET_WIDTH = 80
    }

    fun edit(path: String, oldString: String?, newString: String?, replaceAll: Boolean = false): ActionResult {
        if (oldString.isNullOrBlank()) {
            return ActionResult.error("old_string is blank. Pass the exact text to replace, including whitespace and indentation.")
        }
        if (newString == null) return ActionResult.error("Missing or invalid required parameter 'new_string'.")
        if (newString == oldString) {
            return ActionResult.error("new_string is identical to old_string. Nothing to change.")
        }
        val resolved = files.resolveForWrite(path)
        val file = when (resolved) {
            is ToolFiles.ResolveResult.PermissionNeeded -> return resolved.result
            is ToolFiles.ResolveResult.Error -> return ActionResult.error(resolved.message)
            is ToolFiles.ResolveResult.Ok -> resolved.file
        }
        if (!file.exists()) {
            return ActionResult.error(
                "Path '$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to discover valid paths."
            )
        }
        if (!file.isFile) return ActionResult.error("Path '$path' is a directory, not a file.")
        val raw = readBounded(file)
            ?: return ActionResult.error("File '${file.name}' is larger than 1 MB. Read it in pages with read_file instead.")
        if (looksBinary(raw)) {
            return ActionResult.error(
                "File '${file.name}' looks binary. Use read_file to inspect it and write_file to replace it."
            )
        }
        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) {
            return ActionResult.error(
                "File '${file.name}' is not valid UTF-8 text, so editing it could corrupt it. " +
                    "Use read_file to inspect it and write_file to replace it."
            )
        }
        // `read_file` shows LF-joined lines, so a multiline span copied from its output misses on a
        // CRLF file. Match on LF when the file is uniformly CRLF and convert back afterwards; a file
        // with mixed endings stays exact-only.
        val crlf = isUniformCrlf(content)
        val work = if (crlf) content.replace("\r\n", "\n") else content
        val needle = if (crlf) oldString.replace("\r\n", "\n") else oldString
        val replacement = if (crlf) newString.replace("\r\n", "\n") else newString
        // Index scan, no substrings: a 1 MB file full of matches stays cheap. Without replaceAll the
        // second hit already proves the match isn't unique, so stop there.
        val offsets = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = work.indexOf(needle, from)
            if (i < 0) break
            offsets += i
            if (!replaceAll && offsets.size == 2) break
            from = i + needle.length
        }
        if (offsets.isEmpty()) {
            val hint = if (!crlf && content.contains("\r\n"))
                " The file mixes line endings: keep old_string to a single line or match the exact CRLF bytes."
            else ""
            return ActionResult.error("No match for old_string in '$path'. It must match exactly, including whitespace and indentation.$hint")
        }
        if (offsets.size > 1 && !replaceAll) {
            return ActionResult.error(
                "old_string matched ${offsets.size} times. Pass a longer old_string with more context, or call again with replace_all=true."
            )
        }
        val targets = if (replaceAll) offsets else offsets.take(1)
        val built = StringBuilder(work.length + replacement.length)
        var cursor = 0
        var firstNewOffset = -1
        for (off in targets) {
            built.append(work, cursor, off)
            if (firstNewOffset < 0) firstNewOffset = built.length
            built.append(replacement)
            cursor = off + needle.length
        }
        built.append(work, cursor, work.length)
        val updatedWork = built.toString()
        val updated = if (crlf) updatedWork.replace("\n", "\r\n") else updatedWork
        val updatedBytes = updated.toByteArray(Charsets.UTF_8)
        if (updatedBytes.size > MAX_FILE_BYTES) {
            return ActionResult.error(
                "The edited file would be larger than 1 MB. Split the change or rewrite the file with write_file."
            )
        }
        // The file may have changed since it was read; the rename below would silently drop that.
        val snapshotModified = file.lastModified()
        val snapshotLength = raw.size.toLong()
        if (file.lastModified() != snapshotModified || file.length() != snapshotLength) {
            return ActionResult.error("File '$path' changed while editing. Read it again and retry the edit.")
        }
        val wasExecutable = file.canExecute()
        val tmp = File(file.parentFile, ".${file.name}.edit-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(updatedBytes)
            if (wasExecutable) tmp.setExecutable(true)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return ActionResult.error("Could not replace '$path': the rename failed and the file is unchanged.")
            }
        } catch (e: Exception) {
            tmp.delete()
            return ActionResult.error("Could not write '$path': ${e.message}")
        }
        val noun = if (targets.size == 1) "1 occurrence" else "${targets.size} occurrences"
        return ActionResult.ok("Replaced $noun in '$path'.\n${snippet(updatedWork, firstNewOffset)}")
    }

    /** Null reads past the cap, so a file that grows after the size check still can't slip through. */
    private fun readBounded(file: File): ByteArray? {
        return try {
            file.inputStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun looksBinary(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        val head = sample.take(512)
        val nullRatio = head.count { it.toInt() == 0 }.toFloat() / head.size
        val nonprintableRatio = head.count {
            val v = it.toInt() and 0xFF
            v < 32 && v != 9 && v != 10 && v != 13
        }.toFloat() / head.size
        return nullRatio > 0.05f || nonprintableRatio > 0.3f
    }

    private fun isUniformCrlf(content: String): Boolean {
        if (!content.contains("\r\n")) return false
        val stripped = content.replace("\r\n", "")
        return !stripped.contains('\n') && !stripped.contains('\r')
    }

    private fun snippet(updatedWork: String, firstNewOffset: Int): String {
        val lines = updatedWork.lines()
        val center = if (firstNewOffset < 0) 0 else updatedWork.take(firstNewOffset).count { it == '\n' }
        val start = (center - SNIPPET_LINES / 2).coerceAtLeast(0)
        val end = minOf(start + SNIPPET_LINES, lines.size)
        return lines.subList(start, end).mapIndexed { i, line ->
            "${start + i + 1}: ${if (line.length > SNIPPET_WIDTH) line.take(SNIPPET_WIDTH) + "…(line truncated)" else line}"
        }.joinToString("\n")
    }
}
