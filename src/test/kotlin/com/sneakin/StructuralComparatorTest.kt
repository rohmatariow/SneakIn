package com.sneakin

import com.sneakin.core.JsonLeafWalker
import com.sneakin.core.NoiseMap
import com.sneakin.model.display
import com.sneakin.oracle.CompareResult
import com.sneakin.oracle.ResponseFingerprint
import com.sneakin.oracle.StructuralComparator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StructuralComparatorTest {

    private fun fp(body: String): ResponseFingerprint {
        val leaves = JsonLeafWalker.leaves(body)!!
        val shape = leaves.associate { it.path.display() to it.kind }
        val values = leaves.associate { it.path.display() to it.value }
        return ResponseFingerprint(200, 2, "application/json", true, body.length, 10, body, shape, values)
    }

    private fun noiseOf(vararg bodies: String): NoiseMap = NoiseMap.build(bodies.map { fp(it) }, emptyList())

    @Test
    fun nullBaselineFieldEchoingPayloadIsSameNotDifferent() {
        // Regression: a field that is JSON null in the baseline and echoes the accepted payload as a
        // string must NOT count as a type change (null -> string). It is the "input accepted" signal.
        val baseBody = """{"code":"00","body":{"address1":null,"firstName":"X"}}"""
        val mutBody = """{"code":"00","body":{"address1":"<{!@#}>","firstName":"X"}}"""
        val result = StructuralComparator.compare(fp(baseBody), fp(mutBody), noiseOf(baseBody), "<{!@#}>")
        assertEquals(CompareResult.SAME, result)
    }

    @Test
    fun nullBaselineToNonReflectedServerValueIsSimilar() {
        val baseBody = """{"a":null,"b":"keep"}"""
        val mutBody = """{"a":"server-derived","b":"keep"}"""
        val result = StructuralComparator.compare(fp(baseBody), fp(mutBody), noiseOf(baseBody), "<{!@#}>")
        assertEquals(CompareResult.SIMILAR, result)
    }

    @Test
    fun genuineTypeChangeNotReflectedStaysDifferent() {
        // number -> string with no null and no reflection is a real shape break.
        val baseBody = """{"x":1}"""
        val mutBody = """{"x":"abc"}"""
        val result = StructuralComparator.compare(fp(baseBody), fp(mutBody), noiseOf(baseBody), "zzz")
        assertEquals(CompareResult.DIFFERENT, result)
    }

    @Test
    fun addedOrRemovedKeyStaysDifferent() {
        val baseBody = """{"a":"1"}"""
        val mutBody = """{"a":"1","b":"2"}"""
        val result = StructuralComparator.compare(fp(baseBody), fp(mutBody), noiseOf(baseBody), "zzz")
        assertEquals(CompareResult.DIFFERENT, result)
    }
}
