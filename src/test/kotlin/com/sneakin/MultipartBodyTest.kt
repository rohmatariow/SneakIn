package com.sneakin

import com.sneakin.core.MultipartBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MultipartBodyTest {

    private val boundary = "----SneakInBoundary123"

    // raw "binary" file content including a NUL and a high byte
    private val fileBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(),
        'G'.code.toByte(), 0x00, 0xFF.toByte(), 0x0D, 0x0A, 0x1A)

    private fun sampleBody(): ByteArray {
        val out = ByteArrayOutputStream()
        fun a(s: String) = out.write(s.toByteArray(StandardCharsets.ISO_8859_1))
        a("--$boundary\r\n")
        a("Content-Disposition: form-data; name=\"username\"\r\n\r\n")
        a("alice\r\n")
        a("--$boundary\r\n")
        a("Content-Disposition: form-data; name=\"avatar\"; filename=\"pic.png\"\r\n")
        a("Content-Type: image/png\r\n\r\n")
        out.write(fileBytes)
        a("\r\n")
        a("--$boundary--\r\n")
        return out.toByteArray()
    }

    @Test
    fun parsesFieldsAndFilePart() {
        val mb = MultipartBody.parse(sampleBody(), boundary)
        assertNotNull(mb)
        mb!!
        assertEquals(2, mb.parts.size)

        assertEquals("username", mb.parts[0].name)
        assertNull(mb.parts[0].filename)
        assertEquals("alice", String(mb.parts[0].value, StandardCharsets.UTF_8))

        assertEquals("avatar", mb.parts[1].name)
        assertEquals("pic.png", mb.parts[1].filename)
        assertArrayEquals(fileBytes, mb.parts[1].value)
    }

    @Test
    fun boundaryParsedFromHeader() {
        assertEquals(boundary, MultipartBody.boundaryOf("multipart/form-data; boundary=$boundary"))
        assertEquals("abc", MultipartBody.boundaryOf("multipart/form-data; boundary=\"abc\"; charset=utf-8"))
        assertNull(MultipartBody.boundaryOf("application/json"))
    }

    @Test
    fun mutatingFieldValueKeepsFileBinaryIntact() {
        val mb = MultipartBody.parse(sampleBody(), boundary)!!
        val mutated = mb.renderWithFieldValue(0, "<script>alert(1)</script>")
        val reparsed = MultipartBody.parse(mutated, boundary)!!

        assertEquals("<script>alert(1)</script>", String(reparsed.parts[0].value, StandardCharsets.UTF_8))
        // file part untouched, byte-for-byte
        assertArrayEquals(fileBytes, reparsed.parts[1].value)
        assertEquals("pic.png", reparsed.parts[1].filename)
        // closing delimiter present
        assertTrue(String(mutated, StandardCharsets.ISO_8859_1).endsWith("--$boundary--\r\n"))
    }

    @Test
    fun mutatingFilenameKeepsContentTypeAndBinary() {
        val mb = MultipartBody.parse(sampleBody(), boundary)!!
        val mutated = mb.renderWithFilename(1, "evil.php")
        val reparsed = MultipartBody.parse(mutated, boundary)!!

        assertEquals("evil.php", reparsed.parts[1].filename)
        assertArrayEquals(fileBytes, reparsed.parts[1].value)
        assertTrue(reparsed.parts[1].headerText.contains("Content-Type: image/png"))
        // first field is unchanged
        assertEquals("alice", String(reparsed.parts[0].value, StandardCharsets.UTF_8))
    }

    @Test
    fun filenameInjectionStripsCrlf() {
        val mb = MultipartBody.parse(sampleBody(), boundary)!!
        val mutated = mb.renderWithFilename(1, "a\r\nInjected: header.png")
        val reparsed = MultipartBody.parse(mutated, boundary)!!
        // CR/LF removed so the multipart structure stays valid
        assertEquals("aInjected: header.png", reparsed.parts[1].filename)
        assertEquals(2, reparsed.parts.size)
    }
}
