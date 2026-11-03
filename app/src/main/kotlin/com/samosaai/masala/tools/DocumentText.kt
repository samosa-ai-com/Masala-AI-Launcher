package com.samosaai.masala.tools

import android.content.Context
import com.samosaai.masala.core.files.TextExtractor
import java.io.File

/**
 * The file index's [TextExtractor] (Lane B): [DocumentParser] over a file on disk. pdfbox is set up
 * the first time a document is read, not at app start.
 */
class DocumentText(context: Context) : TextExtractor {
    private val app = context.applicationContext
    private val ready by lazy { DocumentParser.init(app) }

    override fun extract(file: File): String? {
        if (file.length() > DocumentParser.MAX_DOC_BYTES) return null
        ready
        return DocumentParser.extract(file.readBytes(), file.name, null).text.substringBefore(TRUNCATED)
    }

    private companion object {
        /** The parser's own note on cut text, which the index has no use for. */
        const val TRUNCATED = "\n\n[Note: document text was truncated"
    }
}
