package mx.mitelcel.widget.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra y descifra textos. Es una interfaz para que AccountStore no dependa del Keystore real
 * (Inversión de Dependencias): en pruebas se usa una versión falsa, porque el simulador no tiene Keystore.
 */
interface SecretBox {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/**
 * AES-GCM con una llave que vive dentro del Android Keystore (hardware seguro):
 * la app puede usarla pero nunca leerla, y no sale del teléfono. Equivale al Keychain de iOS.
 */
class KeystoreSecretBox : SecretBox {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        // El IV (nonce) lo genera el Keystore; lo guardamos al inicio porque se necesita para descifrar.
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(payload)
    }

    override fun decrypt(encoded: String): String {
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

        // Mismo alias que la versión 1.1: así las contraseñas ya guardadas se siguen pudiendo descifrar.
        const val KEY_ALIAS = "mitelcel_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
