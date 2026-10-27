package com.sneakin

import com.sneakin.core.UniqueValue
import com.sneakin.model.TypeInference
import com.sneakin.model.UniqueFormat
import com.sneakin.model.ValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UniqueValueTest {

    // ---- MIXED: legacy shape-preserving behaviour -------------------------------------------

    @Test
    fun mixedNumericStaysNumericAndUnique() {
        val v = UniqueValue.next("123", ValueType.NUMERIC, UniqueFormat.MIXED, 5)
        assertEquals("1235", v)
        // still recognized as numeric so the server's parser accepts it
        assertEquals(ValueType.NUMERIC, TypeInference.infer(v))
    }

    @Test
    fun mixedEmailKeepsDomainAndStaysValidShape() {
        assertEquals("1239@gmail.com", UniqueValue.next("123@gmail.com", ValueType.EMAIL, UniqueFormat.MIXED, 9))
        assertEquals(
            ValueType.EMAIL,
            TypeInference.infer(UniqueValue.next("a@b.co", ValueType.EMAIL, UniqueFormat.MIXED, 1))
        )
    }

    @Test
    fun mixedUuidKeepsShapeAndOverwritesTrailingNode() {
        val orig = "b45769d4-0931-491a-9058-30f7c37cb499"
        val v = UniqueValue.next(orig, ValueType.UUID, UniqueFormat.MIXED, 42)
        assertEquals(36, v.length)
        assertEquals(4, v.count { it == '-' })
        // the first four nodes (24 chars incl. dashes) are untouched; only the last node changes
        assertEquals(orig.substring(0, 24), v.substring(0, 24))
        assertNotEquals(orig, v)
        assertEquals(ValueType.UUID, TypeInference.infer(v))
    }

    @Test
    fun mixedStringGetsCounterSuffix() {
        assertEquals("nickname-3", UniqueValue.next("nickname", ValueType.STRING, UniqueFormat.MIXED, 3))
    }

    @Test
    fun mixedEmptyBecomesCounter() {
        assertEquals("7", UniqueValue.next("", ValueType.EMPTY, UniqueFormat.MIXED, 7))
    }

    // ---- NUMERIC: digits only, whatever the original shape ----------------------------------

    @Test
    fun numericStripsSymbolsLeavingDigitsOnly() {
        // "ACC-0012" would carry a dash under MIXED; NUMERIC keeps only the digits + counter.
        val v = UniqueValue.next("ACC-0012", ValueType.STRING, UniqueFormat.NUMERIC, 5)
        assertEquals("00125", v)
        assertTrue(v.all { it in '0'..'9' })
    }

    @Test
    fun numericWithNoDigitsIsJustTheCounter() {
        val v = UniqueValue.next("abc", ValueType.STRING, UniqueFormat.NUMERIC, 7)
        assertEquals("7", v)
        assertTrue(v.all { it in '0'..'9' })
    }

    @Test
    fun numericPlainNumberAppendsCounter() {
        assertEquals("1235", UniqueValue.next("123", ValueType.NUMERIC, UniqueFormat.NUMERIC, 5))
    }

    // ---- ALPHANUMERIC: letters + digits, no symbols -----------------------------------------

    @Test
    fun alphanumericStripsSymbolsKeepsLettersAndDigits() {
        val v = UniqueValue.next("a-b.c9", ValueType.STRING, UniqueFormat.ALPHANUMERIC, 10)
        // symbols dropped -> "abc9", counter 10 in base36 -> "a"
        assertEquals("abc9a", v)
        assertTrue(v.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun alphanumericNeverContainsSymbols() {
        val v = UniqueValue.next("user@name!", ValueType.STRING, UniqueFormat.ALPHANUMERIC, 123456)
        assertTrue(v.none { it == '@' || it == '!' || it == '-' || it == '.' })
        assertTrue(v.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' })
    }

    // ---- uniqueness across counters ---------------------------------------------------------

    @Test
    fun distinctCountersGiveDistinctValues() {
        val a = UniqueValue.next("123", ValueType.NUMERIC, UniqueFormat.NUMERIC, 1)
        val b = UniqueValue.next("123", ValueType.NUMERIC, UniqueFormat.NUMERIC, 2)
        assertNotEquals(a, b)
    }
}
