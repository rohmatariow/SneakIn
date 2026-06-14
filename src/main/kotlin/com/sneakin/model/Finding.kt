package com.sneakin.model

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import com.sneakin.oracle.Classification
import com.sneakin.oracle.Verdict

/** One result row: a single mutated request, its response, and the classification. */
class Finding(
    val point: InjectionPoint,
    val payload: Payload,
    val classification: Classification,
    val mutatedRequest: HttpRequest,
    val mutatedResponse: HttpResponse?,
    val baselineRequest: HttpRequest,
    val baselineResponse: HttpResponse?,
    val statusCode: Int,
    val deltaLength: Int,
    val deltaTimeMs: Long,
) {
    val verdict: Verdict get() = classification.verdict
    val parameterName: String get() = point.name
    val locationLabel: String get() = point.locationType.label
    val categoryLabel: String get() = payload.category.label
    val score: Double get() = classification.score
    val signals: String get() = classification.signals.joinToString(", ")

    fun payloadPreview(max: Int = 40): String {
        val flat = payload.value.replace("\n", " ").replace("\r", " ")
        return if (flat.length > max) flat.take(max) + "…" else flat
    }
}
