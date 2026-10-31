package dev.f1xtrack.gw6voice.phone

import android.app.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.room.Room
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class PhoneJob(
    val request: VoiceRequest, val phase: Phase = Phase.RECEIVED,
    val transcript: String = request.text.orEmpty(), val rawAnswer: String = "",
    val answer: AnswerDocument = AnswerDocument(), val error: String = "",
    val audioPath: String = "", val inFlight: Boolean = false,
    val sttMillis: Long = 0, val llmMillis: Long = 0,
    val revision: Long = System.currentTimeMillis(), val handledCommands: List<String> = emptyList(), val attempt: Int = 0,
    val formatRepairAttempted: Boolean = false
) {
    fun reply() = Reply(id = request.id, conversationId = request.conversationId, createdAt = request.createdAt,
        phase = phase, transcript = transcript, answer = answer, error = error, revision = revision)
    fun row() = JobRow().also { it.id = request.id; it.conversationId = request.conversationId; it.createdAt = request.createdAt; it.payload = WireJson.encodeToString(this) }
}

class JobStore(context: Context) {
    private val database = Room.databaseBuilder(context, JobDatabase::class.java, "requests.db").build()
    private val mutable = MutableStateFlow<List<PhoneJob>>(emptyList())
    val jobs = mutable.asStateFlow()
    @Synchronized fun refresh() { mutable.value = database.jobs().all().mapNotNull { runCatching { WireJson.decodeFromString<PhoneJob>(it.payload) }.getOrNull() } }
    @Synchronized fun get(id: String): PhoneJob? = database.jobs().get(id)?.let { WireJson.decodeFromString<PhoneJob>(it.payload) }
    @Synchronized fun insert(job: PhoneJob): Boolean { val added = database.jobs().insert(job.row()) != -1L; refresh(); return added }
    @Synchronized fun delete(id: String) {
        val job = get(id) ?: return
        require(job.phase.terminal) { "Сначала отмените обработку" }
        if (job.audioPath.isNotBlank()) File(job.audioPath).delete()
        database.jobs().delete(id); refresh()
    }
    @Synchronized fun change(id: String, transform: (PhoneJob) -> PhoneJob): PhoneJob? {
        val current = get(id) ?: return null
        val next = transform(current).copy(revision = maxOf(System.currentTimeMillis(), current.revision + 1))
        database.jobs().update(next.row()); refresh(); return next
    }
    @Synchronized fun turns(job: PhoneJob): List<Turn> = database.jobs().all().map { WireJson.decodeFromString<PhoneJob>(it.payload) }
        .filter { it.request.conversationId == job.request.conversationId && it.request.createdAt < job.request.createdAt && it.phase == Phase.DONE }
        .sortedBy { it.request.createdAt }.map { Turn(it.transcript, it.rawAnswer) }
}

class SecureSettings(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "settings.enc"))
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("gw6-settings", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("gw6-settings", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    fun load(): Settings {
        if (!file.baseFile.exists()) return Settings()
        val envelope = file.openRead().use { it.readBytes() }.toString(Charsets.UTF_8).split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(envelope[0], Base64.NO_WRAP))) }
        return WireJson.decodeFromString(cipher.doFinal(Base64.decode(envelope[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }
    fun save(settings: Settings) {
        CloudApi().validate(settings.stt); CloudApi().validate(settings.llm)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val content = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(WireJson.encodeToString(settings).toByteArray()), Base64.NO_WRAP)
        val stream = file.startWrite()
        try { stream.write(content.toByteArray()); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

class PhoneApp : Application() {
    lateinit var jobs: JobStore; private set
    lateinit var settings: SecureSettings; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate(); jobs = JobStore(this); settings = SecureSettings(this)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel("work", "Обработка запросов", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel("answers", "Ответы", NotificationManager.IMPORTANCE_DEFAULT))
        }
        scope.launch { jobs.refresh(); jobs.jobs.value.filter { !it.phase.terminal }.forEach { PipelineWorker.enqueue(this@PhoneApp, it.request) } }
    }
}

val Context.phoneApp get() = applicationContext as PhoneApp
