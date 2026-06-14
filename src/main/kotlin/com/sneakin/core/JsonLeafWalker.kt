package com.sneakin.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken

/** A scalar JSON leaf: its path from the root, string value (`"null"` for JSON null) and kind. */
data class JsonLeaf(val path: List<JsonToken>, val value: String, val kind: JsonKind)

/** Walks a JSON document to every scalar leaf, recording the nested path to each. */
object JsonLeafWalker {

    /** Parse [text] and return every leaf, or null if the text is not valid JSON. */
    fun leaves(text: String): List<JsonLeaf>? {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return null
        }
        val out = ArrayList<JsonLeaf>()
        walk(root, emptyList(), out)
        return out
    }

    private fun walk(el: JsonElement, path: List<JsonToken>, out: MutableList<JsonLeaf>) {
        when {
            el.isJsonObject -> for ((k, v) in el.asJsonObject.entrySet()) {
                walk(v, path + JsonToken.Key(k), out)
            }
            el.isJsonArray -> el.asJsonArray.forEachIndexed { i, v ->
                walk(v, path + JsonToken.Index(i), out)
            }
            else -> {
                val value: String
                val kind: JsonKind
                if (el.isJsonNull) {
                    value = "null"; kind = JsonKind.NULL
                } else {
                    val prim = el.asJsonPrimitive
                    value = prim.asString
                    kind = when {
                        prim.isBoolean -> JsonKind.BOOLEAN
                        prim.isNumber -> JsonKind.NUMBER
                        else -> JsonKind.STRING
                    }
                }
                out.add(JsonLeaf(path, value, kind))
            }
        }
    }
}
