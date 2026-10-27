package com.sneakin.core

import com.sneakin.model.UniqueFormat
import com.sneakin.model.ValueType

/**
 * Generates a fresh value for a parameter the server requires to be UNIQUE per request — account
 * number, email, reference id, nickname, and so on.
 *
 * SneakIn otherwise replays the same baseline value on every request (baseline samples + every
 * mutation that holds this field constant). A server-side uniqueness check would then reject every
 * repeat, so the scan would see spurious rejections instead of the real input-validation behaviour.
 *
 * Each call weaves a monotonically-increasing [counter] into the value. The [UniqueFormat] the user
 * picked in the Targets table decides the charset of the result:
 *  - **[UniqueFormat.NUMERIC]**      digits only — `ACC-12` -> `12<counter>` (empty -> `<counter>`)
 *  - **[UniqueFormat.ALPHANUMERIC]** letters + digits, no symbols — `a-b.c` -> `abc<base36 counter>`
 *  - **[UniqueFormat.MIXED]**        keep the recognizable shape (legacy):
 *      - numeric `123` -> `123<counter>`
 *      - email   `a@b.com` -> `a<counter>@b.com`
 *      - uuid    keeps the 8-4-4-4-12 shape, overwriting the trailing node
 *      - anything else `foo` -> `foo-<counter>`
 */
object UniqueValue {

    fun next(original: String, type: ValueType, format: UniqueFormat, counter: Long): String =
        when (format) {
            UniqueFormat.NUMERIC -> numeric(original, counter)
            UniqueFormat.ALPHANUMERIC -> alphanumeric(original, counter)
            UniqueFormat.MIXED -> mixed(original, type, counter)
        }

    /** Strip everything but ASCII digits, then append the counter — the result is always `0-9`. */
    private fun numeric(original: String, counter: Long): String {
        val digits = original.filter { it in '0'..'9' }
        return digits + counter
    }

    /** Strip everything but ASCII letters/digits, then append a base-36 counter — always `a-z0-9`. */
    private fun alphanumeric(original: String, counter: Long): String {
        val alnum = original.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
        return alnum + counter.toString(36)
    }

    /** Legacy shape-preserving rotation, keyed off the inferred [type]. */
    private fun mixed(original: String, type: ValueType, counter: Long): String = when (type) {
        ValueType.EMAIL -> email(original, counter)
        ValueType.UUID -> uuid(original, counter)
        ValueType.NUMERIC -> original.trim().ifEmpty { "0" } + counter
        ValueType.EMPTY -> counter.toString()
        else -> "$original-$counter"
    }

    /** Insert the counter at the end of the local part: `name@host` -> `name<counter>@host`. */
    private fun email(original: String, counter: Long): String {
        val at = original.indexOf('@')
        return if (at >= 0) original.substring(0, at) + counter + original.substring(at)
        else original + counter
    }

    /** Overwrite the trailing 12-hex node of a UUID with counter-derived hex, keeping the shape. */
    private fun uuid(original: String, counter: Long): String {
        val isUuidShape = original.length == 36 && original.count { it == '-' } == 4
        if (!isUuidShape) return original + counter
        val hex = counter.toString(16).padStart(12, '0').takeLast(12)
        return original.substring(0, 24) + hex
    }
}
