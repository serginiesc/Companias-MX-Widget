package mx.mitelcel.widget.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** Una foto del consumo en un momento: con varias se calcula el ritmo real de gasto. */
@Serializable
data class UsageSample(val timestampMillis: Long, val consumedMB: Double)

/** Respuesta a "¿me alcanzan los datos hasta el corte?". */
sealed interface Forecast {
    /** A este ritmo sobran datos al llegar al corte. */
    data object Enough : Forecast

    /** A este ritmo se acaban el día [date] (antes del corte). */
    data class RunsOut(val date: LocalDate) : Forecast

    /** No hay datos suficientes para estimar (ej. inicio de ciclo). */
    data object Unknown : Forecast
}

/**
 * Pronóstico de consumo. Prefiere el ritmo REAL medido con el historial; si todavía no hay
 * historial suficiente, lo estima suponiendo un ciclo de [DEFAULT_CYCLE_DAYS] días.
 */
object Forecaster {
    const val DEFAULT_CYCLE_DAYS = 30
    private const val MAX_RATE_WINDOW_DAYS = 7L
    private const val MIN_HISTORY_HOURS = 20L

    fun forecast(entry: UsageEntry, history: List<UsageSample>, now: Instant, zone: ZoneId): Forecast {
        if (entry.totalMB <= 0) return Forecast.Unknown
        val rate = measuredRate(entry, history, now) ?: estimatedRate(entry) ?: return Forecast.Unknown
        if (rate <= 0.0) return Forecast.Enough

        val daysToEmpty = maxOf(0.0, entry.availableMB) / rate
        if (daysToEmpty >= entry.daysLeft) return Forecast.Enough
        val today = now.atZone(zone).toLocalDate()
        return Forecast.RunsOut(today.plusDays(floor(daysToEmpty).toLong()))
    }

    /**
     * MB por día según el historial de los últimos [MAX_RATE_WINDOW_DAYS] días.
     * Si el consumo "baja" respecto a una muestra anterior, es que empezó un ciclo nuevo:
     * se descartan las muestras de antes del reinicio.
     */
    internal fun measuredRate(entry: UsageEntry, history: List<UsageSample>, now: Instant): Double? {
        val windowStart = now.minus(MAX_RATE_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli()
        val sameCycle = history
            .filter { it.timestampMillis in windowStart until now.toEpochMilli() }
            .sortedBy { it.timestampMillis }
            .takeLastWhile { it.consumedMB <= entry.consumedMB }
        val oldest = sameCycle.firstOrNull() ?: return null
        val hours = Duration.ofMillis(now.toEpochMilli() - oldest.timestampMillis).toHours()
        if (hours < MIN_HISTORY_HOURS) return null // menos de un día: el ritmo sería muy engañoso
        return (entry.consumedMB - oldest.consumedMB) / (hours / 24.0)
    }

    /** Sin historial: consumo total / días transcurridos del ciclo (supone ciclos de 30 días). */
    internal fun estimatedRate(entry: UsageEntry): Double? {
        val elapsed = DEFAULT_CYCLE_DAYS - entry.daysLeft
        if (elapsed < 1) return null
        return entry.consumedMB / elapsed
    }
}

/** Cómo mostrar la hora de la última consulta, y cuándo un dato ya es "viejo". */
object Freshness {
    /** Más de 3 h sin actualizar: el widget lo avisa en lugar de presentarlo como vigente. */
    val STALE_AFTER: Duration = Duration.ofHours(3)

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    fun isStale(timestampMillis: Long, now: Instant): Boolean =
        timestampMillis > 0 && Duration.ofMillis(now.toEpochMilli() - timestampMillis) > STALE_AFTER

    /** "hoy 10:09", "ayer 22:15" o "18 oct 10:09". Siempre correcto aunque el widget no se redibuje. */
    fun label(timestampMillis: Long, now: Instant, zone: ZoneId): String {
        if (timestampMillis <= 0) return "--:--"
        val then = Instant.ofEpochMilli(timestampMillis).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.atZone(zone).toLocalDate())
        val time = TIME.format(then)
        return when (days) {
            0L -> "hoy $time"
            1L -> "ayer $time"
            else -> "${then.dayOfMonth} ${MONTHS[then.monthValue - 1]} $time"
        }
    }

    fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
}

/** Pie de página del widget: pronóstico + hora, o aviso de dato viejo. */
object StatusLine {
    fun of(entry: UsageEntry, forecast: Forecast, now: Instant, zone: ZoneId): String {
        val time = Freshness.label(entry.timestampMillis, now, zone)
        if (entry.timestampMillis <= 0) return entry.planName // sin datos aún: "Sin conexión o credenciales"
        if (Freshness.isStale(entry.timestampMillis, now)) return "☁ Sin actualizar desde $time"
        return when (forecast) {
            Forecast.Enough -> "✓ Te alcanza · $time"
            is Forecast.RunsOut -> "⚠ Se acaba ~${Freshness.shortDate(forecast.date)} · $time"
            Forecast.Unknown -> time
        }
    }
}

/** ¿Avisar por notificación que quedan pocos datos? Menos de 1 GB o menos del 10% del total. */
object LowData {
    fun isLow(entry: UsageEntry): Boolean =
        entry.totalMB > 0 && (entry.availableMB < entry.mbPerGb || entry.availableMB < entry.totalMB * 0.10)
}
