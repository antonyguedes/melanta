package com.melanta.feature.agent.action

import com.melanta.core.automation.screen.Bounds
import com.melanta.core.automation.screen.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleGoalTest {

    private fun sw(label: String, on: Boolean, role: String = "sw") =
        UiElement(1, role, label, listOf("click", if (on) "checked" else "unchecked"), Bounds(0, 0, 10, 10), null, 4, null)

    @Test
    fun `verbo do pedido define o estado desejado`() {
        assertEquals(ToggleGoal(on = false), ToggleGoal.from("Desliga o wi-fi"))
        assertEquals(ToggleGoal(on = false), ToggleGoal.from("desative o Bluetooth"))
        assertEquals(ToggleGoal(on = true), ToggleGoal.from("Liga o modo avião"))
        assertEquals(ToggleGoal(on = true), ToggleGoal.from("ativa a lanterna"))
        assertEquals(ToggleGoal(on = false), ToggleGoal.from("turn off bluetooth"))
        assertNull(ToggleGoal.from("Liga para a Ana"))
        assertNull(ToggleGoal.from("Abre o YouTube"))
    }

    @Test
    fun `interruptor do pedido e reconhecido pelo rotulo`() {
        val goal = ToggleGoal(on = false)
        assertTrue(goal.matches(sw("Wi‑Fi", on = true), "Desliga o wi-fi"))
        assertTrue(goal.matches(sw("Use Wi-Fi", on = true), "Desliga o wifi"))
        assertFalse(goal.matches(sw("Bluetooth", on = true), "Desliga o wi-fi"))
        assertFalse(goal.matches(sw("Wi-Fi", on = true, role = "btn"), "Desliga o wi-fi"))
        assertTrue(goal.reachedBy(sw("Wi-Fi", on = false)))
        assertFalse(goal.reachedBy(sw("Wi-Fi", on = true)))
    }
}
