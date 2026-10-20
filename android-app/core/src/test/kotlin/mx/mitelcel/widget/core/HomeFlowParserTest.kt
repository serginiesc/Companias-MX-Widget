package mx.mitelcel.widget.core

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mismo fixture que tests/test_parser.mjs, para garantizar que los 3 parsers dan el mismo resultado. */
class HomeFlowParserTest {
    private val fixture = """
        {"blocks":{"children":[
          {"id":"text-plan-name","config":{"value":"Telcel Libre 4"}},
          {"id":"plan-details-row-template-col-2-web-0-0","config":{"value":"28 de octubre"}},
          {"id":"tabs-plans-v2-plan-name-0-10-0","config":{"value":"Bono Chart KB"}},
          {"id":"tabs-plans-v2-usage-chart-0-10-0","config":{"data":[{"key":"used","value":512},{"key":"available","value":512}]}},
          {"id":"tabs-plans-v2-plan-name-0-1-0","config":{"value":"PROMO 50% DE DATOS 24 MDP"}},
          {"id":"tabs-plans-v2-total-data-value-0-1-0","config":{"value":"5"}},
          {"id":"tabs-plans-v2-total-data-unit-0-1-0","config":{"value":"GB"}},
          {"id":"tabs-plans-v2-used-data-value-0-1-0","config":{"value":"512"}},
          {"id":"tabs-plans-v2-used-data-unit-0-1-0","config":{"value":"KB"}},
          {"id":"tabs-plans-v2-available-data-value-0-1-0","config":{"value":"5"}},
          {"id":"tabs-plans-v2-available-data-unit-0-1-0","config":{"value":"GB"}},
          {"id":"tabs-plans-v2-limited-cutoff-message-0-1-0","config":{"segments":[{"text":"Faltan "},{"text":"9"},{"text":" días"}]}},
          {"id":"tabs-plans-v2-plan-name-0-2-0","config":{"value":"Bolsa vacía"}},
          {"id":"tabs-plans-v2-total-data-value-0-2-0","config":{"value":"0"}},
          {"id":"tabs-plans-v2-total-data-unit-0-2-0","config":{"value":"MB"}},
          {"id":"tabs-plans-v2-plan-name-0-0-0","config":{"value":"Datos de mi plan"}},
          {"id":"tabs-plans-v2-total-data-value-0-0-0","config":{"value":"10"}},
          {"id":"tabs-plans-v2-total-data-unit-0-0-0","config":{"value":"GB"}},
          {"id":"tabs-plans-v2-used-data-value-0-0-0","config":{"value":"9.5"}},
          {"id":"tabs-plans-v2-used-data-unit-0-0-0","config":{"value":"GB"}},
          {"id":"tabs-plans-v2-available-data-value-0-0-0","config":{"value":"474"}},
          {"id":"tabs-plans-v2-available-data-unit-0-0-0","config":{"value":"MB"}}
        ]}}
    """.trimIndent()

    private val now = Instant.parse("2026-10-20T16:09:00Z")

    @Test
    fun `suma todas las bolsas en orden natural e ignora las vacías`() {
        val entry = HomeFlowParser.parse(fixture, now, ZoneOffset.UTC)

        assertEquals("Telcel Libre 4", entry.planName)
        assertEquals("28 de octubre", entry.cutoffDate)
        assertEquals("28 oct", entry.shortCutoffDate)
        assertEquals(9, entry.daysLeft)
        assertEquals(listOf("0-0-0", "0-1-0", "0-10-0"), entry.buckets.map { it.id })
        assertEquals(15001.0, entry.totalMB, 0.0001)
        assertEquals(9501.0, entry.consumedMB, 0.0001)
        assertEquals(5474.5, entry.availableMB, 0.0001)
        assertEquals("16:09", entry.updatedAt)
        assertTrue(entry.hasPromo)
    }

    @Test
    fun `textos del widget coinciden con la versión iOS`() {
        val entry = HomeFlowParser.parse(fixture, now, ZoneOffset.UTC)

        assertEquals("9 días", entry.daysLeftText)
        assertEquals("9.50 GB", entry.spentShortText)
        assertEquals(63, entry.percentUsed)
        assertEquals("608 MB/d", entry.dailyAllowanceText)
        assertEquals("1 día", entry.copy(daysLeft = 1).daysLeftText)
    }

    @Test
    fun `pantalla de error sin bolsas lanza excepción`() {
        val errorScreen = """{"blocks":{"children":[{"id":"alert-error","config":{"value":"Mantenimiento"}}]}}"""
        val e = assertThrows(TelcelException::class.java) { HomeFlowParser.parse(errorScreen, now) }
        assertTrue(e.message!!.contains("sin bolsas de datos"))
    }

    @Test
    fun `respuesta que no es JSON lanza TelcelException`() {
        assertThrows(TelcelException::class.java) { HomeFlowParser.parse("<html>Mantenimiento</html>", now) }
    }

    @Test
    fun `normaliza el teléfono a 10 dígitos`() {
        assertEquals("5512345678", TelcelClient.normalizePhone("+52 (55) 1234-5678"))
    }
}
