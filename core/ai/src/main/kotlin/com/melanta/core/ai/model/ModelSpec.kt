package com.melanta.core.ai.model

import java.io.File

/**
 * Modelo conhecido pelo app, com origem fixa e hash para verificação.
 * O template de chat vem embutido no `.litertlm`; [chatTemplate] só existe para corrigir um embutido quebrado.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** RAM total recomendada para rodar com folga junto com os apps que o agente opera. */
    val recommendedRamGb: Int,
    /** Substitui o template de chat embutido no arquivo, quando ele é incompatível com o runtime. */
    val chatTemplate: String? = null,
)

/** Arquivo presente em `filesDir/models`. [spec] é nulo para modelos importados manualmente. */
data class InstalledModel(
    val file: File,
    val sizeBytes: Long,
    val spec: ModelSpec?,
) {
    val displayName: String get() = spec?.displayName ?: file.name
}
