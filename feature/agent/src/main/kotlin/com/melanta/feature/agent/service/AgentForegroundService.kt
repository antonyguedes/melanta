package com.melanta.feature.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.melanta.core.automation.service.AccessibilityBridge
import com.melanta.core.common.DispatcherProvider
import com.melanta.feature.agent.AgentController
import com.melanta.feature.agent.AgentOrchestrator
import com.melanta.feature.agent.overlay.AgentOverlay
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.isActive
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mantém o processo vivo enquanto o agente opera outros apps. É dono do escopo do loop: o `Job`
 * é registrado no [AgentController] e cancelado por qualquer kill switch (notificação, overlay,
 * Volume− três vezes, botão Parar do app).
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject
    lateinit var controller: AgentController

    @Inject
    lateinit var orchestrator: AgentOrchestrator

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var bridge: AccessibilityBridge

    @Inject
    lateinit var overlay: AgentOverlay

    @Inject
    lateinit var notifications: AgentNotifications

    private lateinit var scope: CoroutineScope
    private var runJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        notifications.ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground primeiro: o sistema exige a chamada logo após startForegroundService.
        startInForeground(controller.state.value)

        val task = intent?.takeIf { it.action == ACTION_START }?.getStringExtra(EXTRA_TASK)
        val state = controller.state.value
        if (task == null || state !is AgentState.LoadingModel || state.task != task) {
            // Reinício pelo sistema ou intent obsoleto: não há tarefa válida para executar.
            stopNow()
            return START_NOT_STICKY
        }
        if (runJob?.isActive == true) return START_NOT_STICKY

        runJob = scope.launch {
            try {
                orchestrator.run(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Loop do agente falhou", e)
                controller.fail(e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(NonCancellable + dispatchers.main) {
                    notifications.postResult(controller.state.value, controller.steps.value)
                    stopNow()
                }
            }
        }.also(controller::attach)

        scope.launch(dispatchers.main) {
            overlay.show()
            controller.state.collect(overlay::render)
        }
        // Progresso na notificação (e no chip da barra de status no Android 16+) a cada mudança de estado ou passo.
        scope.launch {
            combine(controller.state, controller.steps, ::Pair).collect { (current, steps) ->
                if (current.isActive) notifications.update(current, steps)
            }
        }
        // Kill switch físico: Volume− três vezes, detectado pelo serviço de acessibilidade.
        scope.launch { bridge.killRequests.collect { controller.stop() } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Se o sistema destruir o serviço no meio de uma tarefa, ela é tratada como cancelada.
        controller.stop()
        overlay.hide()
        notifications.cancelConfirmation()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopNow() {
        overlay.hide()
        notifications.cancelConfirmation()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(state: AgentState) {
        ServiceCompat.startForeground(
            this,
            AgentNotifications.PROGRESS_ID,
            notifications.progress(state, controller.steps.value),
            // O tipo specialUse só existe no Android 14+; antes disso o FGS não precisa de tipo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val ACTION_START = "com.melanta.action.START_AGENT"
        private const val EXTRA_TASK = "task"

        internal fun startIntent(context: Context, task: String): Intent =
            Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task)
    }
}
