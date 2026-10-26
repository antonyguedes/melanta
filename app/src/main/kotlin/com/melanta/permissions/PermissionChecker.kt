package com.melanta.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.melanta.core.automation.service.AgentAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class PermissionSnapshot(
    val accessibilityEnabled: Boolean,
    /** O sistema já fez o bind do serviço. Pode ficar falso alguns instantes depois de ativado. */
    val accessibilityConnected: Boolean = false,
    val postNotifications: Boolean,
    val notificationListener: Boolean,
    val overlay: Boolean,
    /** Android 13+ bloqueia Acessibilidade e Leitor de notificações em APKs fora da loja. */
    val mayNeedRestrictedSettings: Boolean,
) {
    val requiredGranted: Boolean get() = accessibilityEnabled && postNotifications
}

class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshot() = PermissionSnapshot(
        accessibilityEnabled = isAccessibilityEnabled(),
        postNotifications = hasPostNotifications(),
        notificationListener = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        overlay = Settings.canDrawOverlays(context),
        mayNeedRestrictedSettings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !installedFromStore(),
    )

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, AgentAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun hasPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    private fun installedFromStore(): Boolean {
        val installer = try {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return installer in STORE_INSTALLERS
    }

    private companion object {
        val STORE_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")
    }
}
