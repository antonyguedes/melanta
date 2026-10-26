package com.melanta.feature.agent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.melanta.feature.agent.R
import com.melanta.feature.agent.safety.AgentNotificationReceiver
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentStep
import com.melanta.feature.agent.state.task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notificações do agente enquanto ele trabalha em segundo plano:
 * - progresso (a do serviço em primeiro plano): tarefa, passo atual em PT-BR e barra de progresso.
 *   No Android 16+ é pedida como "Live Update", que vira um chip na barra de status ("Passo 3");
 * - confirmação: heads-up com Permitir/Negar;
 * - resultado: aviso ao terminar, porque o usuário provavelmente está em outro app.
 */
@Singleton
class AgentNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        // Canal antigo era IMPORTANCE_LOW: no Android 16 ia para "Silenciosas", recolhido, e o PARAR sumia.
        system.deleteNotificationChannel(LEGACY_CHANNEL_RUN)
        if (system.getNotificationChannel(CHANNEL_RUN) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_RUN, context.getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.agent_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        if (system.getNotificationChannel(CHANNEL_CONFIRM) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFIRM, context.getString(R.string.agent_confirm_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        if (system.getNotificationChannel(CHANNEL_RESULT) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, context.getString(R.string.agent_result_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** Atualiza o progresso e a confirmação pendente, se houver. */
    fun update(state: AgentState, steps: List<AgentStep>) {
        post(PROGRESS_ID, progress(state, steps))
        if (state is AgentState.AwaitingConfirmation) {
            post(CONFIRM_ID, confirmation(state))
        } else {
            manager.cancel(CONFIRM_ID)
        }
    }

    /** Avisa o fim da tarefa. Estados não terminais não geram aviso. */
    fun postResult(state: AgentState, steps: List<AgentStep>) {
        manager.cancel(CONFIRM_ID)
        val (title, text) = when (state) {
            is AgentState.Finished -> context.getString(R.string.agent_result_finished) to state.message
            is AgentState.Failed -> context.getString(R.string.agent_result_failed) to state.reason
            is AgentState.Cancelled -> context.getString(R.string.agent_result_cancelled) to state.task
            else -> return
        }
        val summary = steps.joinToString("\n") { step -> "${step.index + 1}. ${step.action} ${statusMark(step.status)}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(PLUM)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(state.task)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (summary.isEmpty()) text else "$text\n\n$summary"))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(RESULT_ID, notification)
    }

    fun cancelConfirmation() = manager.cancel(CONFIRM_ID)

    fun progress(state: AgentState, steps: List<AgentStep>): Notification {
        val step = (state as? AgentState.Running)?.step ?: (state as? AgentState.AwaitingConfirmation)?.step
        val current = step?.let { index -> steps.lastOrNull { it.index == index } }
        val text = when (state) {
            is AgentState.Running -> when (state.phase) {
                AgentState.Phase.Perceiving -> context.getString(R.string.agent_progress_reading)
                AgentState.Phase.Reasoning -> context.getString(R.string.agent_progress_thinking)
                AgentState.Phase.Acting -> current?.action ?: context.getString(R.string.agent_progress_acting)
            }
            is AgentState.AwaitingConfirmation -> context.getString(R.string.agent_progress_confirm, state.description)
            else -> context.getString(R.string.agent_notification_loading)
        }
        val chip = when (state) {
            is AgentState.AwaitingConfirmation -> context.getString(R.string.agent_chip_confirm)
            is AgentState.Running -> context.getString(R.string.agent_chip_step, state.step + 1)
            else -> context.getString(R.string.agent_chip_loading)
        }
        val previous = steps.filter { it.index != step }.takeLast(RECENT_STEPS)
            .joinToString("\n") { "${it.index + 1}. ${it.action} ${statusMark(it.status)}" }

        return NotificationCompat.Builder(context, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(PLUM)
            .setContentTitle(state.task?.take(MAX_TITLE) ?: context.getString(R.string.agent_notification_title))
            .setContentText(text)
            .setSubText(step?.let { context.getString(R.string.agent_chip_step, it + 1) })
            // Expandida, mostra os últimos passos. BigText também é um estilo aceito para Live Update.
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (previous.isEmpty()) text else "$text\n\n$previous"))
            // Barra indeterminada: o número de passos não é conhecido de antemão.
            .setProgress(0, 0, state !is AgentState.AwaitingConfirmation)
            // Android 16+: vira chip na barra de status com este texto curto.
            .setShortCriticalText(chip)
            .setRequestPromotedOngoing(true)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.agent_notification_stop), AgentNotificationReceiver.stop(context))
            .build()
    }

    private fun confirmation(state: AgentState.AwaitingConfirmation): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(PLUM)
            .setContentTitle(context.getString(R.string.agent_confirm_title))
            .setContentText(state.description)
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.agent_confirm_allow), AgentNotificationReceiver.approve(context))
            .addAction(0, context.getString(R.string.agent_confirm_deny), AgentNotificationReceiver.deny(context))
            .build()

    private fun statusMark(status: AgentStep.Status): String = when (status) {
        AgentStep.Status.Done -> "✓"
        AgentStep.Status.Running, AgentStep.Status.AwaitingConfirmation -> "…"
        is AgentStep.Status.Failed -> "✗"
        is AgentStep.Status.Blocked -> "⛔"
        AgentStep.Status.Denied -> "—"
    }

    private fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Sem a permissão de notificações o usuário ainda tem o overlay e o app; só não há aviso na barra. */
    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    companion object {
        const val PROGRESS_ID = 0x4147 // "AG"
        private const val CONFIRM_ID = 0x4143 // "AC"
        private const val RESULT_ID = 0x4152 // "AR"
        private const val LEGACY_CHANNEL_RUN = "agent_run"
        private const val CHANNEL_RUN = "agent_run_v2"
        private const val CHANNEL_CONFIRM = "agent_confirm"
        private const val CHANNEL_RESULT = "agent_result"
        private const val MAX_TITLE = 60
        private const val RECENT_STEPS = 4
        private val PLUM = "#4A2E4B".toColorInt()
    }
}
