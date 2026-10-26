package com.melanta.core.automation.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatcherTest {

    private val contacts = listOf(
        "Ana Paula Souza" to "+55 11 90000-0001",
        "Ana" to "+55 11 90000-0002",
        "Mariana" to "+55 11 90000-0003",
        "Mãe" to "+55 11 90000-0004",
    )

    @Test
    fun `nome exato vence nome parecido`() {
        assertEquals("+55 11 90000-0002", ContactMatcher.best(AppMatcher.normalize("Ana"), contacts))
        assertEquals("+55 11 90000-0004", ContactMatcher.best(AppMatcher.normalize("mae"), contacts))
        assertEquals("+55 11 90000-0001", ContactMatcher.best(AppMatcher.normalize("Ana Paula"), contacts))
        assertNull(ContactMatcher.best("jose", emptyList()))
    }
}
