package mx.mitelcel.widget.data

import android.content.Context
import kotlinx.serialization.json.Json
import mx.mitelcel.widget.core.UsageEntry

/** Última consulta exitosa de cada línea, para mostrar algo al instante y cuando no hay red. */
class UsageCache(context: Context) {
    private val prefs = context.getSharedPreferences("usage_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(accountId: String, entry: UsageEntry) {
        prefs.edit()
            .putString(key(accountId), json.encodeToString(UsageEntry.serializer(), entry.copy(isCached = false)))
            .apply()
    }

    /** Dato guardado, marcado como "de caché" (el widget muestra ☁). */
    fun load(accountId: String): UsageEntry? = read(accountId)?.copy(isCached = true)

    /** Solo si es más reciente que [maxAgeMillis]; se trata como dato vigente, no como caché. */
    fun loadFresh(accountId: String, maxAgeMillis: Long): UsageEntry? {
        if (maxAgeMillis <= 0) return null
        return read(accountId)?.takeIf { System.currentTimeMillis() - it.timestampMillis < maxAgeMillis }
    }

    fun delete(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun read(accountId: String): UsageEntry? {
        val raw = prefs.getString(key(accountId), null) ?: return null
        return runCatching { json.decodeFromString(UsageEntry.serializer(), raw) }.getOrNull()
    }

    private fun key(accountId: String) = "entry_$accountId"
}
