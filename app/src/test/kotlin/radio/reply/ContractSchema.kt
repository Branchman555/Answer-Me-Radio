package radio.reply

import com.google.gson.*
import java.io.File
import java.time.OffsetDateTime

/** Test-only interpreter for the keywords used by the two preserved contracts; not a general validator. */
object ContractSchema {
    fun document(name: String): JsonObject = JsonParser.parseString(File(System.getProperty("designDir"), name).readText()).asJsonObject
    fun accepts(value: JsonElement, schema: JsonObject, root: JsonObject = schema): Boolean = runCatching {
        schema["\$ref"]?.let { ref ->
            var resolved: JsonElement = root
            ref.asString.removePrefix("#/").split('/').forEach { resolved = resolved.asJsonObject[it] }
            require(accepts(value, resolved.asJsonObject, root))
        }
        schema["type"]?.let { type ->
            val types = if (type.isJsonArray) type.asJsonArray.map { it.asString } else listOf(type.asString)
            require(types.any { when (it) {
                "object" -> value.isJsonObject
                "array" -> value.isJsonArray
                "null" -> value.isJsonNull
                "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asBigDecimal.stripTrailingZeros().scale() <= 0
                else -> error("Unsupported contract type $it")
            } })
        }
        schema["const"]?.let { require(value == it) }
        schema["enum"]?.let { require(it.asJsonArray.any { option -> option == value }) }
        schema["anyOf"]?.let { require(it.asJsonArray.any { option -> accepts(value, option.asJsonObject, root) }) }
        schema["allOf"]?.let { require(it.asJsonArray.all { option -> accepts(value, option.asJsonObject, root) }) }
        schema["if"]?.let { condition -> if (accepts(value, condition.asJsonObject, root))
            schema["then"]?.let { require(accepts(value, it.asJsonObject, root)) } }
        if (value.isJsonObject) {
            val obj = value.asJsonObject
            schema["required"]?.asJsonArray?.forEach { require(obj.has(it.asString)) }
            val props = schema.getAsJsonObject("properties") ?: JsonObject()
            if (schema["additionalProperties"]?.isJsonPrimitive == true && !schema["additionalProperties"].asBoolean)
                require(obj.keySet().all { it in props.keySet() })
            props.entrySet().forEach { (key, rule) -> if (obj.has(key)) require(accepts(obj[key], rule.asJsonObject, root)) }
        }
        if (value.isJsonArray) {
            val array = value.asJsonArray
            schema["minItems"]?.let { require(array.size() >= it.asInt) }
            schema["maxItems"]?.let { require(array.size() <= it.asInt) }
            if (schema["uniqueItems"]?.asBoolean == true) require(array.toList().distinct().size == array.size())
            schema["items"]?.let { item -> require(array.all { accepts(it, item.asJsonObject, root) }) }
        }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val str = value.asString
            val length = str.codePointCount(0, str.length)
            schema["minLength"]?.let { require(length >= it.asInt) }
            schema["maxLength"]?.let { require(length <= it.asInt) }
            schema["pattern"]?.let { require(Regex(it.asString).containsMatchIn(str)) }
            if (schema["format"]?.asString == "date-time") OffsetDateTime.parse(str)
        }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            schema["minimum"]?.let { require(value.asBigDecimal >= it.asBigDecimal) }
            schema["maximum"]?.let { require(value.asBigDecimal <= it.asBigDecimal) }
        }
    }.isSuccess
}
