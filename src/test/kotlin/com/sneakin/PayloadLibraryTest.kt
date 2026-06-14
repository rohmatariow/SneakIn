package com.sneakin

import com.sneakin.core.PayloadLibrary
import com.sneakin.model.InjectionPoint
import com.sneakin.model.LocationType
import com.sneakin.model.PayloadCategory
import com.sneakin.model.ValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PayloadLibraryTest {

    private fun point(value: String, type: ValueType) =
        InjectionPoint(LocationType.FORM, "f", value, type)

    @Test
    fun defaultsMatchSpec() {
        val lib = PayloadLibrary()
        assertEquals(listOf("<{!@#}>", "<>", "|"), lib.symbols.map { it.value })
        assertEquals(listOf("-1", "-99999"), lib.negatives.map { it.value })
        assertEquals(listOf("zzz", "AAA"), lib.appendSuffixes.map { it.value })
    }

    @Test
    fun stringFieldGetsOnlySymbols() {
        val payloads = PayloadLibrary().payloadsFor(point("hello", ValueType.STRING))
        assertEquals(3, payloads.size)
        assertTrue(payloads.all { it.category == PayloadCategory.SYMBOL })
    }

    @Test
    fun numericFieldGetsSymbolBadFormatAndNegativeInOrder() {
        val payloads = PayloadLibrary().payloadsFor(point("100", ValueType.NUMERIC))
        // 3 symbol + (2 append + 1 malform, truncate skipped for numeric) + 2 negative
        assertEquals(8, payloads.size)
        assertEquals(
            listOf(
                PayloadCategory.SYMBOL, PayloadCategory.SYMBOL, PayloadCategory.SYMBOL,
                PayloadCategory.BAD_FORMAT, PayloadCategory.BAD_FORMAT, PayloadCategory.BAD_FORMAT,
                PayloadCategory.NEGATIVE, PayloadCategory.NEGATIVE,
            ),
            payloads.map { it.category }
        )
        assertTrue(payloads.any { it.value == "100zzz" })
        assertTrue(payloads.any { it.value == "100.0.0" })
        assertTrue(payloads.any { it.value == "-99999" })
    }

    @Test
    fun dateFieldGetsSymbolAndBadFormatIncludingTruncate() {
        val payloads = PayloadLibrary().payloadsFor(point("2026-06-12", ValueType.DATE))
        // 3 symbol + (2 append + 1 malform + 1 truncate) = 7, no negatives
        assertEquals(7, payloads.size)
        assertTrue(payloads.none { it.category == PayloadCategory.NEGATIVE })
        assertTrue(payloads.any { it.value == "2026-13-45" })  // malform
        assertTrue(payloads.any { it.value == "2026-06" })     // truncate
    }

    @Test
    fun disabledPayloadsConsumeNoRequests() {
        val lib = PayloadLibrary()
        lib.symbols[0].enabled = false
        val payloads = lib.payloadsFor(point("hello", ValueType.STRING))
        assertEquals(2, payloads.size)
    }

    @Test
    fun disablingModesReducesBadFormat() {
        val lib = PayloadLibrary()
        lib.modeAppend = false
        lib.modeTruncate = false
        // only malform remains for a date
        val payloads = lib.payloadsFor(point("2026-06-12", ValueType.DATE))
        assertEquals(4, payloads.size) // 3 symbol + 1 malform
        assertEquals(1, payloads.count { it.category == PayloadCategory.BAD_FORMAT })
    }
}
