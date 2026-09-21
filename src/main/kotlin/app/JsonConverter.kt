package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.charset.StandardCharsets

/**
 * 把手写 JSON（[JsonValue]/String）接入 Ktor ContentNegotiation。
 * 请求体统一解析为 [JsonValue]，由路由显式映射为具体请求对象。
 */
class JsonConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any,
    ): io.ktor.http.content.OutgoingContent? {
        val text = when (value) {
            is String -> value
            is JsonValue -> JsonCodec.print(value)
            else -> throw ContentConvertException("JsonConverter 只接受 String/JsonValue: $value")
        }
        return io.ktor.http.content.TextContent(
            text,
            contentType.withCharset(charset),
            HttpStatusCode.OK,
        )
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any? {
        val reader = content.toInputStream().bufferedReader(StandardCharsets.UTF_8)
        return JsonCodec.parse(reader.readText())
    }
}
