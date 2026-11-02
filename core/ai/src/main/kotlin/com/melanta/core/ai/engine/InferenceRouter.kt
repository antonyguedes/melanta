package com.melanta.core.ai.engine

import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferenceSettings
import com.melanta.core.ai.cloud.ScreenRedactor
import com.melanta.core.ai.prompt.PromptBudget
import com.melanta.core.ai.prompt.PromptInput
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Tudo que muda entre os modos em um passo de raciocínio. O loop, o parser e a segurança são os mesmos. */
class InferenceProfile(
    val mode: InferenceMode,
    val generator: TextGenerator,
    val budget: PromptBudget,
    /** O que entra no prompt: no modo Nuvem, a versão sem dados sensíveis. */
    val prepare: (PromptInput) -> PromptInput,
)

/** Escolhe o motor a partir das configurações. Local é o padrão e nunca passa pelo redator: nada sai do aparelho. */
@Singleton
class InferenceRouter @Inject constructor(
    private val settings: InferenceSettings,
    @param:Named(LOCAL) private val local: TextGenerator,
    @param:Named(CLOUD) private val cloud: TextGenerator,
    private val redactor: ScreenRedactor,
) {
    suspend fun profile(): InferenceProfile {
        val current = settings.current()
        return when (current.mode) {
            InferenceMode.Local -> localProfile()
            InferenceMode.Cloud -> InferenceProfile(InferenceMode.Cloud, cloud, PromptBudget.Cloud) { input ->
                redactor.redact(input, includeNotificationBodies = current.sendNotificationBodies)
            }
        }
    }

    fun localProfile() = InferenceProfile(InferenceMode.Local, local, PromptBudget()) { it }

    companion object {
        const val LOCAL = "inference_local"
        const val CLOUD = "inference_cloud"
    }
}
