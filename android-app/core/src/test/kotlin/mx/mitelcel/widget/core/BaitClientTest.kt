package mx.mitelcel.widget.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Mismo fixture que tests/test_parser.mjs de amaynez/telcel-widget-agy. */
class BaitClientTest {
    private val fixture = """
        {"success":true,"data":[
          {"category":"Data","type":"BestEffort","unlimited":false,
           "resume":{"total":10240,"unused":6144,"expire_date":"2026-11-01T23:59:59.000Z"}},
          {"category":"Data","type":"Benefits","offers_detail":[
            {"total":5120,"unused":5120,"expire_date":"2026-11-01T23:59:59.000Z"},
            {"total":2048,"unused":2048,"expire_date":"2026-09-01T00:00:00.000Z"}]},
          {"category":"Data","type":"SocialMedia","unlimited":true,
           "resume":{"total":5120,"unused":4800,"expire_date":"2026-12-31T23:59:59.000Z"}},
          {"category":"Minutes","type":"National",
           "resume":{"total":100,"unused":50,"expire_date":"2026-12-15T23:59:59.000Z"}}
        ]}
    """.trimIndent()

    // 20 oct 2026, 10:09 (en UTC para que la prueba no dependa de la zona de la computadora)
    private val now = LocalDateTime.of(2026, 10, 20, 10, 9).toInstant(ZoneOffset.UTC)

    @Test
    fun `suma plan y promos vigentes e ignora redes sociales y minutos`() {
        val entry = BaitParser.parse(fixture, now, ZoneOffset.UTC)

        assertEquals("Mi Bait", entry.planName)
        assertEquals("1 de noviembre", entry.cutoffDate)
        assertEquals("1 nov", entry.shortCutoffDate)
        assertEquals(12, entry.daysLeft)
        assertEquals(listOf("BestEffort", "Benefits"), entry.buckets.map { it.id })
        assertEquals(15360.0, entry.totalMB, 0.001) // la promo vencida (2048) no cuenta
        assertEquals(4096.0, entry.consumedMB, 0.001)
        assertEquals(11264.0, entry.availableMB, 0.001)
        assertTrue(entry.hasPromo)
    }

    @Test
    fun `usa 1 GB = 1024 MB como la app de Bait`() {
        val entry = BaitParser.parse(fixture, now, ZoneOffset.UTC)

        assertEquals(1024, entry.mbPerGb)
        assertEquals(11.0, entry.remainingGB, 0.001)
        assertEquals(15.0, entry.totalGB, 0.001)
        assertEquals("4.00 GB", entry.spentShortText)
    }

    @Test
    fun `error de Bait muestra su mensaje`() {
        val e = assertThrows(TelcelException::class.java) {
            BaitParser.parse("""{"success":false,"message":"No se encontró"}""", now)
        }
        assertEquals("No se encontró", e.message)
    }

    @Test
    fun `consulta por número con la llave del sitio y sin contraseña`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(fixture))
        server.start()
        try {
            val entry = BaitClient(baseUrl = server.url("/").toString()).fetchUsage("+52 55 1234 5678", password = null)

            val request = server.takeRequest()
            assertEquals("/api/core/q/dn/5512345678/get/consumption", request.path)
            assertTrue(request.getHeader("x-ord-service")!!.isNotBlank())
            assertEquals(15360.0, entry.totalMB, 0.001)
        } finally {
            server.shutdown()
        }
    }
}
