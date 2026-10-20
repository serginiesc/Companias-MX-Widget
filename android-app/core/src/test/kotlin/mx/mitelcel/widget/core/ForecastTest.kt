package mx.mitelcel.widget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class ForecastTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    /** 10 GB de plan; [consumed] gastados; [daysLeft] días al corte. */
    private fun entry(consumed: Double, daysLeft: Int, total: Double = 10000.0) = UsageEntry(
        timestampMillis = now.toEpochMilli(),
        consumedMB = consumed,
        totalMB = total,
        availableMB = total - consumed,
        daysLeft = daysLeft,
        cutoffDate = "",
        planName = "Plan",
        buckets = emptyList(),
        updatedAt = "12:00",
    )

    private fun sample(daysAgo: Long, consumed: Double) = UsageSample(now.minus(daysAgo, ChronoUnit.DAYS).toEpochMilli(), consumed)

    @Test
    fun `sin historial estima con un ciclo de 30 días`() {
        // 20 días transcurridos, 4000 MB gastados -> 200 MB/día; quedan 6000 MB y 10 días -> sobran.
        assertEquals(Forecast.Enough, Forecaster.forecast(entry(4000.0, 10), emptyList(), now, zone))

        // 15 días transcurridos, 9000 MB -> 600 MB/día; quedan 1000 MB -> se acaban en 1.6 días.
        assertEquals(
            Forecast.RunsOut(LocalDate.of(2026, 10, 21)),
            Forecaster.forecast(entry(9000.0, 15), emptyList(), now, zone),
        )
    }

    @Test
    fun `con historial usa el ritmo real`() {
        // Hace 2 días llevaba 5000 MB, hoy 7000 -> 1000 MB/día; quedan 3000 MB para 10 días -> se acaban en 3 días.
        val history = listOf(sample(2, 5000.0), sample(1, 6000.0))
        assertEquals(
            Forecast.RunsOut(LocalDate.of(2026, 10, 23)),
            Forecaster.forecast(entry(7000.0, 10), history, now, zone),
        )
    }

    @Test
    fun `ignora muestras de antes de que empezara el ciclo nuevo`() {
        // La de hace 3 días (9000 MB) es del ciclo anterior: hoy el consumo "bajó" a 1000.
        val history = listOf(sample(3, 9000.0), sample(2, 200.0))
        // Ritmo real: (1000 - 200) / 2 días = 400 MB/día; quedan 9000 MB para 28 días -> se acaban.
        val rate = Forecaster.measuredRate(entry(1000.0, 28), history, now)!!
        assertEquals(400.0, rate, 0.01)
    }

    @Test
    fun `menos de un día de historial no cuenta como ritmo real`() {
        val history = listOf(UsageSample(now.minus(2, ChronoUnit.HOURS).toEpochMilli(), 100.0))
        assertNull(Forecaster.measuredRate(entry(500.0, 10), history, now))
    }

    @Test
    fun `al inicio del ciclo o sin plan no se puede saber`() {
        assertEquals(Forecast.Unknown, Forecaster.forecast(entry(0.0, 30), emptyList(), now, zone))
        assertEquals(Forecast.Unknown, Forecaster.forecast(entry(0.0, 10, total = 0.0), emptyList(), now, zone))
    }

    @Test
    fun `la hora se muestra como hoy, ayer o fecha`() {
        val ts = { iso: String -> Instant.parse(iso).toEpochMilli() }
        assertEquals("hoy 10:09", Freshness.label(ts("2026-10-20T10:09:00Z"), now, zone))
        assertEquals("ayer 22:15", Freshness.label(ts("2026-10-19T22:15:00Z"), now, zone))
        assertEquals("18 oct 08:00", Freshness.label(ts("2026-10-18T08:00:00Z"), now, zone))
    }

    @Test
    fun `el pie de página avisa pronóstico o dato viejo`() {
        val fresh = entry(4000.0, 10)
        assertEquals("✓ Te alcanza · hoy 12:00", StatusLine.of(fresh, Forecast.Enough, now, zone))
        assertEquals(
            "⚠ Se acaba ~23 oct · hoy 12:00",
            StatusLine.of(fresh, Forecast.RunsOut(LocalDate.of(2026, 10, 23)), now, zone),
        )

        val old = fresh.copy(timestampMillis = now.minus(5, ChronoUnit.HOURS).toEpochMilli())
        assertTrue(Freshness.isStale(old.timestampMillis, now))
        assertEquals("☁ Sin actualizar desde hoy 07:00", StatusLine.of(old, Forecast.Enough, now, zone))
    }

    @Test
    fun `datos bajos = menos de 1 GB o menos del 10 por ciento`() {
        assertTrue(LowData.isLow(entry(9500.0, 5))) // quedan 500 MB
        assertTrue(LowData.isLow(entry(46000.0, 5, total = 50000.0))) // 4 GB de 50: 8%
        assertFalse(LowData.isLow(entry(4000.0, 5))) // quedan 6 GB
        assertFalse(LowData.isLow(entry(0.0, 5, total = 0.0))) // sin plan: nada que avisar
    }
}
