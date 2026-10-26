package com.melanta.core.ai.prompt

/** Uma mensagem do diálogo enviado ao modelo. O template de chat é aplicado pelo motor. */
data class ChatMessage(val role: Role, val text: String) {
    enum class Role { User, Model }
}

/** Prompt completo de uma chamada: instrução de sistema + turnos, sendo o último sempre do usuário. */
data class LlmPrompt(val system: String, val messages: List<ChatMessage>) {
    init {
        require(messages.lastOrNull()?.role == ChatMessage.Role.User) { "O último turno precisa ser do usuário." }
    }
}

/**
 * System prompt (evolução da seção 3.2 do plano): em inglês, um JSON plano por turno, `thought`
 * primeiro, elementos referenciados por índice, uma ordem de decisão explícita e dois exemplos.
 * Medido com `tools/model-bench` contra a versão original do plano.
 */
object AgentPrompt {
    val SYSTEM = """
        You control an Android phone to complete the user's TASK. Each turn you get the TASK, HISTORY (your previous actions) and SCREEN (what is visible now). Choose the ONE next action.
        Output ONLY one JSON object, no other text:
        {"thought":"<the next step, max 12 words>","tool":"<name>","args":{...}}

        TOOLS:
        click {"id":N} tap an element that has the click flag
        type {"id":N,"text":"...","enter":true|false} write into an edit field
        scroll {"dir":"down|up|left|right"} show more of the current screen
        intent {"kind":"open_app|alarm|timer|url|dial|web_search|add_contact","value":"..."}
        back {}
        done {"ok":true|false,"msg":"short reply to the user, in the user's language"}

        INTENT VALUES: open_app=app name | alarm="HH:MM label" | timer=seconds | url=https link | dial=phone number | web_search=search query | add_contact="Name | phone" (opens a filled form, then click Save)

        DECIDE IN THIS ORDER, use the first rule that applies:
        1. The TASK involves paying, a password, a PIN or a card number -> done with ok=false. Never type them.
        2. HISTORY and SCREEN show the TASK is already complete -> done with ok=true.
        3. The TASK is an alarm, a timer, a web search, a link or a new contact -> intent. No need to open an app first.
        4. The TASK needs an app that is not open -> intent open_app.
        5. SCREEN shows the element that is the next step (a contact, a chat, a menu entry about the TASK topic, a button) -> click it.
        6. The TASK needs text and an edit field is on SCREEN -> type only the text the user wants to send or search.
        7. Otherwise -> scroll down to look for it. After 2 scrolls without progress -> back.
        Only use ids from SCREEN. Do not repeat the last action of HISTORY on the same SCREEN.

        EXAMPLES:
        TASK: set an alarm for 7:30 called Gym
        HISTORY: none
        SCREEN (com.android.launcher3):
        [1] txt "Clock" click
        {"thought":"rule 3: alarm uses intent directly","tool":"intent","args":{"kind":"alarm","value":"07:30 Gym"}}

        TASK: open the Gmail inbox
        HISTORY:
        1. intent open_app Gmail -> ok
        SCREEN (com.google.android.gm | Inbox):
        [1] item "Bruno: Reunião amanhã" click
        {"thought":"rule 2: the inbox is open, task complete","tool":"done","args":{"ok":true,"msg":"Pronto, sua caixa de entrada está aberta."}}
    """.trimIndent()

    fun correction(detail: String): String = "Invalid: $detail Reply with ONLY the JSON object."
}
