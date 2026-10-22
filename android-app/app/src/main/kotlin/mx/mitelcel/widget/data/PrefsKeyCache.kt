package mx.mitelcel.widget.data

import android.content.Context
import mx.mitelcel.widget.core.KeyCache

/**
 * Recuerda entre ejecuciones las llaves PÚBLICAS de cada compañía (las que vienen en el JavaScript
 * de su sitio). No son secretos del usuario, por eso no se cifran; las contraseñas van en AccountStore.
 */
class PrefsKeyCache(context: Context) : KeyCache {
    private val prefs = context.getSharedPreferences("carrier_public_keys", Context.MODE_PRIVATE)

    override fun get(name: String): String? = prefs.getString(name, null)

    override fun put(name: String, value: String) {
        prefs.edit().putString(name, value).apply()
    }
}
