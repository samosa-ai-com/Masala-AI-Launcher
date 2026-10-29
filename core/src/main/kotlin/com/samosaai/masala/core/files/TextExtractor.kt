package com.samosaai.masala.core.files

import java.io.File

/**
 * Reads a document's text for the file index (arch §3.10). `:app` backs it with its document parser
 * (PDF, DOCX, XLSX, PPTX, HTML, RTF, text), so `:pro` needs no parsing libraries of its own.
 * Null when the format isn't readable or reading failed.
 */
fun interface TextExtractor {
    fun extract(file: File): String?
}
