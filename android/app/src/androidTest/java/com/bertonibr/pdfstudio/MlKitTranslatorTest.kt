package com.bertonibr.pdfstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bertonibr.pdfstudio.service.MlKitTranslator
import com.bertonibr.pdfstudio.service.TranslationResult
import com.bertonibr.pdfstudio.service.languageName
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tradutor de verdade (Google ML Kit) no emulador. Baixar o pacote do idioma
 * depende da internet do emulador; se não der, o teste é marcado como pulado
 * (não como falha).
 */
@RunWith(AndroidJUnit4::class)
class MlKitTranslatorTest {

    @Test
    fun portugueseTextIsRecognizedWithoutDownloading() = runBlocking {
        val t = MlKitTranslator()
        try {
            val r = t.translate("Bom dia! Hoje vamos revisar o contrato e assinar os documentos.") {}
            assertTrue("deveria reconhecer português", r.alreadyPortuguese)
        } finally {
            t.close()
        }
    }

    @Test
    fun translatesEnglishToPortuguese() = runBlocking {
        val t = MlKitTranslator()
        try {
            val r: TranslationResult = try {
                withTimeout(240_000) { t.translate("Good morning, my friend. The contract is ready.") {} }
            } catch (e: Exception) {
                assumeTrue("tradutor indisponível no emulador (sem rede para baixar o pacote): $e", false)
                return@runBlocking
            }
            assertEquals("en", r.sourceLanguage)
            val out = r.text.lowercase()
            assertTrue(r.text, out.contains("bom dia") || out.contains("amigo") || out.contains("contrato"))
        } finally {
            t.close()
        }
    }

    @Test
    fun languageNamesInPortuguese() {
        assertEquals("inglês", languageName("en"))
        assertEquals("espanhol", languageName("es"))
    }
}
