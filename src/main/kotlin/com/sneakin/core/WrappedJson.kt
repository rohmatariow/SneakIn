package com.sneakin.core

import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pure helpers for **wrapped JSON** injection points: a value that is itself a JSON document, carried
 * either as a url-encoded parameter value (query/form — `post={"id":1}`) or as a string inside
 * another JSON body (`{"data":"{\"id\":1}"}`). Wrapping nests **recursively**: a param can hold JSON
 * that holds a JSON-string that holds JSON, to any depth.
 *
 * A wrapped target is described by an optional outer **param frame** (at most one, outermost) plus an
 * ordered list of **string-wrap paths** — each the path of a JSON string-leaf (whose text is JSON)
 * within its enclosing level — and finally the leaf path inside the innermost JSON. Mutation replays
 * that chain: descend to the innermost JSON, set the leaf, then re-embed level by level (re-escaping
 * each JSON string, re-encoding the param). Kept Burp-free so the whole thing is unit-testable.
 */
object WrappedJson {

    /**
     * Normalized JSON text if [value] is a JSON object/array, else null. Tries the value as-is then
     * url-decoded, so it works whether the source hands back an encoded or decoded value. Bare scalars
     * ("123", "true", "halo") and empty containers are NOT treated as wrapped JSON.
     */
    fun container(value: String): String? {
        for (c in candidates(value)) if (isJsonDoc(c)) return c
        return null
    }

    /** Like [container] but only the raw value (no url-decoding) — for JSON strings nested inside JSON. */
    fun rawContainer(value: String): String? {
        val t = value.trim()
        return if (isJsonDoc(t)) t else null
    }

    /**
     * Set the leaf at [finalPath] inside [jsonText], descending first through [stringWraps] (each a
     * JSON string-leaf carrying JSON) and re-embedding on the way out. Empty [stringWraps] is a plain
     * set on [jsonText]. Returns the rewritten [jsonText], or null if any hop is gone.
     */
    fun setThroughStringWraps(
        jsonText: String,
        stringWraps: List<List<JsonToken>>,
        finalPath: List<JsonToken>,
        value: String,
        kind: JsonKind?,
    ): String? {
        if (stringWraps.isEmpty()) return JsonMutate.set(jsonText, finalPath, value, kind)
        val head = stringWraps.first()
        val inner = JsonMutate.get(jsonText, head) ?: return null
        val newInner = setThroughStringWraps(inner, stringWraps.drop(1), finalPath, value, kind) ?: return null
        return JsonMutate.set(jsonText, head, newInner, JsonKind.STRING)
    }

    /**
     * In an `a=...&b=...` blob, find parameter [name], descend its value through [stringWraps] to
     * [finalPath], set it, and re-encode in place. The param value is treated as url-encoded unless
     * it is already a raw JSON document (so a raw `{...}` value is not corrupted by `+`→space); the
     * rewritten value is always url-encoded. Other parameters are left byte-for-byte untouched.
     */
    fun rewriteParam(
        blob: String,
        name: String,
        stringWraps: List<List<JsonToken>>,
        finalPath: List<JsonToken>,
        value: String,
        kind: JsonKind?,
    ): String? {
        val pairs = blob.split("&").toMutableList()
        for (i in pairs.indices) {
            val eq = pairs[i].indexOf('=')
            if (eq < 0) continue
            val rawName = pairs[i].substring(0, eq)
            if (rawName != name && urlDecode(rawName) != name) continue
            val rawValue = pairs[i].substring(eq + 1)
            val jsonText = rawContainer(rawValue) ?: urlDecode(rawValue)
            val newJson = setThroughStringWraps(jsonText, stringWraps, finalPath, value, kind) ?: return null
            pairs[i] = rawName + "=" + URLEncoder.encode(newJson, StandardCharsets.UTF_8)
            return pairs.joinToString("&")
        }
        return null
    }

    private fun candidates(value: String): List<String> = buildList {
        val raw = value.trim()
        add(raw)
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()
            ?.trim()?.takeIf { it != raw }?.let { add(it) }
    }

    private fun isJsonDoc(text: String): Boolean {
        if (!(text.startsWith("{") || text.startsWith("["))) return false
        return !JsonLeafWalker.leaves(text).isNullOrEmpty()
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8) }.getOrDefault(s)
}
