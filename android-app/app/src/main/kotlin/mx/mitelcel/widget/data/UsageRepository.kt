package mx.mitelcel.widget.data

import android.content.Context
import mx.mitelcel.widget.core.BaitClient
import mx.mitelcel.widget.core.Carrier
import mx.mitelcel.widget.core.TelcelClient
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.core.UsageProvider

data class RefreshResult(val entry: UsageEntry, val error: String? = null)

/**
 * Único punto que decide de dónde salen los datos de cada línea: caché reciente, su compañía o respaldo.
 * El widget, el Worker y las pantallas usan esta clase; ninguno habla directo con la red.
 */
class UsageRepository(
    context: Context,
    /** Qué cliente usar para cada compañía. Inyectable para pruebas. */
    private val providers: (Carrier) -> UsageProvider = { defaultProvider(it) },
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
                val entry = providers(account.carrier).fetchUsage(account.phone, password)
                cache.save(accountId, entry)
                RefreshResult(entry)
            } catch (e: Exception) {
                RefreshResult(cachedOrFallback(accountId), e.message ?: "Error de conexión")
            }
        }
    }

    private companion object {
        val LOCK = Any()

        // Un `when` exhaustivo: si se agrega una compañía al enum y se olvida aquí, NO compila.
        fun defaultProvider(carrier: Carrier): UsageProvider = when (carrier) {
            Carrier.TELCEL -> TelcelClient()
            Carrier.BAIT -> BaitClient()
        }
    }
}
