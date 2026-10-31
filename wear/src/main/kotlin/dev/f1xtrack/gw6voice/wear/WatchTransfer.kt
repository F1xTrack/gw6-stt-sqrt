package dev.f1xtrack.gw6voice.wear

import android.app.*
import android.content.*
import com.google.android.gms.wearable.*
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.io.File

object WatchTransfer {
    suspend fun send(context: Context, entry: WatchEntry) {
        entry.request.validate()
        val request = PutDataMapRequest.create(Protocol.REQUEST + entry.request.id).apply {
            dataMap.putString("json", WireJson.encodeToString(entry.request)); dataMap.putLong("resentAt", System.currentTimeMillis())
            if (entry.request.text == null) {
                val file = File(entry.audioPath)
                require(file.exists() && file.length() <= Protocol.MAX_AUDIO && file.length() > 44) { "Запись недоступна" }
                dataMap.putAsset("audio", Asset.createFromFd(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)))
            }
        }.asPutDataRequest().setUrgent()
        // GMS copies the asset before this task completes. Close the source descriptor afterwards.
        try { Wearable.getDataClient(context).putDataItem(request).await(); context.watchApp.store.change(entry.request.id) { it.copy(published = true) } }
        finally { request.assets.values.forEach { it.fd?.close() } }
    }
    suspend fun command(context: Context, id: String, action: String) {
        val command = Command(id, action)
        Wearable.getDataClient(context).putDataItem(PutDataMapRequest.create(Protocol.COMMAND + command.id).apply {
            dataMap.putString("json", WireJson.encodeToString(command))
        }.asPutDataRequest().setUrgent()).await()
    }
    suspend fun receive(context: Context, item: DataItem) {
        val path = item.uri.path.orEmpty()
        val map = DataMapItem.fromDataItem(item).dataMap
        if (path == Protocol.CONFIG) { context.watchApp.store.config(WireJson.decodeFromString<WatchConfig>(map.getString("json") ?: return)); return }
        if (!path.startsWith(Protocol.RESPONSE)) return
        val asset = map.getAsset("reply") ?: return
        val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
        val reply = try {
            response.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1024 * 1024); out.write(buffer, 0, n) }
                WireJson.decodeFromString<Reply>(out.toString("UTF-8"))
            }
        } finally { response.release() }
        require(reply.version == Protocol.VERSION && Protocol.validId(reply.id) && Protocol.validId(reply.conversationId) && path == Protocol.RESPONSE + reply.id)
        val newAnswer = context.watchApp.store.reply(reply)
        if (reply.transcript.isNotBlank() || reply.phase == Phase.CANCELLED) {
            context.watchApp.store.change(reply.id) { entry -> if (entry.audioPath.isNotBlank()) File(entry.audioPath).delete(); entry.copy(audioPath = "") }
        }
        if (reply.phase.terminal) {
            Wearable.getDataClient(context).putDataItem(PutDataMapRequest.create(Protocol.ACK + reply.id).apply { dataMap.putLong("revision", reply.revision) }.asPutDataRequest().setUrgent()).await()
        }
        if (newAnswer) {
            val intent = PendingIntent.getActivity(context, reply.id.hashCode(), Intent(context, MainActivity::class.java).putExtra("requestId", reply.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { context.getSystemService(NotificationManager::class.java).notify(reply.id.hashCode(), Notification.Builder(context, "answers")
                .setSmallIcon(dev.f1xtrack.gw6voice.render.R.drawable.ic_voice).setContentTitle("Ответ готов").setContentText(reply.transcript.take(80))
                .setContentIntent(intent).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()) }
        }
    }
    suspend fun sync(context: Context) {
        Wearable.getDataClient(context).getDataItems().await().use { items ->
            items.filter { it.uri.path == Protocol.CONFIG || it.uri.path.orEmpty().startsWith(Protocol.RESPONSE) }.forEach { runCatching { receive(context, it.freeze()) } }
        }
        context.watchApp.store.state.value.entries.filter { !it.published }.forEach { send(context, it) }
    }
}

class WatchListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        runBlocking(Dispatchers.IO) { events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { runCatching { WatchTransfer.receive(this@WatchListener, it.dataItem.freeze()) } } }
    }
}
