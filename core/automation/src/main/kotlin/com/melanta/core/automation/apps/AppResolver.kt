package com.melanta.core.automation.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class LaunchableApp(val label: String, val packageName: String, val activityName: String)

/** Resolve "whatsapp", "configurações", "camera" para o app instalado mais provável. */
@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cache: List<LaunchableApp>? = null

    fun resolve(name: String): LaunchableApp? = AppMatcher.bestMatch(name, apps()) ?: run {
        // App instalado depois do último cache.
        cache = null
        AppMatcher.bestMatch(name, apps())
    }

    fun apps(): List<LaunchableApp> = cache ?: load().also { cache = it }

    private fun load(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
    }
}

/** Busca aproximada pura, para testes na JVM. */
object AppMatcher {

    /** Nomes que o modelo usa em inglês ou de forma genérica para apps de sistema em português. */
    private val SYNONYMS = mapOf(
        "settings" to listOf("configuracoes", "ajustes", "definicoes"),
        "camera" to listOf("camera", "camara"),
        "clock" to listOf("relogio"),
        "calendar" to listOf("agenda", "calendario"),
        "phone" to listOf("telefone"),
        "messages" to listOf("mensagens"),
        "contacts" to listOf("contatos"),
        "photos" to listOf("fotos"),
        "gallery" to listOf("galeria"),
        "calculator" to listOf("calculadora"),
        "files" to listOf("arquivos"),
        "browser" to listOf("chrome", "navegador"),
    )

    fun bestMatch(query: String, apps: List<LaunchableApp>): LaunchableApp? {
        // Modelos às vezes mandam o nome do pacote ("com.whatsapp") em vez do nome do app.
        val trimmed = query.trim()
        apps.firstOrNull { it.packageName.equals(trimmed, ignoreCase = true) }?.let { return it }
        if ('.' in trimmed && ' ' !in trimmed) {
            // Pacote de outro fabricante ("com.android.contacts" num Pixel, que usa "com.google.android.contacts"):
            // compara o último segmento e, se não achar, usa-o como nome ("contacts" -> "Contatos").
            val last = trimmed.substringAfterLast('.')
            apps.firstOrNull { it.packageName.substringAfterLast('.').equals(last, ignoreCase = true) }?.let { return it }
            return bestMatch(last, apps)
        }
        val q = normalize(query)
        if (q.isEmpty()) return null
        val queries = listOf(q) + SYNONYMS[q].orEmpty() +
            SYNONYMS.filterValues { q in it }.keys
        return apps
            .map { app -> app to queries.maxOf { score(it, app) } }
            .filter { (_, score) -> score >= MIN_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun score(query: String, app: LaunchableApp): Double {
        val label = normalize(app.label)
        val pkg = app.packageName.lowercase()
        return when {
            label == query -> 1.0
            label.startsWith(query) || query.startsWith(label) -> 0.9
            label.split(' ').any { it == query } -> 0.85
            pkg.split('.').any { it == query.replace(" ", "") } -> 0.8
            label.contains(query) -> 0.75
            else -> similarity(query, label) * 0.7
        }
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 1 - distância de Levenshtein normalizada. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private const val MIN_SCORE = 0.5
}
