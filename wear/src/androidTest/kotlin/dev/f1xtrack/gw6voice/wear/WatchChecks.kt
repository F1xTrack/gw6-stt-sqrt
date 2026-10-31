package dev.f1xtrack.gw6voice.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.f1xtrack.gw6voice.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchChecks {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun reply(request: VoiceRequest, phase: Phase, revision: Long) = Reply(id = request.id, conversationId = request.conversationId, createdAt = request.createdAt, phase = phase, transcript = "корень из 18", answer = Demo.document, revision = revision)
    @Test fun watchStateSurvivesReload() {
        val store = WatchStore(context); val request = VoiceRequest(conversationId = Protocol.id())
        store.insert(WatchEntry(request)); store.config(WatchConfig(true))
        val reloaded = WatchStore(context)
        assertTrue(reloaded.state.value.config.wav)
        assertNotNull(reloaded.state.value.entries.firstOrNull { it.request.id == request.id })
    }
    @Test fun oldReplyCannotOverwriteCompletedAnswer() {
        val store = WatchStore(context); val request = VoiceRequest(conversationId = Protocol.id())
        store.insert(WatchEntry(request))
        assertTrue(store.reply(reply(request, Phase.DONE, 50)))
        assertFalse(store.reply(reply(request, Phase.STT, 40)))
        assertEquals(Phase.DONE, store.state.value.entries.first { it.request.id == request.id }.reply!!.phase)
        assertFalse(store.reply(reply(request, Phase.DONE, 50)))
    }
    @Test fun deletionPreventsResurrectionAfterSynchronization() {
        val store = WatchStore(context); val request = VoiceRequest(conversationId = Protocol.id())
        store.insert(WatchEntry(request)); store.reply(reply(request, Phase.DONE, 10)); store.delete(request.id)
        store.reply(reply(request, Phase.DONE, 11))
        assertFalse(store.state.value.entries.any { it.request.id == request.id })
        assertTrue(WatchStore(context).state.value.deleted.contains(request.id))
    }
    @Test fun newConversationDoesNotEraseHistory() {
        val store = WatchStore(context); val before = store.state.value
        store.newConversation()
        assertNotEquals(before.conversation, store.state.value.conversation)
        assertEquals(before.entries, store.state.value.entries)
    }
}
