package com.melanta.permissions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.melanta.core.automation.service.AgentNotificationListenerService

/** Atalhos para as telas do sistema onde cada permissão especial é concedida. */
internal object PermissionIntents {

    fun openAccessibilitySettings(context: Context) =
        context.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openNotificationListenerSettings(context: Context) {
        val component = ComponentName(context, AgentNotificationListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        if (!context.launch(detail)) context.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openOverlaySettings(context: Context) =
        context.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)))

    fun openAppNotificationSettings(context: Context) =
        context.launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )

    /** É nesta tela (menu ⋮) que aparece "Permitir configurações restritas". */
    fun openAppDetails(context: Context) =
        context.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
