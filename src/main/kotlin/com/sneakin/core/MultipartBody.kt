package com.sneakin.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** One part of a multipart/form-data body. [value] is kept as raw bytes to preserve binary uploads. */
class MultipartPart(
    val headerText: String,   // the part's header block (no trailing blank line)
    val value: ByteArray,
    val name: String?,        // Content-Disposition name="..."
    val filename: String?,    // Content-Disposition filename="..." (null for non-file parts)
)

/**
 * Parses and rebuilds a multipart/form-data body around its declared boundary. Used both to
 * enumerate injection points (field values + filenames) and to rebuild the body after mutation,
 * keeping every other part — including binary file content — byte-for-byte intact.
 */
class MultipartBody private constructor(
    val boundary: String,
    val parts: List<MultipartPart>,
) {
    /** Rebuild the body, replacing part [index]'s value bytes. */
    fun renderWithFieldValue(index: Int, newValue: String): ByteArray =
        render(index) { part -> MutatedPart(part.headerText, newValue.toByteArray(StandardCharsets.UTF_8)) }

    /** Rebuild the body, replacing part [index]'s filename attribute. CR/LF stripped to stay valid. */
    fun renderWithFilename(index: Int, newFilename: String): ByteArray {
        val safe = newFilename.replace("\r", "").replace("\n", "")
        return render(index) { part ->
            val old = part.filename ?: ""
            val newHeader = part.headerText.replace("filename=\"$old\"", "filename=\"$safe\"")
            MutatedPart(newHeader, part.value)
        }
    }

    private class MutatedPart(val headerText: String, val value: ByteArray)

    private fun render(targetIndex: Int, mutate: (MultipartPart) -> MutatedPart): ByteArray {
        val out = ByteArrayOutputStream()
        val delimiter = "--$boundary"
        for ((i, part) in parts.withIndex()) {
            val emitted = if (i == targetIndex) mutate(part) else MutatedPart(part.headerText, part.value)
            out.writeAscii(delimiter)
            out.writeAscii(CRLF)
            out.writeAscii(emitted.headerText)
            out.writeAscii(CRLF + CRLF)
            out.write(emitted.value)
            out.writeAscii(CRLF)
        }
        out.writeAscii("$delimiter--$CRLF")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(StandardCharsets.ISO_8859_1))

    companion object {
        private const val CRLF = "\r\n"

        /** Extract the boundary token from a `Content-Type: multipart/form-data; boundary=...` header. */
        fun boundaryOf(contentTypeHeader: String?): String? {
            if (contentTypeHeader == null) return null
            val marker = "boundary="
            val idx = contentTypeHeader.indexOf(marker, ignoreCase = true)
            if (idx < 0) return null
            var b = contentTypeHeader.substring(idx + marker.length).trim()
            // boundary may be quoted and/or followed by other params
            if (b.startsWith("\"")) {
                val end = b.indexOf('"', 1)
                if (end > 0) return b.substring(1, end)
            }
            val semi = b.indexOf(';')
            if (semi >= 0) b = b.substring(0, semi)
            return b.trim().ifEmpty { null }
        }

        /** Parse [body] into parts. Returns null if the structure can't be recognized. */
        fun parse(body: ByteArray, boundary: String): MultipartBody? {
            val delimiter = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
            val starts = indicesOf(body, delimiter)
            if (starts.size < 2) return null // need at least one part + closing delimiter

            val parts = ArrayList<MultipartPart>()
            for (i in 0 until starts.size - 1) {
                // content starts after the delimiter and its trailing CRLF
                var contentStart = starts[i] + delimiter.size
                if (matchesAt(body, contentStart, CRLF_BYTES)) contentStart += 2 else continue
                // content ends at the CRLF immediately preceding the next delimiter
                var contentEnd = starts[i + 1]
                if (contentEnd >= 2 && body[contentEnd - 2] == CR && body[contentEnd - 1] == LF) contentEnd -= 2
                if (contentEnd < contentStart) continue

                val part = parsePart(body, contentStart, contentEnd) ?: continue
                parts.add(part)
            }
            if (parts.isEmpty()) return null
            return MultipartBody(boundary, parts)
        }

        private fun parsePart(body: ByteArray, start: Int, end: Int): MultipartPart? {
            // split header block from value at the first blank line (CRLF CRLF)
            val sep = indexOf(body, CRLF_CRLF, start, end)
            if (sep < 0) return null
            val headerText = String(body, start, sep - start, StandardCharsets.ISO_8859_1)
            val valueStart = sep + 4
            val value = body.copyOfRange(valueStart, end)
            val name = attr(headerText, "name")
            val filename = attr(headerText, "filename")
            return MultipartPart(headerText, value, name, filename)
        }

        /** Pull a `key="value"` attribute out of a part's header block. */
        private fun attr(headerText: String, key: String): String? {
            val marker = "$key=\""
            val idx = headerText.indexOf(marker, ignoreCase = true)
            if (idx < 0) return null
            val valStart = idx + marker.length
            val valEnd = headerText.indexOf('"', valStart)
            if (valEnd < 0) return null
            return headerText.substring(valStart, valEnd)
        }

        private const val CR: Byte = '\r'.code.toByte()
        private const val LF: Byte = '\n'.code.toByte()
        private val CRLF_BYTES = byteArrayOf(CR, LF)
        private val CRLF_CRLF = byteArrayOf(CR, LF, CR, LF)

        private fun indicesOf(haystack: ByteArray, needle: ByteArray): List<Int> {
            val out = ArrayList<Int>()
            var from = 0
            while (true) {
                val i = indexOf(haystack, needle, from, haystack.size)
                if (i < 0) break
                out.add(i)
                from = i + needle.size
            }
            return out
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
            if (needle.isEmpty()) return -1
            var i = from
            val last = to - needle.size
            while (i <= last) {
                if (matchesAt(haystack, i, needle)) return i
                i++
            }
            return -1
        }

        private fun matchesAt(haystack: ByteArray, at: Int, needle: ByteArray): Boolean {
            if (at < 0 || at + needle.size > haystack.size) return false
            for (j in needle.indices) if (haystack[at + j] != needle[j]) return false
            return true
        }
    }
}
