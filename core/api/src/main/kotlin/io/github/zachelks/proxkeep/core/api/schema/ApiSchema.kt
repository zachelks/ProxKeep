package io.github.zachelks.proxkeep.core.api.schema

import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.json.parseLenientBoolean
import io.github.zachelks.proxkeep.core.api.json.parseLenientDouble
import io.github.zachelks.proxkeep.core.api.json.parseLenientInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The kinds of value the API schema describes. */
enum class ParamType {
    String,
    Integer,
    Number,
    Boolean,
    Array,
    Object,
    Null,

    /** A type this client does not know, rendered as text. */
    Other,
    ;

    companion object {
        fun of(name: kotlin.String?): ParamType = when (name) {
            "string" -> String
            "integer" -> Integer
            "number" -> Number
            "boolean" -> Boolean
            "array" -> Array
            "object" -> Object
            "null" -> Null
            else -> Other
        }
    }
}

/**
 * One parameter of an API method, or one key inside a property string.
 *
 * A property string is a single text value made of `key=value` pairs, such as
 * a disk's `local-lvm:vm-100-disk-0,size=32G`. Its keys are in [properties].
 *
 * @property name the key; numbered keys end in `[n]`, such as `net[n]`
 * @property format a named format such as `pve-vmid`, when the schema gives one
 * @property defaultKey whether this key may be written without `key=`, as the
 *   first item of a property string
 * @property alias the key this one is another name for
 * @property keyAlias for keys such as `virtio` in `virtio=BC:24:11:…`: the key
 *   that holds this key's name. The value belongs to [alias].
 */
data class ParamSchema(
    val name: String,
    val type: ParamType,
    val description: String? = null,
    val verboseDescription: String? = null,
    val optional: Boolean = true,
    val default: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val enum: List<String> = emptyList(),
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val format: String? = null,
    val typeText: String? = null,
    val properties: List<ParamSchema> = emptyList(),
    val items: ParamSchema? = null,
    val defaultKey: Boolean = false,
    val alias: String? = null,
    val keyAlias: String? = null,
) {
    /** Whether the key is numbered, like `net[n]`. */
    val isIndexed: Boolean
        get() = name.endsWith(INDEX_SUFFIX)

    /** The key without its `[n]`, such as `net`. */
    val baseName: String
        get() = name.removeSuffix(INDEX_SUFFIX)

    /** Whether the value is a property string with keys of its own. */
    val isPropertyString: Boolean
        get() = type == ParamType.String && properties.isNotEmpty()

    /** Whether this key only stands in for another one and has no field of its own. */
    val isAlias: Boolean
        get() = alias != null

    /** Whether [key] is this parameter, counting numbered keys such as `net0` for `net[n]`. */
    fun matches(key: String): Boolean =
        if (isIndexed) key.startsWith(baseName) && key.length > baseName.length && key.substring(baseName.length).all(Char::isDigit)
        else key == name

    /** The property of a property string named [key]. */
    fun property(key: String): ParamSchema? = properties.firstOrNull { it.name == key }

    companion object {
        const val INDEX_SUFFIX = "[n]"

        internal fun fromJson(name: String, json: JsonObject): ParamSchema {
            val format = json["format"]
            val properties = when {
                format is JsonObject -> format.mapNotNull { (key, value) -> (value as? JsonObject)?.let { fromJson(key, it) } }
                json["properties"] is JsonObject -> (json["properties"] as JsonObject).mapNotNull { (key, value) ->
                    (value as? JsonObject)?.let { fromJson(key, it) }
                }
                else -> emptyList()
            }
            return ParamSchema(
                name = name,
                type = ParamType.of(json.text("type")),
                description = json.text("description"),
                verboseDescription = json.text("verbose_description"),
                optional = json.text("optional")?.let(::parseLenientBoolean) ?: false,
                default = json["default"]?.let(::defaultText),
                minimum = json.text("minimum")?.let(::parseLenientDouble),
                maximum = json.text("maximum")?.let(::parseLenientDouble),
                enum = (json["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                pattern = json.text("pattern"),
                minLength = json.text("minLength")?.let(::parseLenientInt),
                maxLength = json.text("maxLength")?.let(::parseLenientInt),
                format = (format as? JsonPrimitive)?.content,
                typeText = json.text("typetext"),
                properties = properties,
                items = (json["items"] as? JsonObject)?.let { fromJson("items", it) },
                defaultKey = json.text("default_key")?.let(::parseLenientBoolean) ?: false,
                alias = json.text("alias"),
                keyAlias = json.text("keyAlias"),
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        /** Booleans in defaults are written as 0 and 1, the way the server reads them back. */
        private fun defaultText(element: JsonElement): String? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            return when (primitive.content) {
                "true" -> if (primitive.isString) "true" else "1"
                "false" -> if (primitive.isString) "false" else "0"
                else -> primitive.content
            }
        }
    }
}

/**
 * One HTTP method of an API path.
 *
 * @property method `GET`, `POST`, `PUT` or `DELETE`
 * @property permissions the schema's description of who may call it, in English
 */
data class ApiMethod(
    val method: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: List<ParamSchema> = emptyList(),
    val returns: ParamSchema? = null,
    val permissions: String? = null,
) {
    fun parameter(key: String): ParamSchema? =
        parameters.firstOrNull { it.name == key } ?: parameters.firstOrNull { it.isIndexed && it.matches(key) }
}

/** One path of the API, such as `/nodes/{node}/qemu/{vmid}/config`, with its methods. */
data class ApiEndpoint(
    val path: String,
    val methods: Map<String, ApiMethod>,
)

/**
 * The server's description of its own API: every path, method and parameter.
 *
 * Proxmox VE publishes it for its API viewer at
 * `/pve-docs/api-viewer/apidoc.js`. It matches the server's version exactly,
 * so forms built from it offer what that server supports.
 */
class ApiSchema(private val endpoints: Map<String, ApiEndpoint>) {

    val paths: Set<String>
        get() = endpoints.keys

    /** The endpoint for a path template such as `/nodes/{node}/qemu/{vmid}/config`. */
    fun endpoint(path: String): ApiEndpoint? = endpoints[path]

    fun method(path: String, method: String): ApiMethod? = endpoints[path]?.methods?.get(method)

    companion object {
        /**
         * Reads the API viewer's data file, which assigns the schema to a
         * JavaScript variable, or the schema as plain JSON.
         *
         * @throws IllegalArgumentException when [text] holds no schema
         */
        fun parse(text: String): ApiSchema {
            val start = text.indexOf("apiSchema").let { if (it < 0) 0 else it }
            val open = text.indexOf('[', start)
            require(open >= 0) { "No API schema found" }
            val close = matchingBracket(text, open)
            val tree = PveJson.parseToJsonElement(text.substring(open, close + 1))
            require(tree is JsonArray) { "The API schema is not a list" }
            val endpoints = LinkedHashMap<String, ApiEndpoint>()
            tree.forEach { collect(it, endpoints) }
            require(endpoints.isNotEmpty()) { "The API schema is empty" }
            return ApiSchema(endpoints)
        }

        private fun collect(element: JsonElement, into: MutableMap<String, ApiEndpoint>) {
            val node = element as? JsonObject ?: return
            val path = (node["path"] as? JsonPrimitive)?.content
            val info = node["info"] as? JsonObject
            if (path != null && info != null) {
                val methods = info.mapNotNull { (verb, value) -> (value as? JsonObject)?.let { verb to method(verb, it) } }.toMap()
                into[path] = ApiEndpoint(path, methods)
            }
            (node["children"] as? JsonArray)?.forEach { collect(it, into) }
        }

        private fun method(verb: String, json: JsonObject): ApiMethod {
            val parameters = ((json["parameters"] as? JsonObject)?.get("properties") as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { ParamSchema.fromJson(key, it) } }
                .orEmpty()
            val permissions = (json["permissions"] as? JsonObject)?.let { permissions ->
                (permissions["description"] as? JsonPrimitive)?.content
                    ?: permissions["check"]?.toString()
            }
            return ApiMethod(
                method = verb,
                name = (json["name"] as? JsonPrimitive)?.content,
                description = (json["description"] as? JsonPrimitive)?.content,
                parameters = parameters,
                returns = (json["returns"] as? JsonObject)?.let { ParamSchema.fromJson("returns", it) },
                permissions = permissions,
            )
        }

        /** The index of the bracket that closes the one at [open], skipping brackets inside strings. */
        private fun matchingBracket(text: String, open: Int): Int {
            var depth = 0
            var inString = false
            var index = open
            while (index < text.length) {
                val char = text[index]
                if (inString) {
                    when (char) {
                        '\\' -> index++
                        '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '[', '{' -> depth++
                        ']', '}' -> {
                            depth--
                            if (depth == 0) return index
                        }
                    }
                }
                index++
            }
            throw IllegalArgumentException("The API schema is cut off")
        }
    }
}
