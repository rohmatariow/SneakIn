package com.sneakin.model

/** The three illegal-input categories. Every payload is expected to be REJECTED by the server. */
enum class PayloadCategory {
    SYMBOL, BAD_FORMAT, NEGATIVE;

    val label: String
        get() = when (this) {
            SYMBOL -> "Symbol"
            BAD_FORMAT -> "Bad Format"
            NEGATIVE -> "Negative"
        }

    /** Sub-label applied to a VULNERABLE verdict when input of this category is accepted. */
    val acceptedSubLabel: String
        get() = when (this) {
            SYMBOL -> "Symbol Accepted"
            BAD_FORMAT -> "Bad Format Accepted"
            NEGATIVE -> "Negative Accepted"
        }
}

/** A concrete payload to send into one injection point. */
class Payload(val value: String, val category: PayloadCategory, val note: String = "")

/** An editable payload entry: a value the user can enable/disable without deleting. */
class PayloadEntry(var value: String, var enabled: Boolean = true)
