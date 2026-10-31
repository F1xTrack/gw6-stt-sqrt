package dev.f1xtrack.gw6voice.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val safeMessage: String, val uncertain: Boolean = false, cause: Throwable? = null) : IOException(safeMessage, cause)

class CloudApi(
    private val base: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val onCall: (Call) -> Unit = {}
) {

    fun transcribe(settings: Settings, file: File, mime: String): String {
        val endpoint = settings.stt
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", endpoint.model)
            .addFormDataPart("file", if (mime == "audio/wav") "voice.wav" else "voice.m4a", file.asRequestBody(mime.toMediaType()))
        if (settings.language.isNotBlank()) multipart.addFormDataPart("language", settings.language)
        extras(endpoint).forEach { (name, value) ->
            require(name !in setOf("file", "model")) { "Параметр $name задаётся отдельно" }
            multipart.addFormDataPart(name, if (value is JsonPrimitive) value.content else value.toString())
        }
        val response = execute(endpoint, multipart.build())
        val obj = runCatching { WireJson.parseToJsonElement(response).jsonObject }.getOrElse { throw ApiFailure("STT вернул некорректный JSON") }
        return obj["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it.length <= 16_000 }
            ?: throw ApiFailure("STT не вернул текст речи")
    }

    fun answer(settings: Settings, question: String, history: List<Turn>, repair: String? = null): String {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT + "\n" + settings.instructions) })
            Conversation.context(history, settings.contextCharacters).forEach { turn ->
                add(buildJsonObject { put("role", "user"); put("content", turn.question) })
                add(buildJsonObject { put("role", "assistant"); put("content", turn.answer) })
            }
            add(buildJsonObject { put("role", "user"); put("content", question) })
            if (repair != null) {
                add(buildJsonObject { put("role", "assistant"); put("content", repair.take(100_000)) })
                add(buildJsonObject { put("role", "user"); put("content", "Исправь только формат своего ответа: LaTeX в математических разделителях; SVG в блоке ```svg, с viewBox и только статическими фигурами и атрибутами оформления. Без CSS, use, скриптов и внешних ресурсов. Сохрани смысл и числа. Верни весь исправленный ответ.") })
            }
        }
        val body = buildJsonObject {
            extras(settings.llm).forEach { (name, value) -> require(name !in setOf("model", "messages", "stream")) { "Параметр $name задаётся приложением" }; put(name, value) }
            put("model", settings.llm.model); put("messages", messages); put("stream", false)
        }
        val result = execute(settings.llm, body.toString().toRequestBody("application/json".toMediaType()))
        return runCatching {
            val content = WireJson.parseToJsonElement(result).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!
            val text = if (content is JsonArray) content.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() } else content.jsonPrimitive.content
            text.trim().takeIf { it.isNotBlank() } ?: error("empty")
        }.getOrElse { throw ApiFailure("Модель не вернула текст в choices[0].message.content") }
    }

    fun validate(endpoint: Endpoint) {
        val url = runCatching { endpoint.url.trim().toHttpUrl() }.getOrElse { throw IllegalArgumentException("Нужен полный URL метода API") }
        require(url.scheme == "https") { "Для облачного API нужен HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Уберите пароль и фрагмент из URL" }
        require(endpoint.model.isNotBlank()) { "Укажите модель" }
        require(endpoint.timeoutSeconds in 10..600) { "Тайм-аут: 10–600 секунд" }
        endpoint.headers.forEach { (key, value) -> require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains('\n') && !value.contains('\r')) { "Некорректные дополнительные заголовки" } }
        extras(endpoint)
    }

    private fun extras(endpoint: Endpoint): JsonObject = runCatching {
        WireJson.parseToJsonElement(endpoint.parameters.ifBlank { "{}" }).jsonObject
    }.getOrElse { throw IllegalArgumentException("Дополнительные параметры должны быть JSON-объектом") }

    private fun execute(endpoint: Endpoint, body: RequestBody): String {
        validate(endpoint)
        val request = Request.Builder().url(endpoint.url.trim()).post(body).apply {
            if (endpoint.key.isNotBlank()) header("Authorization", "Bearer ${endpoint.key.trim()}")
            endpoint.headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = base.newBuilder().callTimeout(endpoint.timeoutSeconds, TimeUnit.SECONDS).readTimeout(endpoint.timeoutSeconds, TimeUnit.SECONDS).build().newCall(request)
        onCall(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure(when (response.code) {
                    401, 403 -> "API отклонил ключ (${response.code})"
                    404 -> "Метод или модель не найдены (404)"
                    429 -> "Лимит API (429). Повторите позже"
                    else -> "API вернул HTTP ${response.code}"
                }, response.code >= 500)
                val stream = response.body?.byteStream() ?: throw ApiFailure("Пустой ответ API")
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; if (out.size() + count > 2 * 1024 * 1024) throw ApiFailure("Ответ API больше 2 МБ"); out.write(buffer, 0, count) }
                return out.toString("UTF-8")
            }
        } catch (e: ApiFailure) { throw e }
        catch (error: IOException) { throw ApiFailure("Соединение прервано. Сервер мог обработать запрос", true, error) }
    }

    companion object {
        val SYSTEM_PROMPT = """
            Ты голосовой помощник на круглых часах. Отвечай на языке пользователя.
            Сначала дай результат, затем короткие понятные шаги. Помни контекст беседы.
            Вопрос получен распознаванием речи: если неоднозначны скобки, область корня,
            дробь, степень или обозначение, задай короткий уточняющий вопрос вместо угадывания.
            Математику всегда оформляй LaTeX: встроенная \\( ... \\), отдельная \\[ ... \\].
            Используй \\sqrt{...}, \\frac{...}{...}, индексы и степени, а не sqrt() и ASCII-дроби.
            Для длинных решений применяй короткие формулы и aligned; не используй пользовательские макросы.
            При необходимости рисунка верни настоящий самодостаточный SVG в отдельном fenced блоке svg.
            SVG: viewBox="0 0 320 260", тёмный фон #101820, светлые линии, крупные подписи >=18,
            контрастные акценты, поля 16 единиц. На часах нет масштабирования.
            Только svg,g,defs,path,rect,circle,ellipse,line,polyline,polygon,text,tspan,title,desc,
            marker,clipPath,linearGradient,radialGradient,stop. Оформление атрибутами fill/stroke,
            без style/CSS, use, HTML, скриптов, анимации, изображений и внешних ресурсов.
            Для простых математических подписей SVG используй Unicode √, π, ²; сложные формулы вынеси рядом в LaTeX.
            Не выдавай выдуманные графики за точные вычисления. Не вставляй SVG как URL или картинку Markdown.
        """.trimIndent().replace("\\\\", "\\")
    }
}
