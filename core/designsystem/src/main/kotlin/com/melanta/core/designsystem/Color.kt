package com.melanta.core.designsystem

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Paleta da marca:
 * - Roxo Escuro / Ameixa Místico: #4A2E4B a #2E1430
 * - Preto Tático / Couro: #1A1A1D
 *
 * Roxo escuro sobre preto tem contraste ~1,6:1, ilegível para texto. Por isso o roxo vai em
 * contêineres (botões, cabeçalho, cartões de destaque) e um lilás derivado do mesmo matiz é usado
 * para texto e indicadores sobre o fundo preto (~10:1).
 */
object MelantaColors {
    val Plum = Color(0xFF4A2E4B)
    val PlumDeep = Color(0xFF2E1430)
    val TacticalBlack = Color(0xFF1A1A1D)

    /** Superfícies: preto tático com um toque de ameixa, em degraus para hierarquia. */
    val SurfaceLow = Color(0xFF201E22)
    val Surface = Color(0xFF252229)
    val SurfaceHigh = Color(0xFF2C2730)
    val SurfaceHighest = Color(0xFF342E38)

    /** Lilás derivado da ameixa, para texto e ícones de destaque sobre o preto. */
    val Lilac = Color(0xFFD9B8DA)
    val LilacMuted = Color(0xFFBFA3C1)
    val OnPlum = Color(0xFFF4E6F4)
    val Text = Color(0xFFEDE6EE)
    val TextMuted = Color(0xFFB3A8B5)
    val Outline = Color(0xFF5E4A5F)
    val OutlineVariant = Color(0xFF3A2F3B)

    val Danger = Color(0xFFF2B8B5)
    val DangerContainer = Color(0xFF8C1D18)
    val Success = Color(0xFFA8D5B0)

    /** Degradê da marca, do ameixa para o roxo profundo. */
    val PlumGradient: Brush = Brush.linearGradient(listOf(Plum, PlumDeep))
}
