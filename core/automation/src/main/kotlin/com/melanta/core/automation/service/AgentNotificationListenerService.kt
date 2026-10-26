package com.melanta.core.automation.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.melanta.core.automation.notifications.NotificationBuffer
import com.melanta.core.automation.notifications.NotificationEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Mantém o [NotificationBuffer] com as notificações recentes (contexto `NOTIFS` do prompt). */
@AndroidEntryPoint
class AgentNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var buffer: NotificationBuffer

    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.sortedBy { it.postTime }?.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) {
            return
        }
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null && text == null) return
        buffer.post(NotificationEntry(sbn.key, appLabel(sbn.packageName), title, text))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        buffer.remove(sbn.key)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
