package mx.mitelcel.widget.data

import android.content.Context
import mx.mitelcel.widget.core.TelcelClient
import mx.mitelcel.widget.core.UsageEntry

data class RefreshResult(val entry: UsageEntry, val error: String? = null)

/**
 * Único punto que decide de dónde salen los datos: caché reciente, Telcel o respaldo.
 * El widget, el Worker y la pantalla usan esta clase; ninguno habla directo con la red.
 */
class UsageRepository(
    context: Context,
    private val client: TelcelClient = TelcelClient(),
) {
    val credentials = CredentialStore(context)
    private val cache = UsageCache(context)

    fun cachedOrFallback(): UsageEntry = cache.load() ?: UsageEntry.errorFallback

    /**
     * Bloqueante: llamar solo en hilo de fondo.
     * @param minAgeMillis si el dato guardado es más nuevo que esto, no se consulta a Telcel.
     */
    fun refresh(minAgeMillis: Long): RefreshResult {
        // El lock evita dos logins simultáneos (ej. el Worker periódico + un toque al widget).
        // Dos sesiones SSO a la vez es justo lo que provoca bloqueos en Mi Telcel.
        synchronized(LOCK) {
            cache.loadFresh(minAgeMillis)?.let { return RefreshResult(it) }

            val creds = credentials.load()
                ?: return RefreshResult(cachedOrFallback(), "Abre la app y configura tu número y contraseña")

            return try {
                val entry = client.fetchUsage(creds.phone, creds.password)
                cache.save(entry)
                RefreshResult(entry)
            } catch (e: Exception) {
                RefreshResult(cachedOrFallback(), e.message ?: "Error de conexión")
            }
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
