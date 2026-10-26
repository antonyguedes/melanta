package com.melanta.feature.agent.safety

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.melanta.feature.agent.AgentController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Botões das notificações do agente: PARAR (kill switch) e Permitir/Negar (confirmação). */
class AgentNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = EntryPointAccessors.fromApplication<ReceiverEntryPoint>(context).agentController()
        when (intent.action) {
            ACTION_STOP -> controller.stop()
            ACTION_APPROVE -> controller.confirm(true)
            ACTION_DENY -> controller.confirm(false)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ReceiverEntryPoint {
        fun agentController(): AgentController
    }

    companion object {
        private const val ACTION_STOP = "com.melanta.action.KILL_AGENT"
        private const val ACTION_APPROVE = "com.melanta.action.APPROVE_ACTION"
        private const val ACTION_DENY = "com.melanta.action.DENY_ACTION"

        fun stop(context: Context) = pendingIntent(context, ACTION_STOP, 0)
        fun approve(context: Context) = pendingIntent(context, ACTION_APPROVE, 1)
        fun deny(context: Context) = pendingIntent(context, ACTION_DENY, 2)

        private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, AgentNotificationReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
