package dev.f1xtrack.gw6voice.wear

import android.app.*
import android.content.Context
import android.util.AtomicFile
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable data class WatchEntry(val request: VoiceRequest, val audioPath: String = "", val reply: Reply? = null, val published: Boolean = false)
@Serializable data class WatchState(val conversation: String = Protocol.id(), val config: WatchConfig = WatchConfig(), val entries: List<WatchEntry> = emptyList(), val deleted: List<String> = emptyList())

class WatchStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "watch-state.json"))
    private val mutable = MutableStateFlow(if (file.baseFile.exists()) runCatching { file.openRead().use { WireJson.decodeFromString<WatchState>(it.readBytes().toString(Charsets.UTF_8)) } }.getOrElse { WatchState() } else WatchState())
    val state = mutable.asStateFlow()
    @Synchronized private fun update(transform: (WatchState) -> WatchState) {
        val next = transform(mutable.value)
        val stream = file.startWrite()
        try { stream.write(WireJson.encodeToString(next).toByteArray()); file.finishWrite(stream); mutable.value = next }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun newConversation() = update { it.copy(conversation = Protocol.id()) }
    fun selectConversation(id: String) = update { require(Protocol.validId(id)); it.copy(conversation = id) }
    fun config(config: WatchConfig) = update { it.copy(config = config) }
    fun insert(entry: WatchEntry) = update { it.copy(entries = (it.entries.filterNot { old -> old.request.id == entry.request.id } + entry).sortedByDescending { old -> old.request.createdAt }) }
    fun change(id: String, transform: (WatchEntry) -> WatchEntry) = update { it.copy(entries = it.entries.map { entry -> if (entry.request.id == id) transform(entry) else entry }) }
    fun reply(reply: Reply): Boolean {
        var changed = false
        update { state ->
            if (reply.id in state.deleted) return@update state
            val existing = state.entries.firstOrNull { it.request.id == reply.id }
            if (existing?.reply != null && existing.reply.revision >= reply.revision) state
            else {
                changed = existing?.reply?.phase != Phase.DONE && reply.phase == Phase.DONE
                val entry = (existing ?: WatchEntry(VoiceRequest(id = reply.id, conversationId = reply.conversationId, createdAt = reply.createdAt, text = reply.transcript.ifBlank { "Запрос с телефона" }))).copy(reply = reply)
                state.copy(entries = (state.entries.filterNot { it.request.id == reply.id } + entry).sortedByDescending { it.request.createdAt })
            }
        }
        return changed
    }
    fun delete(id: String) = update { state ->
        state.entries.firstOrNull { it.request.id == id }?.audioPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        state.copy(entries = state.entries.filterNot { it.request.id == id }, deleted = (state.deleted + id).takeLast(500))
    }
}

class WatchApp : Application() {
    lateinit var store: WatchStore; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate(); store = WatchStore(this)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel("record", "Запись вопроса", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel("answers", "Ответы", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true); vibrationPattern = longArrayOf(0, 80) })
        }
    }
}
val Context.watchApp get() = applicationContext as WatchApp
