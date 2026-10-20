package mx.mitelcel.widget.data

import android.content.Context
import kotlinx.serialization.json.Json
import mx.mitelcel.widget.core.UsageEntry

/** Última consulta exitosa, para mostrar algo al instante y cuando no hay red. */
class UsageCache(context: Context) {
    private val prefs = context.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(entry: UsageEntry) {
        prefs.edit().putString(KEY, json.encodeToString(UsageEntry.serializer(), entry.copy(isCached = false))).apply()
    }

    /** Dato guardado, marcado como "de caché" (el widget muestra ☁). */
    fun load(): UsageEntry? = read()?.copy(isCached = true)

    /** Solo si es más reciente que [maxAgeMillis]; se trata como dato vigente, no como caché. */
    fun loadFresh(maxAgeMillis: Long): UsageEntry? {
        if (maxAgeMillis <= 0) return null
        return read()?.takeIf { System.currentTimeMillis() - it.timestampMillis < maxAgeMillis }
    }

    private fun read(): UsageEntry? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString(UsageEntry.serializer(), raw) }.getOrNull()
    }

    private companion object {
        const val KEY = "last_entry"
    }
}
