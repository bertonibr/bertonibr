package com.bertonibr.pdfstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bertonibr.pdfstudio.service.ClaudeService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integração com a API do Claude rodando no Android, contra um servidor
 * simulado (sem custo e sem chave real): monta o pedido e lê o streaming.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        TestPdfs.init()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ask(pdf: ByteArray?, onDelta: (String) -> Unit = {}) =
        ClaudeService.ask("sk-test", pdf, "texto extraído", "Resuma", server.url("/").toString(), onDelta)

    @Test
    fun streamsTheAnswerAndSendsThePdf() {
        server.enqueue(TestPdfs.claudeStream("Resumo ", "de teste"))
        val out = StringBuilder()
        ask(TestPdfs.sample(1)) { out.append(it) }
        assertEquals("Resumo de teste", out.toString())

        val req = server.takeRequest()
        assertTrue(req.path, req.path!!.startsWith("/v1/messages"))
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"claude-opus-5-5\""))
        assertTrue(body, body.contains("\"fallbacks\":\"default\""))
        assertTrue(body.contains("\"type\":\"document\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("Resuma"))
    }

    @Test
    fun withoutPdfSendsExtractedText() {
        server.enqueue(TestPdfs.claudeStream("ok"))
        ask(null)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("texto extraído"))
        assertTrue(!body.contains("\"type\":\"document\""))
    }

    @Test
    fun refusalBecomesAnError() {
        server.enqueue(TestPdfs.claudeStream(stopReason = "refusal"))
        try {
            ask(TestPdfs.sample(1))
            fail("deveria ter lançado erro de recusa")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("recusou"))
        }
    }

    @Test
    fun invalidKeyBecomesAnError() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        try {
            ask(TestPdfs.sample(1))
            fail("deveria ter lançado erro de autenticação")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("401") || msg.contains("invalid x-api-key"))
        }
    }
}
