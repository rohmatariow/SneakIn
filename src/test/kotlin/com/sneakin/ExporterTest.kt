package com.sneakin

import com.google.gson.JsonParser
import com.sneakin.core.Exporter
import com.sneakin.core.FindingRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExporterTest {

    private fun row(
        parameter: String = "amount",
        reasons: String = "ok",
        payload: String = "-1",
    ) = FindingRow(
        parameter = parameter, location = "JSON body", payloadCategory = "Negative",
        payload = payload, status = 200, deltaLength = -3, deltaTimeMs = 5,
        verdict = "VULNERABLE", subLabel = "Negative Accepted", score = 0.83,
        signals = "reflected", reasons = reasons,
        baselineRequest = "GET / HTTP/1.1", baselineResponse = "HTTP/1.1 200 OK",
        mutatedRequest = "GET /?x=-1 HTTP/1.1", mutatedResponse = "HTTP/1.1 200 OK",
    )

    @Test
    fun csvHasHeaderAndEscapesSpecialChars() {
        val csv = Exporter.toCsv(listOf(row(reasons = "accepted, see \"note\"\nline2")))
        val lines = csv.trimEnd().split("\n")
        assertTrue(lines[0].startsWith("Parameter,Location,Payload Category"))
        // the reasons field contains comma, quote and newline -> must be wrapped in quotes with doubled quotes
        assertTrue(csv.contains("\"accepted, see \"\"note\"\"\nline2\""))
    }

    @Test
    fun csvRowHasAllTwelveColumns() {
        val csv = Exporter.toCsv(listOf(row()))
        val dataLine = csv.trimEnd().split("\n")[1]
        // simple row has no quoting, so a plain split by comma yields 12 columns
        assertEquals(12, dataLine.split(",").size)
    }

    @Test
    fun jsonIsStructuredWithBaselineAndMutated() {
        val json = Exporter.toJson(listOf(row()))
        val arr = JsonParser.parseString(json).asJsonArray
        assertEquals(1, arr.size())
        val o = arr[0].asJsonObject
        assertEquals("amount", o.get("parameter").asString)
        assertEquals("VULNERABLE", o.get("verdict").asString)
        assertEquals("Negative Accepted", o.get("subLabel").asString)
        assertEquals(200, o.get("status").asInt)
        assertEquals("GET / HTTP/1.1", o.getAsJsonObject("baseline").get("request").asString)
        assertEquals("GET /?x=-1 HTTP/1.1", o.getAsJsonObject("mutated").get("request").asString)
    }
}
