package dev.f1xtrack.gw6voice.wear

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

data class RecordingState(val recording: Boolean = false, val startedAt: Long = 0, val message: String = "", val lastId: String = "")

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private var audio: AudioRecord? = null
    @Volatile private var capturing = false
    private var thread: Thread? = null
    private var wavFile: RandomAccessFile? = null
    private var output: File? = null
    private var request: VoiceRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> finish(false)
            "cancel" -> finish(true)
            "start" -> if (!mutable.value.recording) startRecording(intent.getStringExtra("conversation") ?: watchApp.store.state.value.conversation)
        }
        return START_NOT_STICKY
    }
    private fun startRecording(conversation: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(71, Notification.Builder(this, "record").setSmallIcon(dev.f1xtrack.gw6voice.render.R.drawable.ic_voice)
                .setContentTitle("Запись вопроса").setContentText("До 2 минут").setOngoing(true).setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Стоп и отправить", stop).build()).build())
            val wav = watchApp.store.state.value.config.wav
            val item = VoiceRequest(conversationId = conversation, audioMime = if (wav) "audio/wav" else "audio/mp4")
            request = item
            output = File(filesDir, "audio").apply { mkdirs() }.let { File(it, item.id + if (wav) ".wav" else ".m4a") }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GW6Voice:record").apply { acquire(130_000) }
            if (wav) startWav(output!!) else {
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000)
                    setOutputFile(output!!.path); prepare(); start()
                }
            }
            mutable.value = RecordingState(true, System.currentTimeMillis())
            handler.postDelayed({ finish(false) }, 120_000)
        } catch (_: Exception) {
            cleanup(); output?.delete(); mutable.value = RecordingState(message = "Не удалось включить микрофон. Проверьте разрешение и другие записи."); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    @Suppress("MissingPermission")
    private fun startWav(file: File) {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
        audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        require(audio!!.state == AudioRecord.STATE_INITIALIZED)
        wavFile = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        audio!!.startRecording(); capturing = true
        thread = Thread {
            val buffer = ByteArray(size)
            try {
                while (capturing) { val n = audio!!.read(buffer, 0, buffer.size); if (n < 0) error("AudioRecord read"); if (n > 0) wavFile!!.write(buffer, 0, n) }
            } catch (_: Exception) { if (capturing) handler.post { failWav() } }
        }.apply { name = "GW6-WAV"; start() }
    }
    private fun failWav() { finish(true); mutable.value = RecordingState(message = "Запись прервалась. Запишите вопрос заново.") }
    @Synchronized private fun finish(cancel: Boolean) {
        if (!mutable.value.recording) return
        handler.removeCallbacksAndMessages(null)
        mutable.value = mutable.value.copy(recording = false)
        val item = request!!
        val file = output!!
        var valid = !cancel
        try {
            if (recorder != null) recorder!!.stop()
            if (audio != null) {
                capturing = false; audio!!.stop(); thread?.join(2000)
                check(thread?.isAlive != true)
                wavFile?.let { writeWavHeader(it); it.close() }; wavFile = null
            }
        } catch (_: Exception) { valid = false }
        cleanup()
        if (!valid || file.length() <= 44) {
            file.delete(); mutable.value = RecordingState(message = if (cancel) "Запись отменена" else "Слишком короткая или повреждённая запись")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        scope.launch {
            try {
                val entry = WatchEntry(item, file.path)
                watchApp.store.insert(entry)
                mutable.value = RecordingState(message = "Отправка на телефон", lastId = item.id)
                runCatching { WatchTransfer.send(this@RecordingService, entry) }
                    .onFailure { mutable.value = RecordingState(message = "Запись сохранена. Нажмите «Синхронизировать» после восстановления связи.", lastId = item.id) }
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    private fun cleanup() {
        capturing = false
        runCatching { recorder?.release() }; recorder = null
        runCatching { audio?.stop() }; runCatching { thread?.join(2000) }; runCatching { audio?.release() }; audio = null
        runCatching { wavFile?.close() }; wavFile = null
        if (wake?.isHeld == true) wake?.release(); wake = null
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (mutable.value.recording) { cleanup(); output?.delete(); mutable.value = RecordingState(message = "Запись остановлена") }
        scope.cancel(); super.onDestroy()
    }
    companion object {
        private val mutable = MutableStateFlow(RecordingState())
        val state = mutable.asStateFlow()
        private fun writeWavHeader(file: RandomAccessFile) {
            val size = (file.length() - 44).toInt()
            fun intLE(value: Int) { file.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
            fun shortLE(value: Int) { file.write(byteArrayOf(value.toByte(), (value shr 8).toByte())) }
            file.seek(0); file.writeBytes("RIFF"); intLE(size + 36); file.writeBytes("WAVEfmt "); intLE(16); shortLE(1); shortLE(1)
            intLE(16000); intLE(32000); shortLE(2); shortLE(16); file.writeBytes("data"); intLE(size)
        }
    }
}
