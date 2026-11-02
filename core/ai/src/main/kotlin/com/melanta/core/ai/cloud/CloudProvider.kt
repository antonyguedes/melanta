package com.melanta.core.ai.cloud

import java.net.URI

/** Presets de provedores com API compatível com OpenAI Chat Completions. O modelo pode ser editado. */
enum class CloudProvider(val id: String, val displayName: String, val baseUrl: String, val suggestedModel: String) {
    Groq("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    OpenRouter("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
    OpenAi("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Gemini("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    Custom("custom", "Outro (compatível com OpenAI)", "", ""),
    ;

    companion object {
        fun fromId(id: String?): CloudProvider = entries.firstOrNull { it.id == id } ?: Groq
    }
}

data class CloudConfig(
    val provider: CloudProvider = CloudProvider.Groq,
    val baseUrl: String = provider.baseUrl,
    val model: String = provider.suggestedModel,
) {
    /**
     * Identidade do destino: provedor + host. O consentimento e a chave de API valem só para este
     * destino, então trocar o endereço nunca reenvia a tela ou a chave para outro servidor.
     */
    val destinationId: String get() = "${provider.id}|${host(baseUrl).orEmpty()}"

    /** `null` quando o destino é aceitável; senão, o problema em PT-BR. */
    fun problem(): String? = when {
        baseUrl.isBlank() -> "Informe o endereço do provedor."
        model.isBlank() -> "Informe o modelo."
        !isAllowedUrl(baseUrl) -> "O endereço precisa começar com https://: a tela e a chave não podem trafegar sem criptografia."
        else -> null
    }

    companion object {
        /** Só o próprio aparelho e o host do emulador aceitam HTTP, e o Android só libera isso no build de debug. */
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        fun host(url: String): String? = parse(url)?.host?.lowercase()

        fun isAllowedUrl(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (uri.host.isNullOrBlank() || uri.userInfo != null) return false
            return uri.scheme.equals("https", ignoreCase = true) ||
                (uri.scheme.equals("http", ignoreCase = true) && uri.host.lowercase() in LOCAL_HOSTS)
        }

        private fun parse(url: String): URI? = runCatching { URI(url.trim()) }.getOrNull()
    }
}

/** Destino pronto para uso. `toString` não mostra a chave: objetos assim acabam em logs. */
class CloudEndpoint(val baseUrl: String, val model: String, val apiKey: String?) {
    override fun toString() = "CloudEndpoint($baseUrl, $model)"
}

/** Falhas do modo Nuvem. A mensagem é em PT-BR, é mostrada ao usuário e nunca contém a chave. */
sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Falta algo para usar a nuvem: chave, consentimento ou configuração. */
class CloudNotReadyException(message: String) : CloudException(message)

class CloudAuthException : CloudException("O provedor recusou a chave de API. Confira a chave em Motor de IA.")

class CloudRateLimitException(val retryAfterSeconds: Long?) :
    CloudException("O provedor limitou as requisições. Tente de novo em instantes.")

class CloudUnavailableException(message: String, cause: Throwable? = null) : CloudException(message, cause)

/** O provedor recusou o pedido (modelo inexistente, parâmetro inválido...). Repetir não adianta. */
class CloudRequestException(message: String) : CloudException(message)

/** A tela atual é de um app sensível e não pode ser enviada. */
class CloudSensitiveScreenException :
    CloudException("Esta tela é de um app de banco, pagamento ou senhas e não foi enviada para a nuvem.")
