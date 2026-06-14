package com.sneakin

import com.sneakin.core.UniqueValue
import com.sneakin.model.TypeInference
import com.sneakin.model.ValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UniqueValueTest {

    @Test
    fun numericStaysNumericAndUnique() {
        val v = UniqueValue.next("123", ValueType.NUMERIC, 5)
        assertEquals("1235", v)
        // still recognized as numeric so the server's parser accepts it
        assertEquals(ValueType.NUMERIC, TypeInference.infer(v))
    }

    @Test
    fun emailKeepsDomainAndStaysValidShape() {
        assertEquals("1239@gmail.com", UniqueValue.next("123@gmail.com", ValueType.EMAIL, 9))
        assertEquals(ValueType.EMAIL, TypeInference.infer(UniqueValue.next("a@b.co", ValueType.EMAIL, 1)))
    }

    @Test
    fun uuidKeepsShapeAndOverwritesTrailingNode() {
        val orig = "b45769d4-0931-491a-9058-30f7c37cb499"
        val v = UniqueValue.next(orig, ValueType.UUID, 42)
        assertEquals(36, v.length)
        assertEquals(4, v.count { it == '-' })
        // the first four nodes (24 chars incl. dashes) are untouched; only the last node changes
        assertEquals(orig.substring(0, 24), v.substring(0, 24))
        assertNotEquals(orig, v)
        assertEquals(ValueType.UUID, TypeInference.infer(v))
    }

    @Test
    fun stringGetsCounterSuffix() {
        assertEquals("nickname-3", UniqueValue.next("nickname", ValueType.STRING, 3))
    }

    @Test
    fun emptyBecomesCounter() {
        assertEquals("7", UniqueValue.next("", ValueType.EMPTY, 7))
    }

    @Test
    fun distinctCountersGiveDistinctValues() {
        val a = UniqueValue.next("123", ValueType.NUMERIC, 1)
        val b = UniqueValue.next("123", ValueType.NUMERIC, 2)
        assertNotEquals(a, b)
    }
}
