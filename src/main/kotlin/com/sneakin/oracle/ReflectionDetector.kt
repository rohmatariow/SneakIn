package com.sneakin.oracle

/**
 * Detects verbatim reflection of an injected payload in a response and masks it before diffing, so
 * reflected input does not cause a false "DIFFERENT". Reflection is recorded as a minor signal
 * (potential XSS surface) but never sets a verdict on its own (Section 7.6).
 */
object ReflectionDetector {

    fun isReflected(body: String, payload: String): Boolean =
        payload.isNotEmpty() && body.contains(payload)

    /** Remove every verbatim occurrence of [payload] from [body]. */
    fun mask(body: String, payload: String): String =
        if (payload.isEmpty()) body else body.replace(payload, "")
}
