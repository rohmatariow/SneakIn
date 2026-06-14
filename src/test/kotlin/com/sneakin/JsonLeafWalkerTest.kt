package com.sneakin

import com.sneakin.core.JsonLeafWalker
import com.sneakin.model.display
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonLeafWalkerTest {

    @Test
    fun walksNestedObjectsAndArraysToLeafPaths() {
        val json = """
            {
              "user": { "address": { "zip": "12345" }, "name": "bob" },
              "items": [ { "id": 1 }, { "id": 2 } ],
              "active": true,
              "note": null
            }
        """.trimIndent()

        val leaves = JsonLeafWalker.leaves(json)!!
        val map = leaves.associate { it.path.display() to it.value }

        assertEquals("12345", map["user.address.zip"])
        assertEquals("bob", map["user.name"])
        assertEquals("1", map["items[0].id"])
        assertEquals("2", map["items[1].id"])
        assertEquals("true", map["active"])
        assertEquals("null", map["note"])
        assertEquals(6, leaves.size)
    }

    @Test
    fun emptyContainersProduceNoLeaves() {
        val leaves = JsonLeafWalker.leaves("""{"a":{},"b":[]}""")!!
        assertTrue(leaves.isEmpty())
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(JsonLeafWalker.leaves("{not json"))
    }
}
