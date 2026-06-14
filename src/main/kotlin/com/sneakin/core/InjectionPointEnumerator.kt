package com.sneakin.core

import burp.api.montoya.http.message.ContentType
import burp.api.montoya.http.message.params.HttpParameterType
import burp.api.montoya.http.message.requests.HttpRequest
import com.sneakin.model.InjectionPoint
import com.sneakin.model.JsonKind
import com.sneakin.model.JsonToken
import com.sneakin.model.LocationType
import com.sneakin.model.TypeInference
import com.sneakin.model.display
import java.nio.charset.StandardCharsets

/**
 * Parses a seed request into a flat list of mutable injection points: query params, JSON leaf
 * paths (nested), form fields, and multipart field values + filenames. Headers, cookies and path
 * segments are intentionally out of scope (SneakIn targets parameters only). Body type is taken
 * from Burp's [ContentType] with a content-sniff fallback.
 *
 * **Wrapped JSON** is unwrapped automatically (via [WrappedJson]): a query/form param whose value is
 * a JSON document, or a JSON string-leaf whose text is a JSON document, is descended into so each
 * inner leaf (object/array/mixed, any depth) becomes its own target instead of one opaque blob.
 */
class InjectionPointEnumerator(private val log: (String) -> Unit) {

    fun enumerate(request: HttpRequest): List<InjectionPoint> {
        val points = ArrayList<InjectionPoint>()
        runCatching { enumerateQuery(request, points) }.onFailure { log("Query parse error: ${it.message}") }
        runCatching { enumerateBody(request, points) }.onFailure { log("Body parse error: ${it.message}") }
        return points
    }

    private fun enumerateQuery(request: HttpRequest, out: MutableList<InjectionPoint>) {
        for (p in request.parameters(HttpParameterType.URL)) {
            val container = WrappedJson.container(p.value())
            if (container != null) walkWrapped(container, p.name(), isQuery = true, emptyList(), "${p.name()} › ", out)
            else out.add(point(LocationType.QUERY, p.name(), p.value()))
        }
    }

    private fun enumerateBody(request: HttpRequest, out: MutableList<InjectionPoint>) {
        val body = request.body()
        if (body.length() == 0) return

        when (request.contentType()) {
            ContentType.URL_ENCODED -> enumerateForm(request, out)
            ContentType.JSON -> enumerateJson(request.bodyToString(), out)
            ContentType.MULTIPART -> enumerateMultipart(request, out)
            ContentType.XML -> log("XML body: out of scope for v1, skipping body injection points.")
            else -> sniffBody(request, out)
        }
    }

    /** No declared content-type: sniff a JSON-looking body, otherwise skip. */
    private fun sniffBody(request: HttpRequest, out: MutableList<InjectionPoint>) {
        val text = request.bodyToString().trim()
        if (text.startsWith("{") || text.startsWith("[")) {
            enumerateJson(text, out)
        } else if (request.hasParameters(HttpParameterType.BODY)) {
            enumerateForm(request, out)
        } else {
            log("Body content-type unrecognized; no body injection points enumerated.")
        }
    }

    private fun enumerateForm(request: HttpRequest, out: MutableList<InjectionPoint>) {
        for (p in request.parameters(HttpParameterType.BODY)) {
            val container = WrappedJson.container(p.value())
            if (container != null) walkWrapped(container, p.name(), isQuery = false, emptyList(), "${p.name()} › ", out)
            else out.add(point(LocationType.FORM, p.name(), p.value()))
        }
    }

    private fun enumerateJson(text: String, out: MutableList<InjectionPoint>) {
        if (JsonLeafWalker.leaves(text) == null) {
            log("Malformed JSON body; no JSON injection points enumerated.")
            return
        }
        walkWrapped(text, containerName = null, isQuery = false, emptyList(), prefix = "", out)
    }

    /**
     * Walk one JSON text to its leaves, recursing into any string-leaf that is itself a JSON document
     * (wrapping nests to any depth). [containerName]/[isQuery] describe an outer url-encoded param
     * frame (null = the value lives in the body JSON directly); [stringWraps] is the chain of JSON
     * string-leaf paths descended so far; [prefix] is the display breadcrumb (`post › data › `).
     */
    private fun walkWrapped(
        jsonText: String,
        containerName: String?,
        isQuery: Boolean,
        stringWraps: List<List<JsonToken>>,
        prefix: String,
        out: MutableList<InjectionPoint>,
    ) {
        val leaves = JsonLeafWalker.leaves(jsonText) ?: return
        for (leaf in leaves) {
            if (leaf.kind == JsonKind.STRING) {
                val inner = WrappedJson.rawContainer(leaf.value)
                if (inner != null) {
                    walkWrapped(
                        inner, containerName, isQuery, stringWraps + listOf(leaf.path),
                        "$prefix${leaf.path.display()} › ", out,
                    )
                    continue
                }
            }
            val type = when {
                containerName != null && isQuery -> LocationType.QUERY_JSON
                containerName != null -> LocationType.FORM_JSON
                stringWraps.isNotEmpty() -> LocationType.JSON_IN_STRING
                else -> LocationType.JSON
            }
            out.add(
                InjectionPoint(
                    type, "$prefix${innerName(leaf.path)}", leaf.value, TypeInference.infer(leaf.value),
                    jsonPath = leaf.path, jsonKind = leaf.kind,
                    containerName = containerName, stringWrapPaths = stringWraps,
                )
            )
        }
    }

    private fun innerName(path: List<JsonToken>): String =
        if (path.isEmpty()) "(root)" else path.display()

    private fun enumerateMultipart(request: HttpRequest, out: MutableList<InjectionPoint>) {
        val boundary = MultipartBody.boundaryOf(request.headerValue("Content-Type"))
        if (boundary == null) {
            log("Multipart body without a boundary in Content-Type; skipping.")
            return
        }
        val parsed = MultipartBody.parse(request.body().bytes, boundary)
        if (parsed == null) {
            log("Could not parse multipart body against boundary '$boundary'; skipping.")
            return
        }
        parsed.parts.forEachIndexed { i, part ->
            val fieldName = part.name ?: "part$i"
            if (part.filename != null) {
                // file part -> inject only into the filename string (binary content untouched)
                out.add(
                    point(
                        LocationType.MULTIPART_FILENAME, fieldName, part.filename, multipartPartIndex = i
                    )
                )
            } else {
                val value = String(part.value, StandardCharsets.UTF_8)
                out.add(point(LocationType.MULTIPART_FIELD, fieldName, value, multipartPartIndex = i))
            }
        }
    }

    private fun point(
        type: LocationType,
        name: String,
        value: String,
        multipartPartIndex: Int = -1,
    ) = InjectionPoint(
        locationType = type,
        name = name,
        originalValue = value,
        valueType = TypeInference.infer(value),
        multipartPartIndex = multipartPartIndex,
    )
}
