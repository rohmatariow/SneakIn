package com.sneakin

import com.google.gson.JsonParser
import com.sneakin.core.JsonLeafWalker
import com.sneakin.core.JsonMutate
import com.sneakin.core.WrappedJson
import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken
import com.sneakin.model.display
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Wrapped-JSON injection points: a value that is itself JSON (Case A — query/form param value;
 * Case C — a JSON string-leaf carrying JSON), nested **recursively** to any depth. Branching content
 * (objects, arrays, mixed) must enumerate to every inner leaf and round-trip through mutation.
 */
class WrappedJsonTest {

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
    private fun dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)
    private fun leafNames(json: String) = JsonLeafWalker.leaves(json)!!.map { it.path.display() }.toSet()
    private fun pathOf(json: String, display: String): List<JsonToken> =
        JsonLeafWalker.leaves(json)!!.first { it.path.display() == display }.path

    // ---- container detection ----------------------------------------------

    @Test
    fun detectsRawJsonObjectAndArray() {
        assertNotNull(WrappedJson.container("""{"post":{"id":123,"title":"halo"}}"""))
        assertNotNull(WrappedJson.container("""[{"id":1}]"""))
        assertEquals(setOf("post.id", "post.title"), leafNames(WrappedJson.container("""{"post":{"id":123,"title":"halo"}}""")!!))
    }

    @Test
    fun detectsUrlEncodedJsonViaContainerNotRawContainer() {
        val encoded = enc("""{"id":123}""")
        assertNotNull(WrappedJson.container(encoded))            // tries url-decode
        assertNull(WrappedJson.rawContainer(encoded))           // raw-only must NOT match %7B…
        assertEquals(listOf("id"), JsonLeafWalker.leaves(WrappedJson.container(encoded)!!)!!.map { it.path.display() })
    }

    @Test
    fun branchingArrayDictMixedYieldsEveryLeaf() {
        val v = """{"items":[{"id":1},{"id":2}],"meta":{"k":"v"},"tags":["a","b"]}"""
        assertEquals(setOf("items[0].id", "items[1].id", "meta.k", "tags[0]", "tags[1]"), leafNames(WrappedJson.container(v)!!))
    }

    @Test
    fun bareScalarsAndEmptyContainersAreNotWrapped() {
        for (v in listOf("123", "true", "halo", "{}", "[]", "")) {
            assertNull(WrappedJson.container(v), "should not treat '$v' as wrapped JSON")
            assertNull(WrappedJson.rawContainer(v))
        }
    }

    // ---- Case A: param value = JSON, mutation round-trip ------------------

    @Test
    fun rewriteParamSetsDeepLeafAndLeavesOtherParamsUntouched() {
        val json = """{"post":{"id":123,"title":"halo"}}"""
        val blob = "x=1&post=" + enc(json) + "&y=2"
        val out = WrappedJson.rewriteParam(blob, "post", emptyList(), pathOf(json, "post.title"), "<{!@#}>", JsonKind.STRING)!!

        assertTrue(out.startsWith("x=1&post="), out)
        assertTrue(out.endsWith("&y=2"), out)
        val post = JsonParser.parseString(dec(out.removePrefix("x=1&post=").removeSuffix("&y=2"))).asJsonObject.getAsJsonObject("post")
        assertEquals("<{!@#}>", post.get("title").asString)
        assertEquals(123, post.get("id").asInt) // sibling preserved
    }

    @Test
    fun rewriteParamKeepsNumberTypeOnNegativePayload() {
        val blob = "post=" + enc("""{"id":5}""")
        val out = WrappedJson.rewriteParam(blob, "post", emptyList(), pathOf("""{"id":5}""", "id"), "-1", JsonKind.NUMBER)!!
        val id = JsonParser.parseString(dec(out.removePrefix("post="))).asJsonObject.get("id")
        assertTrue(id.asJsonPrimitive.isNumber)
        assertEquals(-1, id.asInt)
    }

    @Test
    fun rewriteParamWithRawUnencodedJsonValueIsNotCorruptedByPlus() {
        // raw (unencoded) JSON value containing '+': must not be url-decoded into a space.
        val blob = "q=" + """{"note":"a+b"}"""
        val out = WrappedJson.rewriteParam(blob, "q", emptyList(), pathOf("""{"note":"a+b"}""", "note"), "<>", JsonKind.STRING)!!
        val obj = JsonParser.parseString(dec(out.removePrefix("q="))).asJsonObject
        assertEquals("<>", obj.get("note").asString)
    }

    @Test
    fun rewriteParamMissingParamReturnsNull() {
        assertNull(WrappedJson.rewriteParam("a=1&b=2", "post", emptyList(), listOf(JsonToken.Key("id")), "x", JsonKind.STRING))
    }

    // ---- Case C: JSON-in-string re-embed ----------------------------------

    @Test
    fun jsonInStringDeepLeafReembedsAndStaysEscaped() {
        val body = """{"meta":"x","data":"{\"id\":1,\"sub\":{\"x\":\"y\"}}"}"""
        val out = WrappedJson.setThroughStringWraps(
            body, listOf(listOf(JsonToken.Key("data"))), listOf(JsonToken.Key("sub"), JsonToken.Key("x")), "<{!@#}>", JsonKind.STRING
        )!!
        val dataStr = JsonParser.parseString(out).asJsonObject.get("data").asString
        val x = JsonParser.parseString(dataStr).asJsonObject.getAsJsonObject("sub").get("x").asString
        assertEquals("<{!@#}>", x)
        assertEquals("x", JsonParser.parseString(out).asJsonObject.get("meta").asString) // sibling intact
    }

    @Test
    fun emptyWrapsIsPlainSet() {
        val out = WrappedJson.setThroughStringWraps("""{"id":1}""", emptyList(), listOf(JsonToken.Key("id")), "<>", JsonKind.NUMBER)!!
        assertEquals("<>", JsonParser.parseString(out).asJsonObject.get("id").asString)
    }

    // ---- Recursive wrappers (depth >= 2) ----------------------------------

    @Test
    fun caseA_then_C_paramHoldingJsonHoldingJsonString() {
        // post = {"data":"{\"id\":1}"}  (url-encoded), target post › data › id
        val inner = """{"id":1}"""
        val mid = JsonMutate.set("""{"data":"x"}""", listOf(JsonToken.Key("data")), inner, JsonKind.STRING)!!
        val blob = "post=" + enc(mid)
        val out = WrappedJson.rewriteParam(
            blob, "post", listOf(listOf(JsonToken.Key("data"))), listOf(JsonToken.Key("id")), "<{!@#}>", JsonKind.STRING
        )!!
        val postJson = dec(out.removePrefix("post="))
        val dataStr = JsonParser.parseString(postJson).asJsonObject.get("data").asString
        assertEquals("<{!@#}>", JsonParser.parseString(dataStr).asJsonObject.get("id").asString)
    }

    @Test
    fun caseC_then_C_jsonStringInsideJsonStringDepthTwo() {
        // body.a = "{\"b\":\"{\\\"c\\\":1}\"}"  — target a › b › c
        val innermost = """{"c":1}"""
        val midJson = JsonMutate.set("""{"b":"y"}""", listOf(JsonToken.Key("b")), innermost, JsonKind.STRING)!!
        val body = JsonMutate.set("""{"a":"x"}""", listOf(JsonToken.Key("a")), midJson, JsonKind.STRING)!!

        val out = WrappedJson.setThroughStringWraps(
            body, listOf(listOf(JsonToken.Key("a")), listOf(JsonToken.Key("b"))), listOf(JsonToken.Key("c")), "<{!@#}>", JsonKind.STRING
        )!!
        val aStr = JsonParser.parseString(out).asJsonObject.get("a").asString
        val bStr = JsonParser.parseString(aStr).asJsonObject.get("b").asString
        assertEquals("<{!@#}>", JsonParser.parseString(bStr).asJsonObject.get("c").asString)
    }

    @Test
    fun setThroughStringWrapsMissingHopReturnsNull() {
        assertNull(
            WrappedJson.setThroughStringWraps(
                """{"a":"not json"}""", listOf(listOf(JsonToken.Key("a"))), listOf(JsonToken.Key("c")), "x", JsonKind.STRING
            )
        )
    }
}
