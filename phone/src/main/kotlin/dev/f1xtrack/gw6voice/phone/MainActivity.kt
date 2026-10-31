package dev.f1xtrack.gw6voice.phone

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.f1xtrack.gw6voice.core.*
import dev.f1xtrack.gw6voice.render.AnswerView
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80E7CD), background = Color(0xFF101820), surface = Color(0xFF16242F))) {
                Surface(Modifier.fillMaxSize()) { PhoneScreen(intent.getStringExtra("requestId"), intent.getBooleanExtra("demo", false)) }
            }
        }
    }
}

@Composable private fun PhoneScreen(initial: String?, demo: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.phoneApp
    val scope = rememberCoroutineScope()
    val jobs by app.jobs.jobs.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(if (demo) "demo" else "history") }
    var selected by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf<Settings?>(null) }
    var question by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf(Protocol.id()) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { app.settings.load() } }.onSuccess { settings = it }.onFailure { settings = Settings(); status = "Не удалось прочитать настройки. Сохраните их заново." }
        if (android.os.Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) status = "Для связи с GW6 нужны работающие Google Play services. Демонстрация формул доступна без них."
        else runCatching { withContext(Dispatchers.IO) { PhoneTransfer.rescan(context) } }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).statusBarsPadding().navigationBarsPadding()) {
        Text("GW6 Voice", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { page = "history"; selected = null }) { Text("Диалоги") }
            TextButton(onClick = { page = "settings" }) { Text("Настройки") }
            TextButton(onClick = { page = "demo" }) { Text("Демо") }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
        when (page) {
            "demo" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Проверка отображения • без API", style = MaterialTheme.typography.titleLarge)
                AnswerView(Demo.document, Modifier.fillMaxWidth())
            }
            "settings" -> settings?.let { SettingsForm(it, onSave = { updated ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { app.settings.save(updated); runCatching { PhoneTransfer.config(context, updated) } } }
                        .onSuccess { settings = updated; status = "Настройки сохранены на телефоне" }
                        .onFailure { status = it.message?.take(160) ?: "Не удалось сохранить настройки" }
                }
            }, report = { status = it }) }
            else -> {
                val job = jobs.firstOrNull { it.request.id == selected }
                if (job != null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { selected = null }) { Text("← История") }
                        Text(job.phase.label, color = MaterialTheme.colorScheme.primary)
                        Text("Распознано", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(job.transcript.ifBlank { "Ожидание распознавания" })
                        if (job.error.isNotBlank()) Text(job.error, color = MaterialTheme.colorScheme.error)
                        AnswerView(job.answer, Modifier.fillMaxWidth())
                        val sttTime = if (job.request.text != null) "не вызывался" else if (job.sttMillis > 0) "${job.sttMillis} мс" else "нет измерения"
                        val llmTime = if (job.llmMillis > 0) "${job.llmMillis} мс" else "нет измерения"
                        Text("STT: $sttTime · LLM: $llmTime", style = MaterialTheme.typography.labelSmall)
                        Row {
                            if (job.phase in setOf(Phase.ERROR, Phase.UNKNOWN, Phase.CANCELLED)) TextButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { PhoneActions.command(context, Command(job.request.id, "retry")) } }
                            }) { Text("Повторить") }
                            if (!job.phase.terminal) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { PhoneActions.command(context, Command(job.request.id, "cancel")) } } }) { Text("Отменить") }
                            TextButton(onClick = { question = job.transcript; conversation = job.request.conversationId; selected = null }) { Text("Исправить вопрос") }
                        }
                        var source by remember(job.request.id) { mutableStateOf(false) }
                        TextButton(onClick = { source = !source }) { Text("Исходный ответ") }
                        if (source) Text(job.rawAnswer)
                        if (job.phase.terminal) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { app.jobs.delete(job.request.id) }; selected = null } }) { Text("Удалить запись и ответ с телефона") }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (jobs.isEmpty()) Text("Настройте два API, установите приложение на часы и запишите первый вопрос. Здесь появятся расшифровки и ответы.", modifier = Modifier.padding(vertical = 18.dp))
                        jobs.forEach { item ->
                            OutlinedCard(onClick = { selected = item.request.id; conversation = item.request.conversationId }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(item.transcript.ifBlank { "Голосовой запрос" }, maxLines = 2)
                                    Text(item.phase.label + " · " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(item.request.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(question, { question = it }, label = { Text("Вопрос или уточнение") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = question.isNotBlank(), onClick = {
                        val request = VoiceRequest(conversationId = conversation, text = question.trim())
                        scope.launch {
                            withContext(Dispatchers.IO) { app.jobs.insert(PhoneJob(request)); PipelineWorker.enqueue(context, request) }
                            selected = request.id
                        }; question = ""
                    }) { Text("Отправить") }
                    TextButton(onClick = { conversation = Protocol.id(); selected = null; question = ""; status = "Начат новый диалог" }) { Text("Новый диалог") }
                }
            }
        }
    }
}

@Composable private fun SettingsForm(initial: Settings, onSave: (Settings) -> Unit, report: (String) -> Unit) {
    var stt by remember { mutableStateOf(initial.stt) }
    var llm by remember { mutableStateOf(initial.llm) }
    var language by remember { mutableStateOf(initial.language) }
    var wav by remember { mutableStateOf(initial.wav) }
    var instructions by remember { mutableStateOf(initial.instructions) }
    var budget by remember { mutableStateOf(initial.contextCharacters.toString()) }
    var busy by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    if (licenses != null) AlertDialog(onDismissRequest = { licenses = null }, confirmButton = { TextButton(onClick = { licenses = null }) { Text("Закрыть") } }, title = { Text("Лицензии компонентов") }, text = { Text(licenses!!, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) })
    fun current() = Settings(stt, llm, language, wav, budget.toIntOrNull()?.coerceIn(1000, 100000) ?: 24000, instructions)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "stt-test")
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(8192); var bytes = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= Protocol.MAX_AUDIO) { "Аудио больше 4 МБ" }; output.write(buffer, 0, n) }
                        } }
                        CloudApi().transcribe(current(), file, if (context.contentResolver.getType(uri)?.contains("wav") == true) "audio/wav" else "audio/mp4")
                    } finally { file.delete() }
                }; report("STT: $result")
            } catch (e: Exception) { report(if (e is ApiFailure) e.safeMessage else "Не удалось проверить STT. Проверьте файл и настройки.") }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EndpointForm("Распознавание речи • STT", stt) { stt = it }
        OutlinedTextField(language, { language = it }, label = { Text("Язык STT, например ru; можно оставить пустым") }, modifier = Modifier.fillMaxWidth())
        EndpointForm("Облачная модель • LLM", llm) { llm = it }
        OutlinedTextField(instructions, { instructions = it }, label = { Text("Дополнительная инструкция модели") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(budget, { budget = it.filter(Char::isDigit) }, label = { Text("Размер контекста в символах (1000–100000)") }, modifier = Modifier.fillMaxWidth())
        Row { Switch(wav, { wav = it }); Text("WAV PCM16 вместо M4A\nПрименяется к следующей записи на часах", modifier = Modifier.padding(10.dp)) }
        Button(enabled = !busy, onClick = { onSave(current()) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { val reply = withContext(Dispatchers.IO) { CloudApi().answer(current(), "Ответь одним словом: связь работает?", emptyList()) }; report("LLM: ${reply.take(300)}") }
                catch (e: Exception) { report(if (e is ApiFailure) e.safeMessage else "Не удалось проверить LLM. Проверьте URL, модель и параметры.") }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Проверка…" else "Проверить LLM • запрос к вашему API") }
        OutlinedButton(enabled = !busy, onClick = { audioPicker.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) { Text("Проверить STT на аудиофайле") }
        OutlinedButton(onClick = { scope.launch {
            runCatching { val nodes = Wearable.getCapabilityClient(context).getCapability(Protocol.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes; report(if (nodes.isEmpty()) "Приложение часов пока недоступно. Проверьте сопряжение и установку обоих APK." else "GW6 доступен: ${nodes.joinToString { it.displayName }}") }
                .onFailure { report("Нет доступа к Wear Data Layer. Проверьте Google Play services.") }
        } }, modifier = Modifier.fillMaxWidth()) { Text("Проверить связь с часами") }
        Text("Полные URL методов: …/audio/transcriptions и …/chat/completions. Приложение не добавляет пути. Ключи сохраняются только на телефоне. Проверки API используют ваш тариф.", style = MaterialTheme.typography.bodySmall)
        Text("Компоненты: AndroidSVG (Apache-2.0), JLaTeXMath (GPLv2 с исключением связывания), CommonMark (BSD-2-Clause), OkHttp и AndroidX (Apache-2.0). Тексты лицензий включены в APK и исходники.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 30.dp))
        TextButton(onClick = { scope.launch { licenses = withContext(Dispatchers.IO) { context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name -> "--- $name ---\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() } } } } }) { Text("Лицензии и исходники компонентов") }
    }
}

@Composable private fun EndpointForm(title: String, endpoint: Endpoint, update: (Endpoint) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    var headers by remember { mutableStateOf(JsonObject(endpoint.headers.mapValues { JsonPrimitive(it.value) }).toString()) }
    var validHeaders by remember { mutableStateOf(true) }
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    OutlinedTextField(endpoint.url, { update(endpoint.copy(url = it)) }, label = { Text("Полный HTTPS URL метода") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(endpoint.key, { update(endpoint.copy(key = it)) }, label = { Text("API key • необязателен") }, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть ключ" else "Показать ключ") }
    OutlinedTextField(endpoint.model, { update(endpoint.copy(model = it)) }, label = { Text("Модель • точный ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(headers, { value ->
        headers = value
        runCatching { WireJson.parseToJsonElement(value).jsonObject.mapValues { require(it.value is JsonPrimitive && it.value.jsonPrimitive.isString); it.value.jsonPrimitive.content } }.onSuccess { validHeaders = true; update(endpoint.copy(headers = it)) }.onFailure { validHeaders = false; update(endpoint.copy(headers = mapOf("" to ""))) }
    }, label = { Text("Дополнительные заголовки • JSON") }, isError = !validHeaders, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(endpoint.parameters, { update(endpoint.copy(parameters = it)) }, label = { Text("Дополнительные параметры • JSON") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(endpoint.timeoutSeconds.toString(), { value -> update(endpoint.copy(timeoutSeconds = value.toLongOrNull() ?: 120)) }, label = { Text("Тайм-аут, секунд (10–600)") }, modifier = Modifier.fillMaxWidth())
}
