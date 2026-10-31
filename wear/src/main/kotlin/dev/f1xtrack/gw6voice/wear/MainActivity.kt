package dev.f1xtrack.gw6voice.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.*
import dev.f1xtrack.gw6voice.core.*
import dev.f1xtrack.gw6voice.render.AnswerView
import kotlinx.coroutines.*
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            if (intent.getBooleanExtra("preview", false)) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(225.dp).clip(CircleShape)) { WatchScreen(null, true) }
                }
            } else WatchScreen(intent.getStringExtra("requestId"), intent.getBooleanExtra("demo", false))
        } }
    }
}

@Composable private fun WatchScreen(initial: String?, demoInitially: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = context.watchApp.store
    val data by store.state.collectAsStateWithLifecycle()
    val recording by RecordingService.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initial ?: recording.lastId.takeIf { it.isNotBlank() }) }
    var handledRecording by remember { mutableStateOf(recording.lastId) }
    var history by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(demoInitially) }
    var transcript by remember(selected) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var elapsed by remember { mutableLongStateOf(0) }
    fun begin() {
        val conversation = data.entries.firstOrNull { it.request.id == selected }?.request?.conversationId ?: data.conversation
        store.selectConversation(conversation)
        context.startForegroundService(Intent(context, RecordingService::class.java).setAction("start").putExtra("conversation", conversation))
        selected = null; history = false; demo = false
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() else status = "Разрешите доступ к микрофону" }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching { withContext(Dispatchers.IO) { WatchTransfer.sync(context) } }.onFailure { status = "Проверьте связь с телефоном" }
    }
    LaunchedEffect(recording.recording, recording.startedAt) { while (recording.recording) { elapsed = (System.currentTimeMillis() - recording.startedAt) / 1000; delay(250) } }
    LaunchedEffect(recording.lastId) {
        if (recording.lastId.isNotBlank() && recording.lastId != handledRecording) {
            handledRecording = recording.lastId; selected = recording.lastId
        }
    }
    val entry = data.entries.firstOrNull { it.request.id == selected }
    Column(Modifier.fillMaxSize().background(Color(0xFF101820)).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GW6 Voice", style = MaterialTheme.typography.titleMedium)
        if (recording.recording) {
            Text("● Запись", color = Color(0xFFFFB4AB))
            Text("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.displaySmall)
            Button(onClick = { context.startService(Intent(context, RecordingService::class.java).setAction("stop")) }, modifier = Modifier.fillMaxWidth()) { Text("Стоп → отправить") }
            WatchAction(onClick = { context.startService(Intent(context, RecordingService::class.java).setAction("cancel")) }) { Text("Отменить") }
        } else {
            Button(onClick = { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin() else microphone.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text(if (entry != null) "Уточнить голосом" else "Записать вопрос") }
            when {
                demo -> {
                    Text("Демо • без API")
                    AnswerView(Demo.document, Modifier.fillMaxWidth(), compact = true, onOverflow = { status = "Полная формула доступна в приложении телефона" })
                    WatchAction(onClick = { demo = false }) { Text("Назад") }
                }
                history -> {
                    Text("История")
                    data.entries.forEach { item ->
                        OutlinedButton(onClick = { selected = item.request.id; history = false; storeConversation(context, item.request.conversationId) }, modifier = Modifier.fillMaxWidth()) {
                            Text((item.reply?.transcript?.ifBlank { null } ?: item.request.text ?: "Голосовой запрос").take(70))
                        }
                    }
                    if (data.entries.isEmpty()) Text("Пока нет запросов")
                    WatchAction(onClick = { history = false }) { Text("Назад") }
                }
                entry != null -> {
                    Text(entry.reply?.phase?.label ?: if (entry.published) "Ожидание телефона" else "Ожидание отправки", color = Color(0xFF80E7CD))
                    if (!entry.reply?.error.isNullOrBlank()) Text(entry.reply!!.error)
                    entry.reply?.answer?.let { AnswerView(it, Modifier.fillMaxWidth(), compact = true, onOverflow = { status = "Откройте полный ответ в приложении телефона" }) }
                    WatchAction(onClick = { transcript = !transcript }) { Text("Распознано") }
                    if (transcript) Text(entry.reply?.transcript?.ifBlank { "Ещё не распознано" } ?: "Ещё не распознано")
                    if (entry.reply?.phase in setOf(Phase.ERROR, Phase.UNKNOWN, Phase.CANCELLED)) OutlinedButton(onClick = {
                        scope.launch { runCatching { WatchTransfer.command(context, entry.request.id, "retry") }.onSuccess { status = "Повтор запрошен" }.onFailure { status = "Не удалось передать команду" } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Повторить") }
                    if (entry.reply?.phase?.terminal != true) WatchAction(onClick = {
                        scope.launch {
                            if (!entry.published) { store.delete(entry.request.id); selected = null }
                            else runCatching { WatchTransfer.command(context, entry.request.id, "cancel") }.onSuccess { status = "Отмена запрошена" }.onFailure { status = "Команда не передана" }
                        }
                    }) { Text("Отменить запрос") }
                    if (entry.reply?.phase?.terminal == true) WatchAction(onClick = { store.delete(entry.request.id); selected = null }) { Text("Удалить с часов") }
                }
                else -> Text("Вопрос → телефон → ответ", style = MaterialTheme.typography.bodySmall)
            }
            WatchAction(onClick = { store.newConversation(); selected = null; history = false; demo = false; status = "Новый диалог" }) { Text("Новый диалог") }
            WatchAction(onClick = { history = true; demo = false }) { Text("История") }
            WatchAction(onClick = {
                scope.launch {
                    status = "Проверка связи…"
                    runCatching {
                        withContext(Dispatchers.IO) { WatchTransfer.sync(context) }
                        val nodes = Wearable.getCapabilityClient(context).getCapability(Protocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                        status = if (nodes.isEmpty()) "Телефон пока недоступен. Записи сохраняются." else "Телефон подключён"
                    }.onFailure { status = "Проверьте сопряжение и приложение телефона" }
                }
            }) { Text("Синхронизировать") }
            WatchAction(onClick = { demo = true; history = false }) { Text("Демо формул и SVG") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (recording.message.isNotBlank() && entry == null) Text(recording.message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
}

private fun storeConversation(context: android.content.Context, id: String) = context.watchApp.store.selectConversation(id)

@Composable private fun WatchAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
}
