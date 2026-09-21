package app

/**
 * 极简 JSON：值模型 + 解析器 + 打印器（无反射、无第三方依赖）。
 * 业务模型通过显式 encode/decode 函数与 [JsonValue] 互转。
 */
sealed class JsonValue {
    data class Obj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()

    val asString: String get() = (this as Str).value
    val asDouble: Double get() = (this as Num).value
    val asLong: Long get() = (this as Num).value.toLong()
    val asInt: Int get() = (this as Num).value.toInt()
    val asBoolean: Boolean get() = (this as Bool).value
    val asArray: List<JsonValue> get() = (this as Arr).items
    fun field(name: String): JsonValue = (this as Obj).entries[name] ?: JsonValue.Null
}

object JsonCodec {
    fun obj(vararg pairs: Pair<String, Any?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        for ((k, v) in pairs) map[k] = toJson(v)
        return JsonValue.Obj(map)
    }

    @Suppress("UNCHECKED_CAST")
    fun toJson(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Enum<*> -> JsonValue.Str(value.name)
        is DoubleArray -> JsonValue.Arr(value.map { JsonValue.Num(it) })
        is IntArray -> JsonValue.Arr(value.map { JsonValue.Num(it.toDouble()) })
        is BooleanArray -> JsonValue.Arr(value.map { JsonValue.Bool(it) })
        is LongArray -> JsonValue.Arr(value.map { JsonValue.Num(it.toDouble()) })
        is Array<*> -> JsonValue.Arr(value.map { toJson(it) })
        is List<*> -> JsonValue.Arr(value.map { toJson(it) })
        is Map<*, *> -> JsonValue.Obj(
            LinkedHashMap<String, JsonValue>().also { m ->
                value.forEach { (k, v) -> m[k.toString()] = toJson(v) }
            }
        )
        else -> error("不支持自动编码的类型: ${value::class}")
    }

    fun print(v: JsonValue): String {
        val sb = StringBuilder()
        write(sb, v)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: JsonValue) {
        when (v) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is JsonValue.Num -> {
                val d = v.value
                when {
                    !d.isFinite() -> sb.append("null")
                    d == d.toLong().toDouble() -> sb.append(d.toLong())
                    else -> sb.append(d)
                }
            }
            is JsonValue.Str -> writeString(sb, v.value)
            is JsonValue.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                v.entries.entries.forEachIndexed { i, (k, item) ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): JsonValue = Parser(text).parseValue()

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseValue(): JsonValue {
            skipWs()
            if (pos >= s.length) error("意外的 JSON 结束")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') { pos++; return JsonValue.Obj(map) }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> error("对象中应为逗号或右花括号，位置 $pos")
                }
            }
            return JsonValue.Obj(map)
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val list = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') { pos++; return JsonValue.Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> error("数组中应为逗号或右方括号，位置 $pos")
                }
            }
            return JsonValue.Arr(list)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("非法转义")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("字符串未闭合")
        }

        private fun parseBool(): JsonValue.Bool {
            return if (s.startsWith("true", pos)) { pos += 4; JsonValue.Bool(true) }
            else { require(s.startsWith("false", pos)); pos += 5; JsonValue.Bool(false) }
        }

        private fun parseNull(): JsonValue {
            require(s.startsWith("null", pos))
            pos += 4
            return JsonValue.Null
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return JsonValue.Num(s.substring(start, pos).toDouble())
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else ' '
        private fun expect(c: Char) {
            skipWs()
            require(pos < s.length && s[pos] == c) { "期望 $c，位置 $pos" }
            pos++
        }
    }
}
