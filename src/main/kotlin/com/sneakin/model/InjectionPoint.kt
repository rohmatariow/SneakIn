package com.sneakin.model

/** Where in the request a mutable value lives. */
enum class LocationType {
    QUERY, JSON, FORM, MULTIPART_FIELD, MULTIPART_FILENAME, HEADER, COOKIE, PATH,

    // "Wrapped JSON": a value that is itself a JSON document, so we descend into its leaves instead
    // of treating the whole thing as one opaque value. QUERY_JSON/FORM_JSON wrap a url-encoded
    // param value; JSON_IN_STRING wraps a JSON string-leaf whose text is another JSON document.
    QUERY_JSON, FORM_JSON, JSON_IN_STRING;

    val label: String
        get() = when (this) {
            QUERY -> "Query"
            JSON -> "JSON body"
            FORM -> "Form body"
            MULTIPART_FIELD -> "Multipart field"
            MULTIPART_FILENAME -> "Multipart filename"
            HEADER -> "Header"
            COOKIE -> "Cookie"
            PATH -> "Path"
            QUERY_JSON -> "Query JSON"
            FORM_JSON -> "Form JSON"
            JSON_IN_STRING -> "JSON-in-string"
        }
}

/** Inferred shape of a baseline value — drives payload applicability (Section 6.4). */
enum class ValueType { NUMERIC, DATE, EMAIL, UUID, BOOLEAN, EMPTY, STRING }

/** One step in a JSON leaf path: an object key or an array index. */
sealed interface JsonToken {
    data class Key(val key: String) : JsonToken
    data class Index(val index: Int) : JsonToken
}

/** The scalar kind of a JSON leaf — used for structural shape and faithful re-typing on mutation. */
enum class JsonKind { STRING, NUMBER, BOOLEAN, NULL }

/** Render JSON path tokens to a display string, e.g. `user.address.zip`, `items[0].id`. */
fun List<JsonToken>.display(): String {
    val sb = StringBuilder()
    for (t in this) {
        when (t) {
            is JsonToken.Key -> {
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(t.key)
            }
            is JsonToken.Index -> sb.append('[').append(t.index).append(']')
        }
    }
    return sb.toString()
}

/**
 * One mutable location in the request. Records its location type, identifying name/path, original
 * value and inferred type, plus the metadata [RequestMutator] needs to set a new value back into a
 * cloned request (JSON path, path-segment index, multipart part index).
 */
class InjectionPoint(
    val locationType: LocationType,
    val name: String,
    val originalValue: String,
    val valueType: ValueType,
    val jsonPath: List<JsonToken>? = null,
    val jsonKind: JsonKind? = null,
    val pathSegmentIndex: Int = -1,
    val multipartPartIndex: Int = -1,
    /** For QUERY_JSON / FORM_JSON: the name of the wrapping url-encoded parameter (the outer frame). */
    val containerName: String? = null,
    /**
     * Wrapped-JSON unwrap chain: the paths of nested JSON string-leaves (each carrying JSON), from
     * outermost to innermost, relative to each successive JSON text. Empty for a non-wrapped leaf and
     * for a param value that holds JSON directly; [jsonPath] then targets the innermost JSON.
     */
    val stringWrapPaths: List<List<JsonToken>> = emptyList(),
) {
    /** User selection from the Targets table; default all-selected. */
    var selected: Boolean = true

    /**
     * Marked by the user as "must be unique per request" (account no, email, reference id...). When
     * set, the engine rotates this value (original + an incrementing counter) on every request so a
     * server-side uniqueness check never rejects the repeats and masks the real validation result.
     */
    var unique: Boolean = false

    override fun toString(): String = "${locationType.label}:$name"
}

/** Infers a [ValueType] from a baseline value string via regex (Section 6.4). */
object TypeInference {
    private val NUMERIC = Regex("^-?\\d+(?:\\.\\d+)?$")
    private val DATE = Regex(
        "^(?:\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?" +
            "|\\d{2}/\\d{2}/\\d{4}|\\d{2}-\\d{2}-\\d{4})$"
    )
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val UUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val BOOLEAN = Regex("^(?:true|false)$", RegexOption.IGNORE_CASE)

    fun infer(value: String): ValueType {
        val v = value.trim()
        return when {
            v.isEmpty() -> ValueType.EMPTY
            NUMERIC.matches(v) -> ValueType.NUMERIC
            BOOLEAN.matches(v) -> ValueType.BOOLEAN
            UUID.matches(v) -> ValueType.UUID
            EMAIL.matches(v) -> ValueType.EMAIL
            DATE.matches(v) -> ValueType.DATE
            else -> ValueType.STRING
        }
    }

    /** True if a recognizable format is present (date/email/uuid/numeric) — Bad-Format applies. */
    fun hasFormat(t: ValueType): Boolean =
        t == ValueType.DATE || t == ValueType.EMAIL || t == ValueType.UUID || t == ValueType.NUMERIC
}
