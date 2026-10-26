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
 * primeiro, elementos referenciados por índice, uma ordem de decisão explícita e três exemplos.
 * Cada mudança é medida com `tools/model-bench`: modelos pequenos são sensíveis à redação
 * (trocar só a regra 3 já moveu o Gemma 4 E2B de 14 para 22 acertos em 28).
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
        intent {"kind":"<see INTENT VALUES>","value":"..."}
        back {}
        done {"ok":true|false,"msg":"short reply to the user, in the user's language"}

        INTENT VALUES:
        open_app=app name; alarm="HH:MM label"; timer=seconds; web_search=query; url=https link; navigate=destination
        dial=phone or contact name; sms="contact or phone | text"; whatsapp="contact or phone | text"; email="address | subject | body"
        add_contact="Name | phone"; event="Title | YYYY-MM-DD HH:MM"; settings=wifi|bluetooth|display|sound|battery|location|airplane|apps|notifications

        DECIDE IN THIS ORDER, use the first rule that applies:
        1. The TASK involves paying, a password, a PIN or a card number -> done with ok=false. Never type them.
        2. HISTORY and SCREEN show the TASK is already complete -> done with ok=true.
        3. The TASK is an alarm, timer, call, sms, whatsapp, email, calendar event, new contact, route, web search, link or a phone setting -> intent of that kind, even when the app icon is on SCREEN.
        4. The TASK needs an app that is not open -> intent open_app.
        5. SCREEN shows the element that is the next step (a contact, a chat, a menu entry about the TASK topic, a switch to turn something on or off, a Send or Save button) -> click it.
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

        TASK: send "on my way" to Bruno on WhatsApp
        HISTORY: none
        SCREEN (com.android.launcher3):
        [1] txt "WhatsApp" click
        {"thought":"rule 3: whatsapp intent fills the chat","tool":"intent","args":{"kind":"whatsapp","value":"Bruno | on my way"}}
    """.trimIndent()

    fun correction(detail: String): String = "Invalid: $detail Reply with ONLY the JSON object."
}
