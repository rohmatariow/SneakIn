package com.sneakin.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken

/** Sets a single leaf value inside a JSON document, identified by its nested path. */
object JsonMutate {

    // disableHtmlEscaping keeps payload chars like < > & literal so reflection matching works.
    // serializeNulls is REQUIRED: Gson drops null object-members by default, which would silently
    // strip every originally-null field from the rebuilt body (so a mutated/combined request would
    // no longer match the real request the server expects). Keep nulls so the body stays faithful.
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    /** Return the scalar string at [path] in [text] (a JSON string-leaf's text), or null if absent/non-scalar. */
    fun get(text: String, path: List<JsonToken>): String? {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return null
        }
        var el: JsonElement = root
        for (token in path) el = child(el, token) ?: return null
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive -> el.asJsonPrimitive.asString
            else -> null
        }
    }

    /** Return [text] with the leaf at [path] replaced by [value] (typed per [kind]); null on failure. */
    fun set(text: String, path: List<JsonToken>, value: String, kind: JsonKind?): String? {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return null
        }
        val newRoot = setIn(root, path, primitiveFor(value, kind)) ?: return null
        return gson.toJson(newRoot)
    }

    private fun setIn(root: JsonElement, path: List<JsonToken>, prim: JsonElement): JsonElement? {
        if (path.isEmpty()) return prim
        var parent: JsonElement = root
        for (i in 0 until path.size - 1) {
            parent = child(parent, path[i]) ?: return null
        }
        when (val last = path.last()) {
            is JsonToken.Key -> {
                if (!parent.isJsonObject || !parent.asJsonObject.has(last.key)) return null
                parent.asJsonObject.add(last.key, prim)
            }
            is JsonToken.Index -> {
                if (!parent.isJsonArray) return null
                val arr = parent.asJsonArray
                if (last.index !in 0 until arr.size()) return null
                arr.set(last.index, prim)
            }
        }
        return root
    }

    private fun child(el: JsonElement, token: JsonToken): JsonElement? = when (token) {
        is JsonToken.Key ->
            if (el.isJsonObject && el.asJsonObject.has(token.key)) el.asJsonObject.get(token.key) else null
        is JsonToken.Index ->
            if (el.isJsonArray && token.index in 0 until el.asJsonArray.size()) el.asJsonArray.get(token.index) else null
    }

    /** Build a primitive, preserving the original leaf's JSON type where the payload allows. */
    private fun primitiveFor(value: String, kind: JsonKind?): JsonElement = when (kind) {
        JsonKind.NUMBER -> value.toBigDecimalOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
        JsonKind.BOOLEAN -> when (value.lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(value)
        }
        else -> JsonPrimitive(value)
    }
}
