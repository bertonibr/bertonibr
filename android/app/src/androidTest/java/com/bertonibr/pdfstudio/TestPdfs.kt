package com.bertonibr.pdfstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File

/** PDFs e utilidades usados pelos testes. */
object TestPdfs {

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun init() = PDFBoxResourceLoader.init(context)

    /**
     * PDF A4 (595 x 842 pt) com [pages] páginas. Cada página tem a linha
     * "$prefix N conteudo original" com a base em (72, 700) do PDF, ou seja,
     * y = 142 na tela (origem no topo).
     */
    fun sample(pages: Int, prefix: String = "Pagina"): ByteArray {
        init()
        PDDocument().use { d ->
            for (i in 1..pages) {
                val p = PDPage(PDRectangle.A4)
                d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("$prefix $i conteudo original")
                    cs.endText()
                }
            }
            return save(d)
        }
    }

    fun protectedPdf(owner: String, user: String): ByteArray {
        PDDocument.load(sample(1)).use { d ->
            val permissions = AccessPermission()
            permissions.setCanModify(false)
            val policy = StandardProtectionPolicy(owner, user, permissions)
            policy.encryptionKeyLength = 128
            d.protect(policy)
            return save(d)
        }
    }

    fun save(d: PDDocument): ByteArray {
        val bos = ByteArrayOutputStream()
        d.save(bos)
        return bos.toByteArray()
    }

    fun writeCache(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes) }

    fun bitmap(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    fun png(w: Int, h: Int, color: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap(w, h, color).compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    // ------------------------------------------------------------ pixels

    fun describe(c: Int) = "rgb(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)})"

    fun assertRed(c: Int, msg: String = "") =
        assertTrue("$msg esperado vermelho, veio ${describe(c)}", Color.red(c) > 200 && Color.green(c) < 80 && Color.blue(c) < 80)

    fun assertWhite(c: Int, msg: String = "") =
        assertTrue("$msg esperado branco, veio ${describe(c)}", Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240)

    fun countDarkPixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val c = b.getPixel(x, y)
            if (Color.red(c) < 100 && Color.green(c) < 100 && Color.blue(c) < 100) n++
        }
        return n
    }

    // ------------------------------------------------------------ Claude (servidor simulado)

    /** Resposta em streaming (SSE) no formato da API de mensagens. */
    fun claudeStream(vararg pieces: String, stopReason: String = "end_turn"): MockResponse {
        val events = buildList {
            add("message_start" to """{"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""")
            add("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            pieces.forEach {
                add("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}""")
            }
            add("content_block_stop" to """{"type":"content_block_stop","index":0}""")
            add("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":5}}""")
            add("message_stop" to """{"type":"message_stop"}""")
        }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" })
    }
}
