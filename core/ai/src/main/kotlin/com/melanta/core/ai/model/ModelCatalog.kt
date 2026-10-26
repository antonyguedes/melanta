package com.melanta.core.ai.model

import com.melanta.core.ai.prompt.ChatTemplates

/**
 * Modelos gratuitos (Apache-2.0, sem login) da litert-community. As URLs são fixadas no commit
 * do repositório para que o SHA-256 continue válido.
 */
object ModelCatalog {

    val Gemma4_E2B = ModelSpec(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        url = hf("gemma-4-E2B-it-litert-lm", "1caa527c1e88b55f046fcd8d2b4265606680663d", "gemma-4-E2B-it.litertlm"),
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        recommendedRamGb = 8,
    )


    val Qwen3_4B = ModelSpec(
        id = "qwen3-4b-instruct-2507-int4",
        displayName = "Qwen3 4B Instruct 2507 (mais cauteloso)",
        fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        url = hf(
            "Qwen3-4B-Instruct-2507",
            "4d409771b414d86b270ca54d27ffc45032a81142",
            "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        ),
        sizeBytes = 2_659_057_664L,
        sha256 = "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd",
        recommendedRamGb = 12,
        chatTemplate = ChatTemplates.CHATML_CONTENT_PARTS,
    )


    val Qwen25_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8",
        displayName = "Qwen2.5 1.5B (leve)",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        url = hf(
            "Qwen2.5-1.5B-Instruct",
            "2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        ),
        sizeBytes = 1_597_922_856L,
        sha256 = "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
        recommendedRamGb = 6,
    )

    /**
     * Escolhido com `tools/model-bench` (16 cenários, 6 de controle): Gemma 4 E2B empatou no topo
     * em acertos com o Qwen3 4B e é ~12x mais rápido no prefill, com menos RAM.
     */
    val default: ModelSpec = Gemma4_E2B

    /** Em ordem de preferência quando nenhum modelo foi escolhido. */
    val all: List<ModelSpec> = listOf(Gemma4_E2B, Qwen3_4B, Qwen25_1_5B)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    fun byFileName(fileName: String): ModelSpec? = all.firstOrNull { it.fileName == fileName }

    private fun hf(repo: String, commit: String, file: String) =
        "https://huggingface.co/litert-community/$repo/resolve/$commit/$file"
}
