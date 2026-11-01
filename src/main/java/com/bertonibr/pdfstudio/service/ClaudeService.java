package com.bertonibr.pdfstudio.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.beta.messages.BetaBase64PdfSource;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Integração com a API do Claude (Anthropic) para resumir e responder
 * perguntas sobre o PDF aberto. Requer uma chave de API do Claude Console.
 */
public final class ClaudeService {

    public static final String MODEL = "claude-opus-5-5";
    private static final String PREF_KEY = "anthropicApiKey";
    /** Limite de envio de PDF da API (32 MB por requisição, com folga para o base64). */
    private static final int MAX_PDF_BYTES = 22 * 1024 * 1024;

    private static final String SYSTEM = """
            Você é um assistente dentro de um leitor de PDF. Responda sempre em português do Brasil, \
            de forma objetiva e bem organizada (use tópicos quando ajudar). Baseie-se somente no \
            documento fornecido; se a informação não estiver nele, diga isso claramente e, quando \
            útil, cite o número da página.""";

    private static final Preferences PREFS = Preferences.userNodeForPackage(ClaudeService.class);

    private ClaudeService() {}

    public static String savedApiKey() {
        return PREFS.get(PREF_KEY, "");
    }

    public static void saveApiKey(String key) {
        if (key == null || key.isBlank()) PREFS.remove(PREF_KEY);
        else PREFS.put(PREF_KEY, key.strip());
    }

    public static boolean hasCredentials() {
        String env = System.getenv("ANTHROPIC_API_KEY");
        return !savedApiKey().isBlank() || (env != null && !env.isBlank());
    }

    private static AnthropicClient client() {
        String key = savedApiKey();
        return key.isBlank() ? AnthropicOkHttpClient.fromEnv()
                : AnthropicOkHttpClient.builder().apiKey(key).build();
    }

    /**
     * Envia o PDF (ou, se for grande demais, o texto extraído) com a instrução
     * e transmite a resposta em partes para onDelta. Retorna o texto completo.
     */
    public static String ask(byte[] pdf, String fallbackText, String instruction, Consumer<String> onDelta) {
        List<BetaContentBlockParam> content = new ArrayList<>();
        if (pdf != null && pdf.length <= MAX_PDF_BYTES) {
            content.add(BetaContentBlockParam.ofDocument(BetaRequestDocumentBlock.builder()
                    .source(BetaBase64PdfSource.builder().data(Base64.getEncoder().encodeToString(pdf)).build())
                    .build()));
        } else {
            content.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder()
                    .text("<documento>\n" + fallbackText + "\n</documento>").build()));
        }
        content.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(instruction).build()));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(64000L)
                .system(SYSTEM)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                // Se o filtro de segurança recusar, a API refaz o pedido em outro modelo.
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessageOfContentBlockParams(content)
                .build();

        StringBuilder full = new StringBuilder();
        BetaStopReason[] stop = new BetaStopReason[1];
        AnthropicClient client = client();
        try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params)) {
            stream.stream().forEach(event -> {
                event.contentBlockDelta()
                        .flatMap(d -> d.delta().text())
                        .ifPresent(t -> {
                            full.append(t.text());
                            onDelta.accept(t.text());
                        });
                event.messageDelta().flatMap(d -> d.delta().stopReason()).ifPresent(r -> stop[0] = r);
            });
        } finally {
            client.close();
        }
        if (BetaStopReason.REFUSAL.equals(stop[0])) {
            throw new IllegalStateException("O Claude recusou este pedido (filtro de segurança).");
        }
        return full.toString();
    }
}
