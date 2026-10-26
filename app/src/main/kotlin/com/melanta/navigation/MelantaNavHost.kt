package com.melanta.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.melanta.automation.AutomationDebugRoute
import com.melanta.feature.agent.ui.AgentRoute
import com.melanta.models.ModelsRoute
import com.melanta.permissions.PermissionOnboardingRoute
import com.melanta.playground.PlaygroundRoute
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingDestination

@Serializable
data object AgentDestination

@Serializable
data object ModelsDestination

@Serializable
data object PlaygroundDestination

@Serializable
data object AutomationDebugDestination

@Composable
fun MelantaNavHost(startWithOnboarding: () -> Boolean) {
    val navController = rememberNavController()
    // Decidido uma única vez: recriações da Activity restauram a pilha de navegação.
    val onboardingFirst = rememberSaveable { startWithOnboarding() }

    NavHost(
        navController = navController,
        startDestination = if (onboardingFirst) OnboardingDestination else AgentDestination,
    ) {
        composable<OnboardingDestination> {
            PermissionOnboardingRoute(
                onContinue = {
                    if (!navController.popBackStack(AgentDestination, inclusive = false)) {
                        navController.navigate(AgentDestination) {
                            popUpTo<OnboardingDestination> { inclusive = true }
                        }
                    }
                },
            )
        }
        composable<AgentDestination> {
            AgentRoute(
                onOpenModels = { navController.navigate(ModelsDestination) },
                onOpenPermissions = { navController.navigate(OnboardingDestination) },
                onOpenPlayground = { navController.navigate(PlaygroundDestination) },
                onOpenAutomation = { navController.navigate(AutomationDebugDestination) },
            )
        }
        composable<ModelsDestination> {
            ModelsRoute(onBack = { navController.popBackStack() })
        }
        composable<PlaygroundDestination> {
            PlaygroundRoute(onBack = { navController.popBackStack() })
        }
        composable<AutomationDebugDestination> {
            AutomationDebugRoute(onBack = { navController.popBackStack() })
        }
    }
}
