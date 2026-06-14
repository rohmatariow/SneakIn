package com.sneakin.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sneakin.model.Finding

/** A Montoya-free, exportable snapshot of one finding. */
data class FindingRow(
    val parameter: String,
    val location: String,
    val payloadCategory: String,
    val payload: String,
    val status: Int,
    val deltaLength: Int,
    val deltaTimeMs: Long,
    val verdict: String,
    val subLabel: String,
    val score: Double,
    val signals: String,
    val reasons: String,
    val baselineRequest: String,
    val baselineResponse: String,
    val mutatedRequest: String,
    val mutatedResponse: String,
)

/** Builds CSV and JSON exports of the results (Section 11). */
object Exporter {

    private val CSV_HEADER = listOf(
        "Parameter", "Location", "Payload Category", "Payload", "Status",
        "Delta Length", "Delta Time (ms)", "Verdict", "Sub-label", "Score", "Signals", "Reasons"
    )

    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun toCsv(rows: List<FindingRow>): String {
        val sb = StringBuilder()
        sb.append(csvLine(CSV_HEADER)).append('\n')
        for (r in rows) {
            sb.append(
                csvLine(
                    listOf(
                        r.parameter, r.location, r.payloadCategory, r.payload, r.status.toString(),
                        r.deltaLength.toString(), r.deltaTimeMs.toString(), r.verdict, r.subLabel,
                        String.format("%.3f", r.score), r.signals, r.reasons
                    )
                )
            ).append('\n')
        }
        return sb.toString()
    }

    /** Structured findings including baseline + mutated request/response and classification reasons. */
    fun toJson(rows: List<FindingRow>): String {
        val array = JsonArray()
        for (r in rows) {
            val o = JsonObject()
            o.addProperty("parameter", r.parameter)
            o.addProperty("location", r.location)
            o.addProperty("payloadCategory", r.payloadCategory)
            o.addProperty("payload", r.payload)
            o.addProperty("status", r.status)
            o.addProperty("deltaLength", r.deltaLength)
            o.addProperty("deltaTimeMs", r.deltaTimeMs)
            o.addProperty("verdict", r.verdict)
            o.addProperty("subLabel", r.subLabel)
            o.addProperty("score", r.score)
            o.addProperty("signals", r.signals)
            o.addProperty("reasons", r.reasons)
            val baseline = JsonObject()
            baseline.addProperty("request", r.baselineRequest)
            baseline.addProperty("response", r.baselineResponse)
            o.add("baseline", baseline)
            val mutated = JsonObject()
            mutated.addProperty("request", r.mutatedRequest)
            mutated.addProperty("response", r.mutatedResponse)
            o.add("mutated", mutated)
            array.add(o)
        }
        return gson.toJson(array)
    }

    private fun csvLine(fields: List<String>): String = fields.joinToString(",") { escapeCsv(it) }

    private fun escapeCsv(field: String): String {
        val needsQuote = field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')
        val escaped = field.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }
}

/** Snapshot a live finding into an exportable, Montoya-free row. */
fun Finding.toRow(): FindingRow = FindingRow(
    parameter = parameterName,
    location = locationLabel,
    payloadCategory = categoryLabel,
    payload = payload.value,
    status = statusCode,
    deltaLength = deltaLength,
    deltaTimeMs = deltaTimeMs,
    verdict = verdict.name,
    subLabel = classification.subLabel,
    score = score,
    signals = signals,
    reasons = classification.reasons.joinToString(" "),
    baselineRequest = baselineRequest.toString(),
    baselineResponse = baselineResponse?.toString() ?: "",
    mutatedRequest = mutatedRequest.toString(),
    mutatedResponse = mutatedResponse?.toString() ?: "",
)

