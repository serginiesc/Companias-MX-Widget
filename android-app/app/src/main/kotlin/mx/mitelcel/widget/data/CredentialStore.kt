package mx.mitelcel.widget.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val phone: String, val password: String)

/**
 * Guarda número y contraseña. La contraseña se cifra con AES-GCM usando una llave que
 * vive dentro del Android Keystore (hardware seguro): la app puede usarla pero nunca leerla,
 * y no sale del teléfono. Equivale al Keychain de iOS.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_PHONE, credentials.phone)
            .putString(KEY_PASSWORD, encrypt(credentials.password))
            .apply()
    }

    /** null si no hay datos o si la llave ya no existe (ej. el usuario borró datos de seguridad). */
    fun load(): Credentials? {
        val phone = prefs.getString(KEY_PHONE, null) ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        val password = runCatching { decrypt(encrypted) }.getOrNull() ?: return null
        return Credentials(phone, password)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // El IV (nonce) lo genera el Keystore; lo guardamos al inicio porque se necesita para descifrar.
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(payload)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.getDecoder().decode(encoded)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mitelcel_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_PHONE = "phone"
        const val KEY_PASSWORD = "password_enc"
    }
}
