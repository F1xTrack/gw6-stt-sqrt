package dev.f1xtrack.gw6voice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

val WireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object Protocol {
    const val VERSION = 1
    const val ROOT = "/gw6/v1"
    const val REQUEST = "$ROOT/request/"
    const val RESPONSE = "$ROOT/response/"
    const val COMMAND = "$ROOT/command/"
    const val ACK = "$ROOT/ack/"
    const val CONFIG = "$ROOT/config"
    const val PHONE_CAPABILITY = "gw6_phone_v1"
    const val WATCH_CAPABILITY = "gw6_watch_v1"
    const val MAX_AUDIO = 4_000_000L
    const val MAX_REPLY = 512 * 1024
    fun id() = UUID.randomUUID().toString()
    fun validId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

@Serializable enum class Phase(val label: String) {
    QUEUED("Ожидание телефона"), RECEIVED("Принято телефоном"), STT("Распознавание"),
    LLM("Модель отвечает"), DONE("Готово"), ERROR("Ошибка"),
    UNKNOWN("Результат запроса неизвестен — нужен повтор"), CANCELLED("Отменено");
    val terminal get() = this in setOf(DONE, ERROR, UNKNOWN, CANCELLED)
}

@Serializable data class VoiceRequest(
    val version: Int = Protocol.VERSION,
    val id: String = Protocol.id(),
    val conversationId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val audioMime: String = "audio/mp4",
    val text: String? = null
) {
    fun validate() {
        require(version == Protocol.VERSION) { "Несовместимая версия приложения" }
        require(Protocol.validId(id) && Protocol.validId(conversationId)) { "Некорректный ID" }
        require(audioMime in setOf("audio/mp4", "audio/wav")) { "Неизвестный аудиоформат" }
        require(text == null || text.isNotBlank() && text.length <= 16_000) { "Пустой или слишком длинный вопрос" }
    }
}

@Serializable data class Reply(
    val version: Int = Protocol.VERSION,
    val id: String,
    val conversationId: String,
    val createdAt: Long,
    val phase: Phase,
    val transcript: String = "",
    val answer: AnswerDocument = AnswerDocument(),
    val error: String = "",
    val revision: Long = System.currentTimeMillis()
)

@Serializable data class Command(val requestId: String, val action: String, val id: String = Protocol.id())
@Serializable data class WatchConfig(val wav: Boolean = false)
@Serializable data class Endpoint(
    val url: String = "", val key: String = "", val model: String = "",
    val headers: Map<String, String> = emptyMap(),
    val parameters: String = "{}", val timeoutSeconds: Long = 120
)
@Serializable data class Settings(
    val stt: Endpoint = Endpoint(), val llm: Endpoint = Endpoint(),
    val language: String = "ru", val wav: Boolean = false,
    val contextCharacters: Int = 24_000,
    val instructions: String = ""
)

@Serializable data class AnswerDocument(val version: Int = 1, val blocks: List<AnswerBlock> = emptyList())
@Serializable data class AnswerBlock(
    val type: String, val content: String, val display: Boolean = false,
    val caption: String = ""
)

data class Turn(val question: String, val answer: String)

object Conversation {
    fun context(turns: List<Turn>, maxCharacters: Int): List<Turn> {
        var remaining = maxCharacters.coerceIn(1000, 100_000)
        return turns.asReversed().take(12).takeWhile {
            remaining -= it.question.length + it.answer.length
            remaining >= 0
        }.asReversed()
    }
}
