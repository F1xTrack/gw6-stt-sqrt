package dev.f1xtrack.gw6voice.phone

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.*
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.io.File

object PhoneTransfer {
    suspend fun publish(context: Context, job: PhoneJob) {
        val payload = WireJson.encodeToString(job.reply()).toByteArray()
        require(payload.size <= 1024 * 1024)
        val request = PutDataMapRequest.create(Protocol.RESPONSE + job.request.id).apply {
            dataMap.putAsset("reply", Asset.createFromBytes(payload)); dataMap.putLong("revision", job.revision)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
    suspend fun config(context: Context, settings: Settings) {
        val request = PutDataMapRequest.create(Protocol.CONFIG).apply { dataMap.putString("json", WireJson.encodeToString(WatchConfig(settings.wav))) }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
    suspend fun receive(context: Context, item: DataItem) {
        val path = item.uri.path.orEmpty()
        val store = context.phoneApp.jobs
        val map = DataMapItem.fromDataItem(item).dataMap
        when {
            path.startsWith(Protocol.REQUEST) -> {
                val encoded = map.getString("json") ?: return
                require(encoded.length <= 32_000)
                val request = WireJson.decodeFromString<VoiceRequest>(encoded).also { it.validate() }
                require(path == Protocol.REQUEST + request.id)
                val existing = store.get(request.id)
                if (existing != null) {
                    publish(context, existing)
                    if (!existing.phase.terminal) PipelineWorker.enqueue(context, request)
                    Wearable.getDataClient(context).deleteDataItems(item.uri).await(); return
                }
                val dir = File(context.filesDir, "audio").apply { mkdirs() }
                val target = File(dir, request.id + if (request.audioMime == "audio/wav") ".wav" else ".m4a")
                if (request.text == null) {
                    val asset = map.getAsset("audio") ?: error("Нет аудио")
                    val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
                    try {
                        response.inputStream.use { input ->
                            val part = File(dir, request.id + ".partial")
                            try {
                                part.outputStream().use { output ->
                                    val buffer = ByteArray(8192); var bytes = 0L
                                    while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= Protocol.MAX_AUDIO); output.write(buffer, 0, n) }
                                    require(bytes > 44) { "Пустое аудио" }
                                }
                                require(part.renameTo(target)) { "Не удалось сохранить аудио" }
                            } finally { File(dir, request.id + ".partial").delete() }
                        }
                    } finally { response.release() }
                }
                val job = PhoneJob(request, audioPath = if (request.text == null) target.path else "")
                store.insert(job)
                PipelineWorker.enqueue(context, request)
                publish(context, store.get(request.id) ?: job)
                Wearable.getDataClient(context).deleteDataItems(item.uri).await()
            }
            path.startsWith(Protocol.COMMAND) -> {
                val command = WireJson.decodeFromString<Command>(map.getString("json") ?: return)
                require(Protocol.validId(command.requestId) && Protocol.validId(command.id))
                PhoneActions.command(context, command)
                Wearable.getDataClient(context).deleteDataItems(item.uri).await()
            }
            path.startsWith(Protocol.ACK) -> {
                val id = path.removePrefix(Protocol.ACK)
                if (!Protocol.validId(id)) return
                val job = store.get(id)
                if (job != null && job.phase.terminal && map.getLong("revision") == job.revision) {
                    Wearable.getDataClient(context).getDataItems().await().use { items ->
                        items.filter { it.uri.path == Protocol.RESPONSE + id }.forEach { Wearable.getDataClient(context).deleteDataItems(it.uri).await() }
                    }
                }
                Wearable.getDataClient(context).deleteDataItems(item.uri).await()
            }
        }
    }
    suspend fun rescan(context: Context) {
        Wearable.getDataClient(context).getDataItems().await().use { items ->
            items.filter { it.uri.path.orEmpty().startsWith(Protocol.REQUEST) || it.uri.path.orEmpty().startsWith(Protocol.COMMAND) || it.uri.path.orEmpty().startsWith(Protocol.ACK) }
                .forEach { runCatching { receive(context, it.freeze()) } }
        }
    }
}

object PhoneActions {
    suspend fun command(context: Context, command: Command) {
        val store = context.phoneApp.jobs
        val job = store.get(command.requestId) ?: return
        if (command.id in job.handledCommands) return
        val next = store.change(job.request.id) { current ->
            if (command.id in current.handledCommands) current
            else {
                val marked = current.copy(handledCommands = (current.handledCommands + command.id).takeLast(100))
                when {
                    command.action == "cancel" && !current.phase.terminal -> marked.copy(phase = Phase.CANCELLED, inFlight = false, error = "")
                    command.action == "retry" && current.phase in setOf(Phase.ERROR, Phase.UNKNOWN, Phase.CANCELLED) -> marked.copy(phase = Phase.RECEIVED, inFlight = false, error = "", attempt = current.attempt + 1, rawAnswer = "", answer = AnswerDocument(), formatRepairAttempted = false)
                    else -> marked
                }
            }
        } ?: return
        if (command.action == "cancel") ActiveCalls.cancel(job.request.id)
        if (next.phase == Phase.RECEIVED && command.action == "retry") PipelineWorker.enqueue(context, next.request)
        runCatching { PhoneTransfer.publish(context, next) }
    }
}

class PhoneListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking(Dispatchers.IO) {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
                runCatching { PhoneTransfer.receive(this@PhoneListener, event.dataItem.freeze()) }
            }
        }
    }
}
