package com.sneakin.oracle

import burp.api.montoya.http.message.MimeType
import burp.api.montoya.http.message.responses.HttpResponse
import com.sneakin.core.JsonLeafWalker
import com.sneakin.model.JsonKind
import com.sneakin.model.display

/**
 * A captured summary of a response: status, content-type, body length, response time and — when
 * the body is JSON — its structural shape (path -> kind) and values (path -> string). The shape
 * and values feed the structural comparator and the noise map.
 */
class ResponseFingerprint(
    val statusCode: Int,
    val statusClass: Int,                       // statusCode / 100
    val contentType: String?,
    val isJson: Boolean,
    val bodyLength: Int,
    val responseTimeMs: Long,
    val body: String,
    val jsonShape: Map<String, JsonKind>?,      // null when not JSON / unparseable
    val jsonValues: Map<String, String>?,
) {
    companion object {
        fun of(response: HttpResponse, timeMs: Long): ResponseFingerprint {
            val status = response.statusCode().toInt()
            val contentType = response.headerValue("Content-Type")
            val body = response.bodyToString()
            val json = isJsonResponse(response, contentType, body)

            var shape: Map<String, JsonKind>? = null
            var values: Map<String, String>? = null
            if (json) {
                val leaves = JsonLeafWalker.leaves(body)
                if (leaves != null) {
                    shape = leaves.associate { it.path.display() to it.kind }
                    values = leaves.associate { it.path.display() to it.value }
                }
            }

            return ResponseFingerprint(
                statusCode = status,
                statusClass = status / 100,
                contentType = contentType,
                isJson = json && shape != null,
                bodyLength = response.body().length(),
                responseTimeMs = timeMs,
                body = body,
                jsonShape = shape,
                jsonValues = values,
            )
        }

        private fun isJsonResponse(response: HttpResponse, contentType: String?, body: String): Boolean {
            if (response.mimeType() == MimeType.JSON || response.inferredMimeType() == MimeType.JSON) return true
            if (contentType != null && contentType.contains("json", ignoreCase = true)) return true
            val t = body.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }
    }
}
