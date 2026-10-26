package com.melanta.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.R
import com.melanta.core.designsystem.PlumButton

@Composable
fun PermissionOnboardingRoute(
    onContinue: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // Depois de uma recusa definitiva o sistema não mostra mais o diálogo: vamos às Configurações.
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
        viewModel.refresh()
    }
    var contactsAsked by rememberSaveable { mutableStateOf(false) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        contactsAsked = true
        viewModel.refresh()
    }

    PermissionOnboardingScreen(
        state = state,
        onAccessibility = { PermissionIntents.openAccessibilitySettings(context) },
        onRestrictedSettings = { PermissionIntents.openAppDetails(context) },
        onNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionIntents.openAppNotificationSettings(context)
            }
        },
        onNotificationListener = { PermissionIntents.openNotificationListenerSettings(context) },
        onOverlay = { PermissionIntents.openOverlaySettings(context) },
        onContacts = {
            if (!contactsAsked) contactsLauncher.launch(Manifest.permission.READ_CONTACTS) else PermissionIntents.openAppDetails(context)
        },
        onContinue = onContinue,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionOnboardingScreen(
    state: PermissionSnapshot,
    onAccessibility: () -> Unit,
    onRestrictedSettings: () -> Unit,
    onNotifications: () -> Unit,
    onNotificationListener: () -> Unit,
    onOverlay: () -> Unit,
    onContacts: () -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium)

            PermissionCard(
                title = R.string.permission_accessibility_title,
                body = R.string.permission_accessibility_body,
                level = R.string.permission_required,
                granted = state.accessibilityEnabled,
                actionLabel = R.string.permission_open_settings,
                onAction = onAccessibility,
                note = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                    R.string.permission_accessibility_waiting
                } else {
                    null
                },
            )
            if (state.mayNeedRestrictedSettings && !state.accessibilityEnabled) {
                RestrictedSettingsCard(onAction = onRestrictedSettings)
            }
            PermissionCard(
                title = R.string.permission_notifications_title,
                body = R.string.permission_notifications_body,
                level = R.string.permission_required,
                granted = state.postNotifications,
                actionLabel = R.string.permission_allow,
                onAction = onNotifications,
            )
            PermissionCard(
                title = R.string.permission_listener_title,
                body = R.string.permission_listener_body,
                level = R.string.permission_recommended,
                granted = state.notificationListener,
                actionLabel = R.string.permission_open_settings,
                onAction = onNotificationListener,
            )
            PermissionCard(
                title = R.string.permission_contacts_title,
                body = R.string.permission_contacts_body,
                level = R.string.permission_recommended,
                granted = state.contacts,
                actionLabel = R.string.permission_allow,
                onAction = onContacts,
            )
            PermissionCard(
                title = R.string.permission_overlay_title,
                body = R.string.permission_overlay_body,
                level = R.string.permission_optional,
                granted = state.overlay,
                actionLabel = R.string.permission_open_settings,
                onAction = onOverlay,
            )

            PlumButton(
                onClick = onContinue,
                enabled = state.requiredGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
            Spacer(Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes level: Int,
    granted: Boolean,
    @StringRes actionLabel: Int,
    onAction: () -> Unit,
    @StringRes note: Int? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(level),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            note?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (granted) R.string.permission_granted else R.string.permission_denied),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (!granted) {
                    FilledTonalButton(onClick = onAction) { Text(stringResource(actionLabel)) }
                }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsCard(onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permission_restricted_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permission_restricted_body), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAction) { Text(stringResource(R.string.permission_restricted_action)) }
        }
    }
}
