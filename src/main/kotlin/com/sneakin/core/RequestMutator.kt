package com.sneakin.core

import burp.api.montoya.http.message.params.HttpParameter
import burp.api.montoya.http.message.requests.HttpRequest
import com.sneakin.model.InjectionPoint
import com.sneakin.model.LocationType
import java.nio.charset.StandardCharsets
import burp.api.montoya.core.ByteArray as BurpByteArray

/**
 * Applies a payload to exactly one injection point on a clone of the seed request (Intruder sniper
 * style). URL/form/cookie params and headers use native Montoya updates; path segments, JSON leaves
 * and multipart parts are rebuilt manually. Returns null if the point can no longer be located.
 */
class RequestMutator(private val log: (String) -> Unit = {}) {

    fun mutate(seed: HttpRequest, point: InjectionPoint, value: String): HttpRequest? = try {
        when (point.locationType) {
            LocationType.QUERY ->
                seed.withUpdatedParameters(HttpParameter.urlParameter(point.name, value))
            LocationType.FORM ->
                seed.withUpdatedParameters(HttpParameter.bodyParameter(point.name, value))
            LocationType.COOKIE ->
                seed.withUpdatedParameters(HttpParameter.cookieParameter(point.name, value))
            LocationType.HEADER ->
                seed.withUpdatedHeader(point.name, value)
            LocationType.PATH -> mutatePath(seed, point, value)
            LocationType.JSON -> mutateJson(seed, point, value)
            LocationType.QUERY_JSON, LocationType.FORM_JSON, LocationType.JSON_IN_STRING ->
                mutateWrapped(seed, point, value)
            LocationType.MULTIPART_FIELD -> mutateMultipart(seed, point, value, filename = false)
            LocationType.MULTIPART_FILENAME -> mutateMultipart(seed, point, value, filename = true)
        }
    } catch (e: Exception) {
        log("Mutation failed for $point: ${e.message}")
        null
    }

    /**
     * Applies several payloads to several points on one request, chained (each mutation feeds the
     * next), to build a single "combined" request that fills every accepted parameter at once.
     * Points that can no longer be located are skipped (logged), never fatal. Safe because the scan
     * early-exits a point on its first VULNERABLE, so each point appears at most once.
     */
    fun mutateAll(seed: HttpRequest, mutations: List<Pair<InjectionPoint, String>>): HttpRequest {
        var req = seed
        for ((point, value) in mutations) {
            val next = mutate(req, point, value)
            if (next == null) log("Combine: skipped $point with '$value' — no longer locatable.")
            else req = next
        }
        return req
    }

    private fun mutatePath(seed: HttpRequest, point: InjectionPoint, value: String): HttpRequest? {
        val segments = seed.pathWithoutQuery().split("/").toMutableList()
        if (point.pathSegmentIndex !in segments.indices) return null
        segments[point.pathSegmentIndex] = pathEncode(value)
        var target = segments.joinToString("/")
        val query = seed.query()
        if (!query.isNullOrEmpty()) target += "?$query"
        return seed.withPath(target)
    }

    private fun mutateJson(seed: HttpRequest, point: InjectionPoint, value: String): HttpRequest? {
        val path = point.jsonPath ?: return null
        val newBody = JsonMutate.set(seed.bodyToString(), path, value, point.jsonKind) ?: return null
        return seed.withBody(newBody)
    }

    /**
     * Mutate one leaf of **wrapped JSON** — a value that is itself a JSON document, nested to any
     * depth. The point carries the unwrap chain ([InjectionPoint.containerName] for an outer
     * url-encoded param, [InjectionPoint.stringWrapPaths] for nested JSON-string leaves) plus the
     * final [InjectionPoint.jsonPath]. Mutation descends to the innermost JSON, sets the leaf, then
     * re-embeds level by level. Operates on raw blobs/strings, so it never depends on Burp's
     * parameter API to decode/encode for us.
     */
    private fun mutateWrapped(seed: HttpRequest, point: InjectionPoint, value: String): HttpRequest? {
        val finalPath = point.jsonPath ?: return null
        val wraps = point.stringWrapPaths
        val container = point.containerName
        if (container != null) {
            val query = point.locationType == LocationType.QUERY_JSON
            val blob = if (query) (seed.query() ?: return null) else seed.bodyToString()
            val newBlob = WrappedJson.rewriteParam(blob, container, wraps, finalPath, value, point.jsonKind)
                ?: return null
            return if (query) seed.withPath(seed.pathWithoutQuery() + "?" + newBlob) else seed.withBody(newBlob)
        }
        val newBody = WrappedJson.setThroughStringWraps(seed.bodyToString(), wraps, finalPath, value, point.jsonKind)
            ?: return null
        return seed.withBody(newBody)
    }

    private fun mutateMultipart(
        seed: HttpRequest,
        point: InjectionPoint,
        value: String,
        filename: Boolean,
    ): HttpRequest? {
        val boundary = MultipartBody.boundaryOf(seed.headerValue("Content-Type")) ?: return null
        val multipart = MultipartBody.parse(seed.body().bytes, boundary) ?: return null
        val index = point.multipartPartIndex
        if (index !in multipart.parts.indices) return null
        val rebuilt = if (filename) multipart.renderWithFilename(index, value)
        else multipart.renderWithFieldValue(index, value)
        // Keep the original Content-Type (boundary) header; Burp recalculates Content-Length.
        return seed.withBody(BurpByteArray.byteArray(*rebuilt))
    }

    /** Percent-encode a path segment: keep unreserved chars, encode everything else as %XX. */
    private fun pathEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isUnreserved()) sb.append(ch) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    private fun Char.isUnreserved(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' ||
            this == '_' || this == '~'
}
