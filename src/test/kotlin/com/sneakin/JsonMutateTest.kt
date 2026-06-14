package com.sneakin

import com.google.gson.JsonParser
import com.sneakin.core.JsonMutate
import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonMutateTest {

    @Test
    fun setsNestedObjectLeaf() {
        val out = JsonMutate.set(
            """{"user":{"address":{"zip":"00000"}}}""",
            listOf(JsonToken.Key("user"), JsonToken.Key("address"), JsonToken.Key("zip")),
            "<>", JsonKind.STRING
        )!!
        val zip = JsonParser.parseString(out).asJsonObject
            .getAsJsonObject("user").getAsJsonObject("address").get("zip")
        assertTrue(zip.asJsonPrimitive.isString)
        assertEquals("<>", zip.asString)
    }

    @Test
    fun setsArrayElementLeafAndKeepsNumberType() {
        val out = JsonMutate.set(
            """{"items":[{"id":1},{"id":2}]}""",
            listOf(JsonToken.Key("items"), JsonToken.Index(1), JsonToken.Key("id")),
            "-1", JsonKind.NUMBER
        )!!
        val id = JsonParser.parseString(out).asJsonObject
            .getAsJsonArray("items").get(1).asJsonObject.get("id")
        assertTrue(id.asJsonPrimitive.isNumber)
        assertEquals(-1, id.asInt)
    }

    @Test
    fun symbolOnNumericFieldFallsBackToString() {
        val out = JsonMutate.set("""{"id":1}""", listOf(JsonToken.Key("id")), "<>", JsonKind.NUMBER)!!
        val id = JsonParser.parseString(out).asJsonObject.get("id")
        assertTrue(id.asJsonPrimitive.isString)
        assertEquals("<>", id.asString)
    }

    @Test
    fun rootPathReplacesWholeDocument() {
        val out = JsonMutate.set("5", emptyList(), "-1", JsonKind.NUMBER)!!
        assertEquals("-1", out.trim())
    }

    @Test
    fun invalidPathReturnsNull() {
        assertNull(JsonMutate.set("""{"a":1}""", listOf(JsonToken.Key("missing")), "x", JsonKind.STRING))
    }

    @Test
    fun htmlCharactersArePreservedNotEscaped() {
        val out = JsonMutate.set("""{"q":"ok"}""", listOf(JsonToken.Key("q")), "<{!@#}>", JsonKind.STRING)!!
        assertTrue(out.contains("<{!@#}>"))
    }

    @Test
    fun nullSiblingFieldsArePreservedWhenMutatingAnotherLeaf() {
        // Regression: Gson drops null members by default, silently stripping every originally-null
        // field from the rebuilt body. Mutating one field must keep untouched null siblings.
        val out = JsonMutate.set(
            """{"accountNo":"123","accountRefNo":null,"firstName":null}""",
            listOf(JsonToken.Key("accountNo")), "<>", JsonKind.STRING
        )!!
        val obj = JsonParser.parseString(out).asJsonObject
        assertEquals("<>", obj.get("accountNo").asString)
        assertTrue(obj.has("accountRefNo"), "null sibling accountRefNo was dropped")
        assertTrue(obj.get("accountRefNo").isJsonNull)
        assertTrue(obj.has("firstName"), "null sibling firstName was dropped")
        assertTrue(obj.get("firstName").isJsonNull)
    }

    @Test
    fun mutatingANullLeafItselfProducesValueAndKeepsOtherNulls() {
        val out = JsonMutate.set(
            """{"currCd":null,"corpId":null}""",
            listOf(JsonToken.Key("currCd")), "<{!@#}>", JsonKind.NULL
        )!!
        val obj = JsonParser.parseString(out).asJsonObject
        assertEquals("<{!@#}>", obj.get("currCd").asString)
        assertTrue(obj.has("corpId"), "untouched null corpId was dropped")
        assertTrue(obj.get("corpId").isJsonNull)
    }
}
