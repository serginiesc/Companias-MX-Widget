package mx.mitelcel.widget.core

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyUsageTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 20)

    private fun at(date: String, hour: Int, consumed: Double) =
        UsageSample(LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), consumed)

    @Test
    fun `convierte acumulado en consumo por día`() {
        val samples = listOf(
            at("2026-10-17", 9, 1000.0),
            at("2026-10-17", 21, 1200.0), // la última del día manda
            at("2026-10-18", 20, 1500.0),
            // 19: sin foto
            at("2026-10-20", 8, 2100.0),
        )

        val days = DailyUsage.fromSamples(samples, today, zone, days = 4)

        assertEquals(listOf("2026-10-17", "2026-10-18", "2026-10-19", "2026-10-20"), days.map { it.date.toString() })
        assertEquals(listOf(0.0, 300.0, 0.0, 600.0), days.map { it.consumedMB })
    }

    @Test
    fun `un ciclo nuevo no da consumo negativo`() {
        val samples = listOf(at("2026-10-19", 22, 9800.0), at("2026-10-20", 10, 150.0))

        val days = DailyUsage.fromSamples(samples, today, zone, days = 1)

        assertEquals(150.0, days.single().consumedMB, 0.001)
    }
}
