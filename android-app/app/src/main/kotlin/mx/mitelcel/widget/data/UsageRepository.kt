package mx.mitelcel.widget.data

import android.content.Context
import mx.mitelcel.widget.core.TelcelClient
import mx.mitelcel.widget.core.UsageEntry

data class RefreshResult(val entry: UsageEntry, val error: String? = null)

/**
 * Único punto que decide de dónde salen los datos de cada línea: caché reciente, Telcel o respaldo.
 * El widget, el Worker y las pantallas usan esta clase; ninguno habla directo con la red.
 */
class UsageRepository(
    context: Context,
    private val client: TelcelClient = TelcelClient(),
    val accounts: AccountStore = AccountStore(context),
    private val cache: UsageCache = UsageCache(context),
) {
    fun cachedOrFallback(accountId: String?): UsageEntry =
        accountId?.let { cache.load(it) } ?: UsageEntry.errorFallback

    fun deleteAccount(accountId: String) {
        accounts.delete(accountId)
        cache.delete(accountId)
    }

    /**
     * Bloqueante: llamar solo en hilo de fondo.
     * @param minAgeMillis si el dato guardado es más nuevo que esto, no se consulta a Telcel.
     */
    fun refresh(accountId: String, minAgeMillis: Long): RefreshResult {
        // El lock hace que las consultas vayan UNA POR UNA, aunque haya varias líneas o el Worker
        // coincida con un toque al widget. Dos sesiones SSO a la vez provocan bloqueos en Mi Telcel.
        synchronized(LOCK) {
            cache.loadFresh(accountId, minAgeMillis)?.let { return RefreshResult(it) }

            val account = accounts.get(accountId)
                ?: return RefreshResult(UsageEntry.errorFallback, "La línea ya no existe")
            val password = accounts.password(accountId)
                ?: return RefreshResult(cachedOrFallback(accountId), "Vuelve a escribir la contraseña de ${account.alias}")

            return try {
                val entry = client.fetchUsage(account.phone, password)
                cache.save(accountId, entry)
                RefreshResult(entry)
            } catch (e: Exception) {
                RefreshResult(cachedOrFallback(accountId), e.message ?: "Error de conexión")
            }
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
