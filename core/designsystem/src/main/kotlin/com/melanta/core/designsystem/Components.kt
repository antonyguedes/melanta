package com.melanta.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Botão principal da marca: ameixa com texto claro. */
@Composable
fun PlumButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MelantaColors.Plum,
            contentColor = MelantaColors.OnPlum,
            disabledContainerColor = MelantaColors.SurfaceHigh,
            disabledContentColor = MelantaColors.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

/** Botão de parada: o kill switch precisa ser inconfundível em qualquer tela. */
@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MelantaColors.DangerContainer,
            contentColor = MelantaColors.OnPlum,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

/** Cartão de seção sobre o preto tático. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MelantaColors.Surface, contentColor = MelantaColors.Text),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MelantaColors.Lilac)
            }
            content()
        }
    }
}

/** Faixa com o degradê ameixa, para cabeçalhos e o estado atual do agente. */
@Composable
fun PlumGradientBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MelantaColors.PlumGradient)
            .padding(20.dp),
        content = content,
    )
}
