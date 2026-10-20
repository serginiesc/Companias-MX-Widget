package mx.mitelcel.widget.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Una bolsa de datos (ej. "0-0-0" Plan Base, "0-1-0" Datos Promocionales). */
@Serializable
data class BucketUsage(
    val id: String,
    val name: String,
    val consumedMB: Double,
    val totalMB: Double,
    val availableMB: Double,
)

/**
 * Resultado de una consulta: suma de todas las bolsas + fecha de corte.
 * Equivalente a `TelcelUsageEntry` de la versión iOS.
 */
@Serializable
data class UsageEntry(
    val timestampMillis: Long,
    val consumedMB: Double,
    val totalMB: Double,
    val availableMB: Double,
    val daysLeft: Int,
    val cutoffDate: String,
    val planName: String,
    val buckets: List<BucketUsage>,
    val updatedAt: String,
    val isCached: Boolean = false,
) {
    val hasPromo: Boolean get() = buckets.size > 1

    val remainingGB: Double get() = max(0.0, availableMB / 1000.0)

    val totalGB: Double get() = totalMB / 1000.0

    val usageRatio: Double
        get() = if (totalMB > 0) min(1.0, max(0.0, consumedMB / totalMB)) else 0.0

    val percentUsed: Int get() = (usageRatio * 100.0).roundToInt()

    /** "28 de octubre" -> "28 oct" */
    val shortCutoffDate: String
        get() = cutoffDate.replace(SHORT_MONTH_REGEX, " $1")

    /** Datos disponibles por día hasta la fecha de corte. */
    val dailyAllowanceText: String
        get() {
            val dailyMB = max(0.0, availableMB) / max(1, daysLeft)
            return if (dailyMB >= 1000) fmt("%.2f GB/d", dailyMB / 1000.0) else fmt("%.0f MB/d", dailyMB)
        }

    val spentShortText: String
        get() = if (consumedMB < 1000) fmt("%.0f MB", consumedMB) else fmt("%.2f GB", consumedMB / 1000.0)

    val daysLeftText: String get() = "$daysLeft ${if (daysLeft == 1) "día" else "días"}"

    val footerText: String
        get() = "${if (hasPromo) "Plan+Promo" else planName} • ⏱ $updatedAt${if (isCached) " ☁" else ""}"

    companion object {
        private val SHORT_MONTH_REGEX =
            Regex("""\s+de\s+([a-záéíóú]{3})[a-záéíóú]*""", RegexOption.IGNORE_CASE)

        /**
         * Locale.US a propósito: con el locale del teléfono (ej. es-ES) "%.2f" daría "5,47"
         * y el widget mostraría formatos distintos según la configuración regional.
         */
        internal fun fmt(pattern: String, value: Double): String = String.format(Locale.US, pattern, value)

        val errorFallback = UsageEntry(
            timestampMillis = 0,
            consumedMB = 0.0,
            totalMB = 0.0,
            availableMB = 0.0,
            daysLeft = 0,
            cutoffDate = "Verificar cuenta",
            planName = "Sin conexión o credenciales",
            buckets = emptyList(),
            updatedAt = "--:--",
            isCached = true,
        )
    }
}
