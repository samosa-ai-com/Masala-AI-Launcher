package com.samosaai.masala.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.FileAccessDeniedException
import com.samosaai.masala.core.tool.FileScope
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.secondbrain.SecondBrainRepository
import com.samosaai.masala.secondbrain.SecondBrainStore
import com.samosaai.masala.tools.missing
import com.samosaai.masala.tools.string
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream

/**
 * `memory_file_raw` (plan `docs/plans/second-brain-robust.md`, S4): files an original (a recording, a PDF,
 * a photo) into the Second Brain's raw/ folder, or links a big one where it already lives. Asks first.
 *
 * Copy vs link (D4): a picked file (`content://`) or anything under a temp or cache path is always copied.
 * A file over 10 MB on durable storage is linked in place instead of duplicated, which needs all-files
 * access (`:pro`); without it every file is copied, up to [SecondBrainStore.RAW_COPY_MAX_BYTES].
 */
object MemoryRawTool {
    val definition = ToolDefinition(
        "memory_file_raw",
        "Keep an original file (recording, PDF, photo, document) in the Second Brain's raw/ folder, after the " +
            "user said to keep it. Then write the note that owns it with source_file and a [[raw/...]] link. " +
            "Files over 10 MB on the phone's storage are linked in place (mode link) instead of copied.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put("description", "The file: a path (/sdcard/…, file://…) or a content:// URI from a picker or share.")
                }
                putJsonObject("mode") {
                    put("type", "string")
                    putJsonArray("enum") { add("copy"); add("link") }
                    put("description", "copy (default) puts a copy in raw/; link records a big file where it is.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "File name in raw/, e.g. 2026-10-29-standup.m4a. Defaults to the source's name.")
                }
            }
            putJsonArray("required") { add("source") }
        },
    )

    /** A picked file's display name and a way to open it, or null when it can't be read. */
    fun interface UriSource {
        fun open(uri: String): Pair<String, () -> InputStream>?
    }

    fun create(context: Context, secondBrain: SecondBrainRepository, fileAccess: () -> FileAccess): Tool =
        create(secondBrain, fileAccess) { uri ->
            val parsed = Uri.parse(uri)
            val name = runCatching {
                context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: parsed.lastPathSegment?.substringAfterLast('/') ?: "file"
            name to { context.contentResolver.openInputStream(parsed) ?: error("the picked file can't be opened") }
        }

    internal fun create(secondBrain: SecondBrainRepository, fileAccess: () -> FileAccess, uris: UriSource): Tool =
        FunctionTool(definition, CHANGES_SOMETHING, readsPrivateData = true) { args ->
            val source = args.string("source")?.trim()?.takeIf { it.isNotEmpty() } ?: return@FunctionTool missing("source")
            val link = args.string("mode")?.trim()?.lowercase() == "link"
            val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() }
            if (source.startsWith("content://")) {
                if (link) return@FunctionTool ActionResult.error("A picked file can't be linked in place; file it with mode copy.")
                val (shown, open) = uris.open(source) ?: return@FunctionTool ActionResult.error("The picked file can't be read.")
                return@FunctionTool filed(secondBrain.fileRaw(name ?: shown, open))
            }
            val access = fileAccess()
            val file = try {
                access.resolve(source.removePrefix("file://"))
            } catch (e: FileAccessDeniedException) {
                return@FunctionTool ActionResult.error(e.message ?: "That file is out of reach.")
            }
            if (!file.isFile) return@FunctionTool ActionResult.error("No file at '$source'.")
            val big = file.length() > SecondBrainStore.RAW_LINK_THRESHOLD_BYTES
            val ephemeral = secondBrain.isEphemeralPath(file.path)
            val canLink = access.scope == FileScope.SHARED_STORAGE && big && !ephemeral
            when {
                link && canLink -> ActionResult.ok(
                    "Linked in place, nothing copied: ${file.path}. Record this path in the owning note " +
                        "(source_file: ${file.path}, and in its Sources section).",
                )
                link -> ActionResult.error(
                    when {
                        ephemeral -> "${file.path} is a temporary location that can vanish: file it with mode copy."
                        !big -> "${file.name} is under 10 MB: file it with mode copy."
                        else -> "Linking in place needs all-files access, which Masala doesn't have: file it with mode copy."
                    },
                )
                canLink -> ActionResult.error(
                    "${file.name} is over 10 MB and stays where it is: link it in place with mode link instead of copying.",
                )
                else -> filed(secondBrain.fileRaw(name ?: file.name) { file.inputStream() })
            }
        }

    private fun filed(outcome: SecondBrainStore.WriteOutcome): ActionResult =
        if (outcome.ok) {
            ActionResult.ok(
                "Filed ${outcome.id}. Now write or update the note that owns it: source_file: ${outcome.id} " +
                    "and a [[${outcome.id}]] link in its Sources section.",
            )
        } else {
            ActionResult.error(outcome.errors.joinToString("\n"))
        }
}
