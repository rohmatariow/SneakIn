package com.sneakin.oracle

import java.awt.Color

/**
 * The verdict for one mutated request. There is deliberately **no** ERROR verdict — a 5xx/stack
 * trace never means the illegal input was accepted, so it is treated as a [REJECTED] input (the
 * server errored rather than accepting the value) (Section 7.3).
 */
enum class Verdict {
    /** Illegal input was accepted. This is the finding. */
    VULNERABLE,

    /** Diverged but ambiguous — manual check needed. */
    REVIEW,

    /** Input was not accepted — proper rejection (4xx / error marker) or a 5xx server error. */
    REJECTED;

    val displayColor: Color
        get() = when (this) {
            VULNERABLE -> Color(0xC0, 0x39, 0x2B) // red
            REVIEW -> Color(0xB7, 0x8A, 0x00)     // amber
            REJECTED -> Color(0x7F, 0x8C, 0x8D)   // grey
        }
}

/** Outcome of comparing a mutated response against the baseline. */
enum class CompareResult { SAME, SIMILAR, DIFFERENT }

/**
 * A full classification result: the verdict, a category sub-label (e.g. "Negative Accepted"), an
 * acceptance score for display, the comparison outcome, and human-readable reasons + signals.
 */
class Classification(
    val verdict: Verdict,
    val subLabel: String,
    val score: Double,
    val compare: CompareResult?,
    val reasons: List<String>,
    val signals: List<String>,
)
