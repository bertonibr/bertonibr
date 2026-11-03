package com.bertonibr.pdfstudio.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Um documento do histórico (abas Recentes/Favoritos da tela inicial). */
data class RecentDoc(
    val id: String,
    /** Endereço original do arquivo (pode deixar de ser acessível depois). */
    val uri: String?,
    val name: String,
    val lastOpened: Long,
    val pageCount: Int,
    /** Página em que o usuário parou (para continuar de onde estava). */
    val lastPage: Int,
    val favorite: Boolean,
    /** Há uma cópia guardada pelo app (para arquivos abertos via WhatsApp, "Abrir com", etc.). */
    val hasCopy: Boolean,
    val sizeBytes: Long,
)

/**
 * Guarda o histórico em um arquivo JSON na área privada do app, junto com as
 * miniaturas e as cópias dos PDFs que não poderiam ser reabertos depois.
 */
class RecentStore(context: Context) {

    val dir = File(context.filesDir, "recentes").apply { mkdirs() }
    private val index = File(dir, "recentes.json")

    fun copyFile(id: String) = File(dir, "$id.pdf")
    fun thumbFile(id: String) = File(dir, "$id.png")

    fun deleteFiles(id: String) {
        copyFile(id).delete()
        thumbFile(id).delete()
    }

    fun load(): List<RecentDoc> = try {
        if (!index.exists()) emptyList() else {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentDoc(
                    id = o.getString("id"),
                    uri = o.optString("uri").ifEmpty { null },
                    name = o.getString("name"),
                    lastOpened = o.optLong("lastOpened"),
                    pageCount = o.optInt("pageCount"),
                    lastPage = o.optInt("lastPage"),
                    favorite = o.optBoolean("favorite"),
                    hasCopy = o.optBoolean("hasCopy"),
                    sizeBytes = o.optLong("size"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList() // arquivo corrompido: começa um histórico novo
    }

    fun save(list: List<RecentDoc>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("uri", d.uri ?: "")
                    .put("name", d.name)
                    .put("lastOpened", d.lastOpened)
                    .put("pageCount", d.pageCount)
                    .put("lastPage", d.lastPage)
                    .put("favorite", d.favorite)
                    .put("hasCopy", d.hasCopy)
                    .put("size", d.sizeBytes)
            )
        }
        // Grava num arquivo temporário e troca de uma vez: nunca fica um JSON pela metade.
        val tmp = File(dir, "recentes.${System.nanoTime()}.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) {
            index.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        /** Quantos documentos (fora os favoritos) o histórico guarda. */
        const val MAX_RECENTS = 50

        /** Espaço máximo das cópias guardadas; as mais antigas são apagadas primeiro. */
        const val MAX_COPY_BYTES = 300L * 1024 * 1024

        /** Largura (pixels) da miniatura da 1ª página. */
        const val THUMB_WIDTH = 200

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
