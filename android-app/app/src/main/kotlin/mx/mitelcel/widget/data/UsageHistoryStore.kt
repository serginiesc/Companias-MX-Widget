package mx.mitelcel.widget.data

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.core.UsageSample

/**
 * Historial del consumo de cada línea (máx. una foto por hora, últimos [KEEP_DAYS] días).
 * Sirve para la gráfica de la app y para medir el ritmo REAL de gasto del pronóstico.
 */
class UsageHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("usage_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(UsageSample.serializer())

    fun samples(accountId: String): List<UsageSample> {
        val raw = prefs.getString(key(accountId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(accountId: String, entry: UsageEntry) {
        if (entry.totalMB <= 0) return
        val cutoff = entry.timestampMillis - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        val kept = samples(accountId).filter { it.timestampMillis >= cutoff }.toMutableList()
        // Una foto por hora basta: si la última es de hace menos de 1 h, se reemplaza.
        if (kept.lastOrNull()?.let { entry.timestampMillis - it.timestampMillis < TimeUnit.HOURS.toMillis(1) } == true) {
            kept.removeAt(kept.lastIndex)
        }
        kept += UsageSample(entry.timestampMillis, entry.consumedMB)
        prefs.edit().putString(key(accountId), json.encodeToString(serializer, kept)).apply()
    }

    fun delete(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String) = "samples_$accountId"

    private companion object {
        const val KEEP_DAYS = 45L
    }
}
