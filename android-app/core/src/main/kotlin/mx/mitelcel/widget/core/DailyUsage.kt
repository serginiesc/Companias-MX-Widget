package mx.mitelcel.widget.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** MB gastados en un día. */
data class DayUsage(val date: LocalDate, val consumedMB: Double)

/**
 * Convierte las fotos del consumo ACUMULADO del ciclo en consumo POR DÍA, para la gráfica.
 * Ejemplo: lunes 1000 MB acumulados, martes 1300 -> el martes se gastaron 300 MB.
 */
object DailyUsage {
    fun fromSamples(samples: List<UsageSample>, today: LocalDate, zone: ZoneId, days: Int = 14): List<DayUsage> {
        // Última foto de cada día (la más reciente es la que refleja el total de ese día).
        val lastOfDay = samples
            .sortedBy { it.timestampMillis }
            .associateBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }

        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val todayTotal = lastOfDay[date]?.consumedMB
            val previous = lastOfDay.filterKeys { it < date }.maxByOrNull { it.key }?.value?.consumedMB
            val used = when {
                todayTotal == null -> 0.0 // sin foto ese día (teléfono apagado, sin red...)
                previous == null -> 0.0 // primer día registrado: no sabemos cuánto llevaba antes
                todayTotal < previous -> todayTotal // empezó un ciclo nuevo: el acumulado se reinició
                else -> todayTotal - previous
            }
            DayUsage(date, used)
        }
    }
}
