package dev.f1xtrack.gw6voice.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.caverock.androidsvg.SVG
import dev.f1xtrack.gw6voice.core.*
import dev.f1xtrack.gw6voice.render.FormulaRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidChecks {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun nativeMathRendersRootsFractionsMatricesAndUnicode() {
        val formulas = listOf("\\sqrt{1+\\sqrt{4}}", "\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}", "\\int_0^1 x^2\\,dx", "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}", "\\alpha+\\beta=\\pi", "\\text{Ответ}=3\\sqrt{2}")
        formulas.forEachIndexed { index, source ->
            val drawable = FormulaRenderer.drawable(source, 36f)
            assertTrue(source, drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Formula must paint pixels: $source", pixels.any { it != 0 })
            File(context.getExternalFilesDir(null), "formula-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        assertFalse(FormulaRenderer.validate(Demo.document).blocks.any { it.type == "error" })
    }
    @Test fun nativeSvgRendersToWatchSizedCanvas() {
        val source = Demo.document.blocks.first { it.type == "svg" }.content
        val svg = SVG.getFromString(SafeSvg.validate(source))
        for (size in listOf(240, 320, 396)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap))
            val pixels = IntArray(size * size); bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            assertTrue(pixels.distinct().size > 20)
            File(context.getExternalFilesDir(null), "svg-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun keystoreSettingsRoundTripWithoutPlaintextFile() {
        val storage = SecureSettings(context)
        val prior = runCatching { storage.load() }.getOrDefault(Settings())
        val test = Settings(stt = Endpoint("https://speech.example.test/audio/transcriptions", "test-private-stt-value", "speech"), llm = Endpoint("https://model.example.test/chat/completions", "test-private-llm-value", "model"))
        try {
            storage.save(test); assertEquals(test, storage.load())
            val bytes = File(context.filesDir, "settings.enc").readText()
            assertFalse(bytes.contains("test-private")); assertFalse(bytes.contains("example.test"))
        } finally {
            if (prior.stt.url.isNotBlank() && prior.llm.url.isNotBlank()) storage.save(prior) else File(context.filesDir, "settings.enc").delete()
        }
    }
    @Test fun duplicateRequestDoesNotOverwriteDurableState() {
        val store = context.phoneApp.jobs
        val request = VoiceRequest(conversationId = Protocol.id(), text = "исходный вопрос")
        assertTrue(store.insert(PhoneJob(request)))
        store.change(request.id) { it.copy(phase = Phase.DONE, transcript = "сохранено") }
        assertFalse(store.insert(PhoneJob(request)))
        assertEquals(Phase.DONE, store.get(request.id)!!.phase)
        assertEquals("сохранено", store.get(request.id)!!.transcript)
    }
    @Test fun interruptedNetworkStageBecomesUnknownWithoutSendingAgain() = runBlocking {
        val request = VoiceRequest(conversationId = Protocol.id(), text = "вопрос")
        val store = context.phoneApp.jobs
        store.insert(PhoneJob(request, phase = Phase.LLM, inFlight = true))
        val worker = TestListenableWorkerBuilder<PipelineWorker>(context).setInputData(workDataOf("id" to request.id, "attempt" to 0)).build()
        worker.doWork()
        assertEquals(Phase.UNKNOWN, store.get(request.id)!!.phase)
        assertFalse(store.get(request.id)!!.inFlight)
    }
}
