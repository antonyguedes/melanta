package com.melanta.core.ai.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Chaves de API por destino ([CloudConfig.destinationId]): a chave de um provedor nunca vai para outro. */
interface ApiKeyStore {
    fun get(destinationId: String): String?
    fun put(destinationId: String, apiKey: String)
    fun remove(destinationId: String)
}

/**
 * Guarda a chave cifrada com AES-GCM, com uma chave que não sai do Android Keystore. O destino entra
 * como dado autenticado: um blob copiado para outro destino não decifra. Fica fora de backups
 * (`allowBackup=false` e `data_extraction_rules`).
 */
@Singleton
class ApiKeyVault @Inject constructor(@ApplicationContext context: Context) : ApiKeyStore {

    private val prefs = context.getSharedPreferences("cloud_api_keys", Context.MODE_PRIVATE)

    override fun get(destinationId: String): String? {
        val blob = prefs.getString(destinationId, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.updateAAD(destinationId.toByteArray())
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
        } catch (_: GeneralSecurityException) {
            // Chave do Keystore perdida ou blob adulterado: o usuário informa a chave de novo.
            remove(destinationId)
            null
        }
    }

    override fun put(destinationId: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(destinationId.toByteArray())
        val blob = cipher.iv + cipher.doFinal(apiKey.toByteArray())
        prefs.edit().putString(destinationId, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun remove(destinationId: String) {
        prefs.edit().remove(destinationId).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "melanta_cloud_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
