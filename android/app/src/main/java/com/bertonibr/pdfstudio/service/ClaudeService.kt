package com.bertonibr.pdfstudio.service

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64PdfSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import java.util.Base64

/** Resumos e perguntas sobre o PDF usando a API do Claude. */
object ClaudeService {

    const val MODEL = "claude-opus-5-5"

    /** Limite de PDF por requisição da API (32 MB), com folga para o base64. */
    private const val MAX_PDF_BYTES = 22 * 1024 * 1024

    private val SYSTEM = """
        Você é um assistente dentro de um leitor de PDF no celular. Responda sempre em português do Brasil,
        de forma objetiva e bem organizada (tópicos curtos quando ajudar). Baseie-se somente no documento
        fornecido; se a informação não estiver nele, diga isso claramente e, quando útil, cite a página.
    """.trimIndent()

    const val PROMPT_SUMMARY_DOC = "Faça um resumo deste documento: comece com uma frase dizendo do que se trata, " +
        "depois liste os pontos principais, números/datas/prazos importantes e, se houver, ações ou decisões necessárias."
    const val PROMPT_SUMMARY_PAGE = "Resuma esta página de forma objetiva, destacando os pontos principais."

    /**
     * Envia o PDF (ou o texto extraído, se o PDF for grande demais) com a
     * instrução e entrega a resposta em partes para [onDelta].
     */
    fun ask(
        apiKey: String,
        pdf: ByteArray?,
        fallbackText: String,
        instruction: String,
        baseUrl: String? = null,
        onDelta: (String) -> Unit,
    ) {
        val content = mutableListOf<BetaContentBlockParam>()
        if (pdf != null && pdf.size <= MAX_PDF_BYTES) {
            content += BetaContentBlockParam.ofDocument(
                BetaRequestDocumentBlock.builder()
                    .source(BetaBase64PdfSource.builder().data(Base64.getEncoder().encodeToString(pdf)).build())
                    .build()
            )
        } else {
            content += BetaContentBlockParam.ofText(
                BetaTextBlockParam.builder().text("<documento>\n$fallbackText\n</documento>").build()
            )
        }
        content += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(instruction).build())

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(64000L)
            .system(SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // Se o filtro de segurança recusar, a API refaz o pedido em outro modelo.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfContentBlockParams(content)
            .build()

        val builder = AnthropicOkHttpClient.builder().apiKey(apiKey)
        if (baseUrl != null) builder.baseUrl(baseUrl)
        val client = builder.build()
        var stop: BetaStopReason? = null
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onDelta(it.text()) }
                    event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { stop = it }
                }
            }
        } finally {
            client.close()
        }
        if (stop == BetaStopReason.REFUSAL) {
            throw IllegalStateException("O Claude recusou este pedido (filtro de segurança).")
        }
    }
}
