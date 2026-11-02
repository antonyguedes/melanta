package com.melanta.core.common

import java.text.Normalizer

/**
 * Fonte única do que é sensível: a política de segurança do agente decide com estas regras o que ele
 * nunca faz, e o redator do modo Nuvem decide o que nunca sai do aparelho.
 */
object SensitiveContent {

    /** Bancos, carteiras, cripto, gerenciadores de senha e autenticadores (prefixo de pacote). */
    val BLOCKED_PACKAGE_PREFIXES = listOf(
        "com.nu.production", "br.com.intermedium", "com.itau", "br.com.itau", "com.bradesco", "br.com.bradesco",
        "com.santander", "br.com.santander", "br.com.bb.android", "br.gov.caixa", "br.com.gabba.Caixa",
        "com.c6bank", "br.com.original", "com.btgpactual", "com.mercadopago.wallet", "com.picpay",
        "com.paypal", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.revolut",
        "com.transferwise", "com.binance", "com.coinbase", "br.gov.meugovbr",
        "com.x8bit.bitwarden", "com.lastpass", "com.agilebits.onepassword", "com.dashlane",
        "keepass2android", "com.authy", "com.google.android.apps.authenticator2", "com.azure.authenticator",
    )

    /** Rótulo de campo sensível, procurado em texto já passado por [normalize]. */
    val SENSITIVE_FIELD = words(
        "senha", "password", "passcode", "pin", "cvv", "cvc", "codigo de seguranca", "security code",
        "numero do cartao", "card number", "cartao", "token", "codigo de verificacao", "verification code", "otp",
    )

    fun isBlockedPackage(packageName: String): Boolean =
        BLOCKED_PACKAGE_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }

    fun words(vararg terms: String) =
        Regex(terms.joinToString("|", prefix = "\\b(", postfix = ")\\b") { Regex.escape(it) })

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
