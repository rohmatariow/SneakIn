package com.sneakin

import com.sneakin.core.NoiseMap
import com.sneakin.model.JsonKind
import com.sneakin.oracle.ResponseFingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoiseMapTest {

    private fun fp(
        status: Int,
        len: Int,
        time: Long,
        values: Map<String, String>,
        shape: Map<String, JsonKind>,
        isJson: Boolean = true,
    ) = ResponseFingerprint(status, status / 100, "application/json", isJson, len, time, "", shape, values)

    @Test
    fun detectsValueLevelNoiseButKeepsConstantFields() {
        val shape = mapOf("id" to JsonKind.NUMBER, "name" to JsonKind.STRING, "ts" to JsonKind.NUMBER)
        val fps = listOf(
            fp(200, 100, 10, mapOf("id" to "1", "name" to "bob", "ts" to "100"), shape),
            fp(200, 110, 12, mapOf("id" to "2", "name" to "bob", "ts" to "200"), shape),
            fp(200, 105, 11, mapOf("id" to "3", "name" to "bob", "ts" to "300"), shape),
        )
        val nm = NoiseMap.build(fps, emptyList())

        assertTrue(nm.isNoisePath("id"))
        assertTrue(nm.isNoisePath("ts"))
        assertFalse(nm.isNoisePath("name")) // constant -> not noise
        assertEquals(setOf("id", "ts"), nm.noisePaths)
        assertEquals(100, nm.lengthMin)
        assertEquals(110, nm.lengthMax)
        assertTrue(nm.statusStable)
    }

    @Test
    fun typeChangeIsNeverMasked() {
        val fps = listOf(
            fp(200, 100, 10, mapOf("x" to "1"), mapOf("x" to JsonKind.NUMBER)),
            fp(200, 100, 10, mapOf("x" to "foo"), mapOf("x" to JsonKind.STRING)),
        )
        val nm = NoiseMap.build(fps, emptyList())
        assertFalse(nm.isNoisePath("x")) // value differs but type differs -> signal, not noise
        assertTrue(nm.noisePaths.isEmpty())
    }

    @Test
    fun pathMissingInSomeSampleIsNotNoise() {
        val fps = listOf(
            fp(200, 100, 10, mapOf("a" to "1", "b" to "1"), mapOf("a" to JsonKind.NUMBER, "b" to JsonKind.NUMBER)),
            fp(200, 100, 10, mapOf("a" to "2"), mapOf("a" to JsonKind.NUMBER)),
        )
        val nm = NoiseMap.build(fps, emptyList())
        assertTrue(nm.isNoisePath("a"))   // common + varies
        assertFalse(nm.isNoisePath("b"))  // disappears -> not masked
    }

    @Test
    fun unstableStatusDetected() {
        val fps = listOf(
            fp(200, 100, 10, emptyMap(), emptyMap()),
            fp(500, 100, 10, emptyMap(), emptyMap()),
        )
        val nm = NoiseMap.build(fps, emptyList())
        assertFalse(nm.statusStable)
        assertEquals(setOf(200, 500), nm.baselineStatuses)
    }

    @Test
    fun manualIgnoreSupportsExactAndRegex() {
        val fps = listOf(fp(200, 100, 10, mapOf("k" to "1"), mapOf("k" to JsonKind.NUMBER)))
        val nm = NoiseMap.build(fps, listOf("token", "secret.*"))
        assertTrue(nm.isNoisePath("token"))
        assertTrue(nm.isNoisePath("secretKey"))
        assertFalse(nm.isNoisePath("unrelated"))
    }
}
