package com.bertonibr.pdfstudio.service

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** Resultado de uma tradução para o português. */
class TranslationResult(
    val text: String,
    /** Código do idioma de origem detectado (ex.: "en"). */
    val sourceLanguage: String,
    /** O texto já estava em português (nada a traduzir). */
    val alreadyPortuguese: Boolean = false,
)

/** Traduz textos para o português. */
interface TextTranslator {
    suspend fun translate(text: String, onStatus: (String) -> Unit): TranslationResult
    fun close() {}
}

/** Nome do idioma em português (ex.: "en" → "inglês"). */
fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale("pt", "BR")).ifBlank { code }

/**
 * Tradução no próprio celular com o Google ML Kit: gratuita e sem chave.
 * Na primeira vez baixa o pacote do idioma pela internet (Wi-Fi ou dados
 * móveis); depois funciona até sem internet. Detecta o idioma sozinho.
 */
class MlKitTranslator : TextTranslator {

    private val identifier = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()
    private val models = RemoteModelManager.getInstance()

    override suspend fun translate(text: String, onStatus: (String) -> Unit): TranslationResult {
        val tag = try {
            identifier.identifyLanguage(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "und"
        }
        // Idioma não identificado: assume inglês, o caso mais comum.
        val source = TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
        if (source == TranslateLanguage.PORTUGUESE) {
            return TranslationResult(text, source, alreadyPortuguese = true)
        }

        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                    .build()
            )
        }
        try {
            val missing = listOf(source, TranslateLanguage.PORTUGUESE).any { lang ->
                !models.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await()
            }
            if (missing) onStatus("Baixando o tradutor (só na primeira vez, cerca de 30 MB)...")
            // Sem exigir Wi-Fi: usa também os dados móveis do celular.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Não foi possível baixar o tradutor. Verifique a internet do celular e tente de novo.", e
            )
        }
        onStatus("Traduzindo...")
        return TranslationResult(translator.translate(text).await(), source)
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        identifier.close()
    }
}
