package com.melanta

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.melanta.navigation.MelantaNavHost
import com.melanta.permissions.PermissionChecker
import com.melanta.core.designsystem.MelantaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionChecker: PermissionChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tema sempre escuro (paleta ameixa + preto tático): ícones claros nas barras do sistema.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MelantaTheme {
                MelantaNavHost(startWithOnboarding = { !permissionChecker.snapshot().requiredGranted })
            }
        }
    }
}
