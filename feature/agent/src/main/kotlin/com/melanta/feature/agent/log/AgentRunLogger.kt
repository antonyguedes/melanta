package com.melanta.feature.agent.log

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Registro local dos passos de cada execução. Nada sai do aparelho. */
interface AgentRunLogger {
    fun begin(task: String)
    fun event(type: String, vararg fields: Pair<String, Any?>)
    fun end()
}

/** Um arquivo JSONL por execução em `filesDir/agent-logs`, mantendo as [KEEP] mais recentes. */
@Singleton
class FileAgentRunLogger @Inject constructor(
    @ApplicationContext context: Context,
) : AgentRunLogger {

    private val dir = File(context.filesDir, "agent-logs")
    private var current: File? = null

    @Synchronized
    override fun begin(task: String) {
        dir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        current = File(dir, "$name.jsonl")
        event("start", "task" to task)
        dir.listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    @Synchronized
    override fun event(type: String, vararg fields: Pair<String, Any?>) {
        val file = current ?: return
        val line = buildJsonObject {
            put("t", JsonPrimitive(System.currentTimeMillis()))
            put("type", JsonPrimitive(type))
            fields.forEach { (key, value) ->
                put(
                    key,
                    when (value) {
                        null -> JsonPrimitive(null as String?)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        }
        try {
            file.appendText(line.toString() + "\n")
        } catch (e: IOException) {
            Log.w(TAG, "Falha ao gravar log do agente", e)
        }
    }

    @Synchronized
    override fun end() {
        current = null
    }

    private companion object {
        const val TAG = "AgentRunLogger"
        const val KEEP = 20
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AgentRunLoggerModule {
    @Binds abstract fun bind(impl: FileAgentRunLogger): AgentRunLogger
}
