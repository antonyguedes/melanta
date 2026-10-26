package com.melanta.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** O app é só escuro: a paleta da marca (ameixa + preto tático) é uma identidade escura. */
private val MelantaColorScheme = darkColorScheme(
    primary = MelantaColors.Lilac,
    onPrimary = MelantaColors.PlumDeep,
    primaryContainer = MelantaColors.Plum,
    onPrimaryContainer = MelantaColors.OnPlum,
    inversePrimary = MelantaColors.Plum,
    secondary = MelantaColors.LilacMuted,
    onSecondary = MelantaColors.PlumDeep,
    secondaryContainer = MelantaColors.PlumDeep,
    onSecondaryContainer = MelantaColors.OnPlum,
    tertiary = MelantaColors.LilacMuted,
    onTertiary = MelantaColors.PlumDeep,
    tertiaryContainer = MelantaColors.PlumDeep,
    onTertiaryContainer = MelantaColors.OnPlum,
    background = MelantaColors.TacticalBlack,
    onBackground = MelantaColors.Text,
    surface = MelantaColors.TacticalBlack,
    onSurface = MelantaColors.Text,
    surfaceVariant = MelantaColors.SurfaceHigh,
    onSurfaceVariant = MelantaColors.TextMuted,
    surfaceContainerLowest = MelantaColors.TacticalBlack,
    surfaceContainerLow = MelantaColors.SurfaceLow,
    surfaceContainer = MelantaColors.Surface,
    surfaceContainerHigh = MelantaColors.SurfaceHigh,
    surfaceContainerHighest = MelantaColors.SurfaceHighest,
    outline = MelantaColors.Outline,
    outlineVariant = MelantaColors.OutlineVariant,
    error = MelantaColors.Danger,
    onError = MelantaColors.PlumDeep,
    errorContainer = MelantaColors.DangerContainer,
    onErrorContainer = MelantaColors.OnPlum,
)

@Composable
fun MelantaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MelantaColorScheme, typography = Typography(), content = content)
}
