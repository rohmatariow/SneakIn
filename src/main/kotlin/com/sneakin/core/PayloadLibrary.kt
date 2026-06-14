package com.sneakin.core

import com.sneakin.model.InjectionPoint
import com.sneakin.model.Payload
import com.sneakin.model.PayloadCategory
import com.sneakin.model.PayloadEntry
import com.sneakin.model.TypeInference
import com.sneakin.model.ValueType

/**
 * Holds the three editable payload categories and generates the applicable, ordered payload list
 * for an injection point (Section 8 + Section 6.4 applicability). Edits made via the Payload Editor
 * mutate these lists directly; disabled entries consume no requests.
 */
class PayloadLibrary {

    // Group 1 — Symbol (static literals), applies to every point.
    val symbols: MutableList<PayloadEntry> = defaultSymbols()

    // Group 2 — Bad Format (rule-based, generative), applies when a format is detected.
    val appendSuffixes: MutableList<PayloadEntry> = defaultSuffixes()
    var modeAppend = true
    var modeMalform = true
    var modeTruncate = true

    // Group 3 — Negative (numbers), applies only to numeric fields.
    val negatives: MutableList<PayloadEntry> = defaultNegatives()

    /** Applicable, enabled payloads for [point], ordered symbol -> bad-format -> negative. */
    fun payloadsFor(point: InjectionPoint): List<Payload> {
        val out = ArrayList<Payload>()
        // Symbol — all points.
        symbols.filter { it.enabled }.forEach { out.add(Payload(it.value, PayloadCategory.SYMBOL)) }
        // Bad Format — only when a recognizable format is present.
        if (TypeInference.hasFormat(point.valueType)) {
            out.addAll(badFormatPayloads(point.originalValue, point.valueType))
        }
        // Negative — only when numeric.
        if (point.valueType == ValueType.NUMERIC) {
            negatives.filter { it.enabled }.forEach { out.add(Payload(it.value, PayloadCategory.NEGATIVE)) }
        }
        return out
    }

    /** Generate bad-format payloads from a valid [base] value of [type], honoring the mode toggles. */
    fun badFormatPayloads(base: String, type: ValueType): List<Payload> {
        val out = ArrayList<Payload>()
        if (modeAppend) {
            appendSuffixes.filter { it.enabled }.forEach {
                out.add(Payload(base + it.value, PayloadCategory.BAD_FORMAT, "append '${it.value}'"))
            }
        }
        if (modeMalform) {
            malform(base, type)?.let { out.add(Payload(it, PayloadCategory.BAD_FORMAT, "malformed ${type.name.lowercase()}")) }
        }
        if (modeTruncate) {
            truncate(base, type)?.let { out.add(Payload(it, PayloadCategory.BAD_FORMAT, "truncated")) }
        }
        return out
    }

    /** A structurally-broken variant of a valid value (e.g. an impossible date). */
    private fun malform(base: String, type: ValueType): String? = when (type) {
        ValueType.DATE -> if (base.contains('/')) "13/45/2026" else "2026-13-45"
        ValueType.EMAIL -> if (base.contains('@')) base.replaceFirst("@", "@@") else "$base@"
        ValueType.UUID -> base.dropLast(1) + "ZZ"
        ValueType.NUMERIC -> "$base.0.0"
        else -> null
    }

    /** A truncated (cut) variant of a valid value. Numeric truncation stays valid, so it is skipped. */
    private fun truncate(base: String, type: ValueType): String? = when (type) {
        ValueType.DATE -> base.substringBefore('T').substringBeforeLast('-').takeIf { it != base && it.isNotEmpty() }
        ValueType.EMAIL -> base.substringBefore('@').takeIf { it.isNotEmpty() && it != base }
        ValueType.UUID -> base.substringBeforeLast('-').takeIf { it != base }
        else -> null
    }

    fun resetSymbols() { symbols.clear(); symbols.addAll(defaultSymbols()) }
    fun resetBadFormat() {
        appendSuffixes.clear(); appendSuffixes.addAll(defaultSuffixes())
        modeAppend = true; modeMalform = true; modeTruncate = true
    }
    fun resetNegatives() { negatives.clear(); negatives.addAll(defaultNegatives()) }

    private fun defaultSymbols() = mutableListOf(PayloadEntry("<{!@#}>"), PayloadEntry("<>"), PayloadEntry("|"))
    private fun defaultSuffixes() = mutableListOf(PayloadEntry("zzz"), PayloadEntry("AAA"))
    private fun defaultNegatives() = mutableListOf(PayloadEntry("-1"), PayloadEntry("-99999"))
}
