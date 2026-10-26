package com.melanta.core.automation.apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve o nome de um contato ("mãe", "Ana Souza") para o telefone, quando há permissão de Contatos. */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Telefone do contato cujo nome melhor corresponde a [name]; `null` sem permissão ou sem resultado. */
    fun phoneFor(name: String): String? {
        if (!hasPermission()) return null
        val wanted = AppMatcher.normalize(name)
        if (wanted.isEmpty()) return null
        val candidates = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                candidates += display to number
            }
        }
        return ContactMatcher.best(wanted, candidates)
    }
}

/** Escolha pura entre os contatos candidatos, para testes na JVM. */
object ContactMatcher {
    fun best(wantedNormalized: String, candidates: List<Pair<String, String>>): String? =
        candidates
            .map { (display, number) -> AppMatcher.normalize(display) to number }
            .sortedBy { (display, _) ->
                when {
                    display == wantedNormalized -> 0
                    display.split(' ').firstOrNull() == wantedNormalized -> 1
                    display.startsWith(wantedNormalized) -> 2
                    else -> 3
                }
            }
            .firstOrNull()
            ?.second
}
