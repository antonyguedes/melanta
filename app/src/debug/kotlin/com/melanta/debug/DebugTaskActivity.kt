package com.melanta.debug

import android.app.Activity
import android.os.Bundle
import com.melanta.feature.agent.AgentController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Só no build de debug: inicia uma tarefa pelo adb, para os testes de ponta a ponta.
 * Uma Activity em primeiro plano pode iniciar o serviço do agente; um receiver em segundo plano não.
 *
 *     adb shell am start -n com.melanta/com.melanta.debug.DebugTaskActivity --es task "põe um alarme para 7h"
 */
class DebugTaskActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val task = intent.getStringExtra(EXTRA_TASK)
        if (task != null) {
            val controller = EntryPointAccessors.fromApplication<TaskEntryPoint>(this).agentController()
            controller.dismiss()
            controller.start(task)
        }
        // Sai da frente: o agente opera o app que estava antes (normalmente a tela inicial).
        finish()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface TaskEntryPoint {
        fun agentController(): AgentController
    }

    private companion object {
        const val EXTRA_TASK = "task"
    }
}
