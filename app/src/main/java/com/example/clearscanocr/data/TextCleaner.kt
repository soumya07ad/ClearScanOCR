package com.example.clearscanocr.data

/**
 * Utility for cleaning up raw OCR text output.
 */
object TextCleaner {

    /**
     * Clean and format raw OCR text for display.
     *
     * - Trims leading/trailing whitespace per line
     * - Removes duplicate blank lines
     * - Merges lines that appear to be fragments of the same sentence
     *   (previous line doesn't end with sentence-ending punctuation)
     *
     * @param raw The raw text from ML Kit.
     * @return A clean, human-readable string.
     */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""

        val lines = raw.lines().map { it.trim() }

        val merged = mutableListOf<String>()
        var buffer = StringBuilder()

        for (line in lines) {
            if (line.isEmpty()) {
                // Flush buffer on blank line
                if (buffer.isNotEmpty()) {
                    merged.add(buffer.toString())
                    buffer = StringBuilder()
                }
                // Add one blank-line marker (dedup later)
                merged.add("")
                continue
            }

            if (buffer.isEmpty()) {
                buffer.append(line)
            } else {
                val lastChar = buffer.last()
                // Merge if previous chunk doesn't end with sentence-ending punctuation
                if (lastChar in listOf('.', '!', '?', ':', ';', '\n')) {
                    merged.add(buffer.toString())
                    buffer = StringBuilder(line)
                } else {
                    buffer.append(' ').append(line)
                }
            }
        }
        if (buffer.isNotEmpty()) {
            merged.add(buffer.toString())
        }

        // Remove consecutive blank lines
        return buildString {
            var prevBlank = false
            for (entry in merged) {
                if (entry.isEmpty()) {
                    if (!prevBlank) {
                        appendLine()
                        prevBlank = true
                    }
                } else {
                    appendLine(entry)
                    prevBlank = false
                }
            }
        }.trim()
    }
}
