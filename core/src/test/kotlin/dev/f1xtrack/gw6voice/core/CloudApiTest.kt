package dev.f1xtrack.gw6voice.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class CloudApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CloudApi
    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).retryOnConnectionFailure(false).followRedirects(false).build()
        api = CloudApi(client)
    }
    @After fun tearDown() { server.shutdown() }
    private fun endpoint(path: String = "/custom/chat/completions") = Endpoint(server.url(path).newBuilder().host("localhost").build().toString(), "secret-test", "my-model", mapOf("X-Provider" to "custom"))
    @Test fun customUrlHeadersModelsAndContextArePreserved() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Ответ \\(x=2\\)"}}]}"""))
        val endpoint = endpoint().copy(parameters = "{\"temperature\":0.2,\"max_tokens\":200}")
        val result = api.answer(Settings(llm = endpoint), "новый", listOf(Turn("старый", "ответ")))
        assertTrue(result.contains("x=2"))
        val request = server.takeRequest()
        assertEquals("/custom/chat/completions", request.path); assertEquals("Bearer secret-test", request.getHeader("Authorization")); assertEquals("custom", request.getHeader("X-Provider"))
        val body = WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("my-model", body["model"]!!.jsonPrimitive.content); assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(200, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(listOf("system", "user", "assistant", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }
    @Test fun sttIsMultipartAndIndependent() {
        server.enqueue(MockResponse().setBody("""{"text":"корень из двух"}"""))
        val file = File.createTempFile("gw6-test", ".wav").apply { writeBytes(ByteArray(100)) }
        try {
            val result = api.transcribe(Settings(stt = endpoint("/speech").copy(model = "speech-model", parameters = "{\"prompt\":\"математика\"}")), file, "audio/wav")
            assertEquals("корень из двух", result)
            val request = server.takeRequest(); assertEquals("/speech", request.path)
            val multipart = request.body.readUtf8()
            assertTrue(multipart.contains("speech-model")); assertTrue(multipart.contains("voice.wav")); assertTrue(multipart.contains("audio/wav")); assertTrue(multipart.contains("математика"))
        } finally { file.delete() }
    }
    @Test fun emptySttIsAnError() {
        server.enqueue(MockResponse().setBody("{\"text\":\"\"}"))
        val file = File.createTempFile("gw6-test", ".wav")
        try { assertThrows(ApiFailure::class.java) { api.transcribe(Settings(stt = endpoint()), file, "audio/wav") } } finally { file.delete() }
    }
    @Test fun rateLimitDoesNotRetryAndDoesNotLeakBody() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("secret-test provider trace"))
        val error = assertThrows(ApiFailure::class.java) { api.answer(Settings(llm = endpoint()), "q", emptyList()) }
        assertFalse(error.uncertain); assertFalse(error.message!!.contains("secret-test")); assertEquals(1, server.requestCount)
    }
    @Test fun serverErrorIsUncertain() { server.enqueue(MockResponse().setResponseCode(500)); assertTrue(assertThrows(ApiFailure::class.java) { api.answer(Settings(llm = endpoint()), "q", emptyList()) }.uncertain) }
    @Test fun redirectsDoNotForwardKeys() { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org")); assertThrows(ApiFailure::class.java) { api.answer(Settings(llm = endpoint()), "q", emptyList()) }; assertEquals(1, server.requestCount) }
    @Test fun disconnectDoesNotRetry() { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); assertTrue(assertThrows(ApiFailure::class.java) { api.answer(Settings(llm = endpoint()), "q", emptyList()) }.uncertain); assertEquals(1, server.requestCount) }
    @Test fun plaintextEndpointIsRejected() { assertThrows(IllegalArgumentException::class.java) { api.validate(Endpoint("http://localhost/api", model = "model")) } }
    @Test fun mandatoryFieldsCannotBeOverridden() { assertThrows(IllegalArgumentException::class.java) { api.answer(Settings(llm = endpoint().copy(parameters = "{\"messages\":[]}")), "q", emptyList()) }; assertEquals(0, server.requestCount) }
    @Test fun malformedResponseIsReported() { server.enqueue(MockResponse().setBody("not json")); assertThrows(ApiFailure::class.java) { api.answer(Settings(llm = endpoint()), "q", emptyList()) } }
}
