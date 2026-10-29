package com.samosaai.masala.pro

import com.samosaai.masala.core.tool.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schemas for the `:pro` tools (plan Phase 1, lane C), copied from the private prototype's ToolDefinitions.kt like
 * `:app`'s MasalaToolDefinitions. `:pro` can't see `:app`, so it has its own copy of the helpers.
 */
object ProToolDefinitions {

    private fun schema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            block()
        }

    private fun tool(name: String, description: String, parameters: JsonObject) = ToolDefinition(name, description, parameters)

    val listFiles = tool(
        "list_files",
        "List files and directories at a path, optionally recursive, sorted and filtered.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Directory path to list.")
                }
                putJsonObject("recursive") {
                    put("type", "boolean")
                    put("description", "List recursively (with depth limit). Default false.")
                }
                putJsonObject("sort_by") {
                    put("type", "string")
                    put("description", "Sort by: 'name' (default), 'date', or 'size'.")
                }
                putJsonObject("include") {
                    put("type", "string")
                    put("description", "Only show entries whose name contains this substring (case-insensitive).")
                }
                putJsonObject("exclude") {
                    put("type", "string")
                    put("description", "Exclude entries whose name contains this substring (case-insensitive).")
                }
                putJsonObject("max_depth") {
                    put("type", "integer")
                    put("description", "Max directory depth for recursive listing (1-20). Default 10.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val readFile = tool(
        "read_file",
        "Read any file, detecting its type: text line-by-line (offset/limit), images fed to " +
            "the vision model so you can see them, archives (zip/apk/jar) listed and readable " +
            "entry-wise via 'archive.zip/path', other binaries as base64.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Path to the file or directory to read.")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "1-indexed line number to start reading from (text files only).")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max number of lines to return (text files only, default 2000).")
                }
                putJsonObject("encoding") {
                    put("type", "string")
                    put("description", "Text encoding (UTF-8, ISO-8859-1, UTF-16). Default UTF-8.")
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val writeFile = tool(
        "write_file",
        "Write or append text or binary content to a file. Parent directories are created.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path to write to.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Text content to write (or base64-encoded content when binary=true).")
                }
                putJsonObject("append") {
                    put("type", "boolean")
                    put("description", "Append instead of overwrite. Default false.")
                }
                putJsonObject("binary") {
                    put("type", "boolean")
                    put("description", "If true, content is base64-encoded binary data. Default false.")
                }
            }
            putJsonArray("required") {
                add("path")
                add("content")
            }
        }
    )

    val glob = tool(
        "glob",
        "Find files by glob pattern (* non-/, ** recursive, ? one char), returning paths " +
            "relative to the search root.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Glob pattern, e.g. '**/*.txt' or 'downloads/*.jpg'.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path, e.g. 'files' or 'downloads/reports'.")
                }
            }
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
        }
    )

    val fileSearch = tool(
        "file_search",
        "Find files anywhere in the phone's storage by words in their name or text (PDF, Word, Excel, " +
            "PowerPoint, text and code files), using the file index. Returns each match's path, date and the " +
            "passage that matched, newest first. Files changed since the last index run may be missing: " +
            "use glob for names and grep for text files then.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Words to find, e.g. 'rental agreement deposit'. All words must match.")
                }
                putJsonObject("folder") {
                    put("type", "string")
                    put("description", "Only search under this folder, e.g. 'Download' or '/sdcard/Documents'. Default: everywhere.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Most results to return (1-30). Default 10.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    val grep = tool(
        "grep",
        "Search file contents by case-insensitive regex, returning path, line number and " +
            "line content.",
        schema {
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Regular expression pattern to search for (case-insensitive).")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Root and optional sub-path, e.g. 'files' or 'downloads/reports'.")
                }
                putJsonObject("include") {
                    put("type", "string")
                    put("description", "Optional file-name filter glob, e.g. '*.txt', '*.kt', '*.{json,xml}'.")
                }
            }
            putJsonArray("required") {
                add("pattern")
                add("path")
            }
        }
    )

    val mediaConvert = tool(
        "media_convert",
        "Convert an audio file from one format to another on-device, using Termux's ffmpeg. This is the ONLY " +
            "way to produce an MP3: Android ships no MP3 encoder, so media_edit physically cannot write one. " +
            "Targets: .mp3, .m4a, .aac, .ogg, .opus, .wav, .flac — the format is taken from the output " +
            "extension. The file never leaves the device. Needs Termux installed with the ffmpeg package; if " +
            "it is missing the error says exactly what to install. For everything other than format — " +
            "trimming, muting, compressing, changing speed, joining — use media_edit, which needs no setup. " +
            "This does not convert video containers.",
        schema {
            putJsonObject("properties") {
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source audio or video file to take the audio from, e.g. " +
                            "'/sdcard/Music/recording.m4a'. Must be on shared storage — Termux cannot see " +
                            "Masala's private folders."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path to write, its extension choosing the format: '.mp3', '.m4a', '.aac', '.ogg', " +
                            "'.opus', '.wav' or '.flac'. Must be on shared storage and cannot be the input."
                    )
                }
                putJsonObject("bitrate") {
                    put("type", "string")
                    put(
                        "description",
                        "Audio bitrate as kbps, e.g. '192k' or '320k'. Defaults to 192k (128k for Opus). " +
                            "Ignored for .wav and .flac, which are lossless."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") {
                add("input")
                add("output")
            }
        }
    )
}
