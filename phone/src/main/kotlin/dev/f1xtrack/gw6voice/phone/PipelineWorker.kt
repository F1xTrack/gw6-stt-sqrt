package dev.f1xtrack.gw6voice.phone

import android.app.*
import android.content.*
import androidx.work.*
import dev.f1xtrack.gw6voice.core.*
import dev.f1xtrack.gw6voice.render.FormulaRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import okhttp3.Call
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ActiveCalls {
    private val calls = ConcurrentHashMap<String, Call>()
    fun put(id: String, call: Call) { calls[id] = call }
    fun cancel(id: String) { calls.remove(id)?.cancel() }
    fun clear(id: String) { calls.remove(id) }
}

class PipelineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id = inputData.getString("id").orEmpty()
    private val attempt = inputData.getInt("attempt", 0)
    private val store get() = applicationContext.phoneApp.jobs

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(41, Notification.Builder(applicationContext, "work")
        .setSmallIcon(dev.f1xtrack.gw6voice.render.R.drawable.ic_voice).setContentTitle("Обработка запроса с часов").setOngoing(true).build())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var job = store.get(id) ?: return@withContext Result.success()
        if (job.attempt != attempt) return@withContext Result.success()
        if (job.phase.terminal) { runCatching { PhoneTransfer.publish(applicationContext, job) }; return@withContext Result.success() }
        if (job.inFlight && job.rawAnswer.isBlank()) {
            val interrupted = store.change(id) { it.copy(phase = Phase.UNKNOWN, inFlight = false, error = "Обработка прервалась после отправки API. Нажмите «Повторить», если ответ не получен.") }!!
            runCatching { PhoneTransfer.publish(applicationContext, interrupted) }
            return@withContext Result.success()
        }
        suspend fun change(transform: (PhoneJob) -> PhoneJob): PhoneJob {
            val updated = store.change(id) { current -> if (current.phase == Phase.CANCELLED || current.attempt != attempt) current else transform(current) }!!
            runCatching { PhoneTransfer.publish(applicationContext, updated) }
            check(updated.phase != Phase.CANCELLED) { "cancelled" }
            check(updated.attempt == attempt) { "replaced" }
            return updated
        }
        val cancellationMonitor = launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { ActiveCalls.cancel(id) }
        }
        try {
            val settings = applicationContext.phoneApp.settings.load()
            val api = CloudApi { call -> ActiveCalls.put(id, call); if (store.get(id)?.phase == Phase.CANCELLED || store.get(id)?.attempt != attempt || isStopped) call.cancel() }
            api.validate(settings.stt); api.validate(settings.llm)
            if (job.transcript.isBlank()) {
                job = change { it.copy(phase = Phase.STT, inFlight = true, error = "") }
                val start = System.nanoTime()
                val transcript = api.transcribe(settings, File(job.audioPath), job.request.audioMime)
                val audio = job.audioPath
                job = change { it.copy(transcript = transcript, inFlight = false, audioPath = "", sttMillis = (System.nanoTime() - start) / 1_000_000) }
                File(audio).delete()
            }
            val start = System.nanoTime()
            val history = store.turns(job)
            var raw = job.rawAnswer
            if (raw.isBlank()) {
                job = change { it.copy(phase = Phase.LLM, inFlight = true) }
                raw = api.answer(settings, job.transcript, history)
                job = change { it.copy(rawAnswer = raw, inFlight = false, llmMillis = (System.nanoTime() - start) / 1_000_000) }
            }
            var document = FormulaRenderer.validate(AnswerParser.parse(raw))
            if (document.blocks.any { it.type == "error" } && !job.formatRepairAttempted) {
                // One extra request at most, only when a concrete formatting error exists.
                job = change { it.copy(formatRepairAttempted = true, inFlight = true) }
                raw = runCatching { api.answer(settings, job.transcript, history, raw) }.getOrDefault(raw)
                document = FormulaRenderer.validate(AnswerParser.parse(raw))
            }
            job = change { it.copy(phase = Phase.DONE, inFlight = false, rawAnswer = raw, answer = document, llmMillis = maxOf(it.llmMillis, (System.nanoTime() - start) / 1_000_000)) }
            notifyAnswer(job)
        } catch (error: Exception) {
            if (store.get(id)?.phase != Phase.CANCELLED && store.get(id)?.attempt == attempt) {
                val phase = if (error is ApiFailure && error.uncertain || isStopped && store.get(id)?.inFlight == true) Phase.UNKNOWN else Phase.ERROR
                val message = when (error) {
                    is ApiFailure -> error.safeMessage
                    is IllegalArgumentException -> error.message?.take(200) ?: "Ошибка настроек или ответа"
                    else -> "Не удалось обработать запрос. Проверьте настройки и повторите."
                }
                job = store.change(id) { it.copy(phase = phase, error = message, inFlight = false) }!!
                runCatching { PhoneTransfer.publish(applicationContext, job) }
            }
        } finally { ActiveCalls.clear(id); cancellationMonitor.cancel() }
        Result.success()
    }
    private fun notifyAnswer(job: PhoneJob) {
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java).putExtra("requestId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), Notification.Builder(applicationContext, "answers")
                .setSmallIcon(dev.f1xtrack.gw6voice.render.R.drawable.ic_voice).setContentTitle("Ответ для GW6 готов")
                .setContentText(job.transcript.take(80)).setContentIntent(intent).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }
    companion object {
        @Synchronized fun enqueue(context: Context, request: VoiceRequest) {
            val manager = WorkManager.getInstance(context)
            val attempt = context.phoneApp.jobs.get(request.id)?.attempt ?: 0
            val tag = "request-${request.id}-$attempt"
            if (manager.getWorkInfosByTag(tag).get().any { !it.state.isFinished }) return
            val work = OneTimeWorkRequestBuilder<PipelineWorker>().setInputData(workDataOf("id" to request.id, "attempt" to attempt)).addTag(tag)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            manager.enqueueUniqueWork("gw6-pipeline", ExistingWorkPolicy.APPEND_OR_REPLACE, work).result.get()
        }
    }
}
