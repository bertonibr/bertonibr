package com.bertonibr.recomendacoes.data

import android.content.Context
import java.io.File

/** Guarda as recomendações num CSV interno do app (na 1ª execução copia os exemplos de assets). */
class Repositorio(private val context: Context) {
    private val arquivo = File(context.filesDir, "recomendacoes.csv")

    fun carregar(): List<Recomendacao> {
        if (!arquivo.exists()) {
            context.assets.open("recomendacoes.csv").use { entrada ->
                arquivo.outputStream().use { entrada.copyTo(it) }
            }
        }
        return Csv.ler(arquivo.readText()).sortedByDescending { it.data }
    }

    fun salvar(recs: List<Recomendacao>) = arquivo.writeText(Csv.escrever(recs))
}
