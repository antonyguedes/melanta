package com.melanta.core.automation.notifications

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NotificationEntry(val key: String, val app: String, val title: String?, val text: String?) {
    /** Formato do bloco `NOTIFS` do prompt: `WhatsApp: Ana "Onde você está?"`. */
    fun line(): String = buildString {
        append(app).append(':')
        title?.let { append(' ').append(it.take(MAX_FIELD)) }
        text?.let { append(" \"").append(it.replace('"', '\'').take(MAX_FIELD)).append('"') }
    }

    private companion object {
        const val MAX_FIELD = 60
    }
}

/** Buffer circular das notificações recentes, alimentado pelo `AgentNotificationListenerService`. */
@Singleton
class NotificationBuffer @Inject constructor() {

    private val _entries = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val entries: StateFlow<List<NotificationEntry>> = _entries.asStateFlow()

    fun post(entry: NotificationEntry) = _entries.update { current ->
        (listOf(entry) + current.filterNot { it.key == entry.key }).take(CAPACITY)
    }

    fun remove(key: String) = _entries.update { current -> current.filterNot { it.key == key } }

    fun recentLines(limit: Int = DEFAULT_PROMPT_LIMIT): List<String> = entries.value.take(limit).map { it.line() }

    companion object {
        const val CAPACITY = 10
        const val DEFAULT_PROMPT_LIMIT = 3
    }
}
