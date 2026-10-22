package mx.mitelcel.widget.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Mismo fixture que tests/test_parser.mjs de amaynez/telcel-widget-agy. */
class MovistarClientTest {
    private val fixture = """
        {"offering":"Movistar Libre","userType":2,"bolton":[
          {"name":"Navegación","vigencia":"28/10/2026","consumption":65,
           "consumptionLabel":"Llevas 6.50 GB de 10.00 GB","totalBenefit":"10.00 GB"},
          {"name":"Bono Datos Extra","vigencia":"28/10/2026","consumption":50,
           "consumptionLabel":"Llevas 1.00 GB de 2.00 GB","totalBenefit":"2.00 GB"},
          {"name":"Redes Sociales","vigencia":"28/10/2026","consumption":0,
           "consumptionLabel":"Ilimitado","totalBenefit":"Ilimitado"},
          {"name":"Minutos","totalBenefit":"Ilimitado"}
        ],"saldos":{"totalBalance":"0.00","topupsBalanceExpiration":"28/10/2026"}}
    """.trimIndent()

    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).toInstant(ZoneOffset.UTC)
    private val staticKey = MovistarClient.FALLBACK_GUARD_KEY
    private val rotatedKey = "88888888888888888888888888888888"

    /** Cifra como lo hace el sitio de Movistar: "<base64url(cifrado+etiqueta)>:<base64url(iv)>". */
    private fun encrypt(plain: String, key: String): String {
        val iv = ByteArray(12) { 42 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "AES"), GCMParameterSpec(128, iv))
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return "${b64.encodeToString(cipher.doFinal(plain.toByteArray()))}:${b64.encodeToString(iv)}"
    }

    // ---------------------------------------------------------------- parser

    @Test
    fun `suma las bolsas de navegación e ignora redes sociales y minutos`() {
        val entry = MovistarParser.parse(fixture, now, ZoneOffset.UTC)

        assertEquals("Movistar Libre", entry.planName)
        assertEquals("28 de octubre", entry.cutoffDate)
        assertEquals(7, entry.daysLeft)
        assertEquals(listOf("Navegación", "Bono Datos Extra"), entry.buckets.map { it.id })
        assertEquals(12288.0, entry.totalMB, 0.001)
        assertEquals(7680.0, entry.consumedMB, 0.001)
        assertEquals(4608.0, entry.availableMB, 0.001)
        assertEquals(1024, entry.mbPerGb)
        assertEquals("4.50 GB", entry.remainingText)
        assertFalse(entry.unlimited)
    }

    @Test
    fun `plan ilimitado no tiene total pero sí vigencia`() {
        val json = """{"offering":"Movistar Ilimitado","bolton":[
            {"name":"Navegación","consumptionLabel":"Ilimitado","totalBenefit":"Ilimitado","vigencia":"28/10/2026"}]}"""

        val entry = MovistarParser.parse(json, now, ZoneOffset.UTC)

        assertTrue(entry.unlimited)
        assertTrue(entry.hasPlan)
        assertEquals("Ilimitado", entry.remainingText)
        assertEquals(7, entry.daysLeft)
        assertFalse(LowData.isLow(entry)) // ilimitado nunca está "bajo"
    }

    @Test
    fun `usa el porcentaje cuando no viene el texto llevas X de Y y la vigencia del saldo`() {
        // 1792929600000 ms = 25 oct 2026 12:00 UTC: prepago sin vigencia en la bolsa.
        val json = """{"bolton":[{"name":"Navegación","totalBenefit":"4 GB","consumption":25}],
            "saldos":{"topupsBalanceExpirationDate":1792929600000}}"""

        val entry = MovistarParser.parse(json, now, ZoneOffset.UTC)

        assertEquals(4096.0, entry.totalMB, 0.001)
        assertEquals(1024.0, entry.consumedMB, 0.001)
        assertEquals("Mi Movistar", entry.planName)
        assertEquals("25 de octubre", entry.cutoffDate)
    }

    @Test
    fun `sin bolsas de navegación es un error`() {
        assertThrows(TelcelException::class.java) { MovistarParser.parse("""{"bolton":[]}""", now) }
        assertThrows(TelcelException::class.java) {
            MovistarParser.parse("""{"bolton":[{"name":"Redes Sociales Ilimitadas","consumptionLabel":"Ilimitado"}]}""", now)
        }
    }

    // ----------------------------------------------------------------- llave

    @Test
    fun `lee la llave guardString del HTML de login`() {
        val b64 = Base64.getEncoder().encodeToString(staticKey.toByteArray())
        assertEquals(staticKey, MovistarClient.extractGuardKey("<script>\nconst guardString = '$b64';\n</script>"))
        assertNull(MovistarClient.extractGuardKey("<html>sin llave</html>"))
        assertNull(MovistarClient.decodeGuardString(Base64.getEncoder().encodeToString("corta".toByteArray())))
    }

    @Test
    fun `descifra AES-256-GCM como el sitio`() {
        val (cipher, iv) = encrypt(fixture, staticKey).split(':')
        assertEquals(fixture, MovistarCrypto.decrypt(cipher, iv, staticKey))
    }

    // --------------------------------------------------------------- cliente

    private lateinit var server: MockWebServer
    private var pageKey: String? = staticKey
    private var datos = "{}"
    private val authHeaders = mutableListOf<String?>()
    private var loginPageHits = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/login" -> {
                    loginPageHits++
                    val key = pageKey ?: return MockResponse().setResponseCode(503)
                    val b64 = Base64.getEncoder().encodeToString(key.toByteArray())
                    MockResponse().setBody("""<script>const guardString = "$b64";</script>""")
                }
                "/consumptions-controller/datos-iniciales" -> {
                    authHeaders += request.getHeader("Authorization")
                    MockResponse().setBody(datos)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(keys: KeyCache = InMemoryKeyCache()) = MovistarClient(baseUrl = server.url("/").toString().trimEnd('/'), keys = keys)

    @Test
    fun `consulta con el token tal cual y descifra`() {
        datos = """{"content":"${encrypt(fixture, staticKey)}"}"""
        val keys = InMemoryKeyCache().apply { put(MovistarClient.GUARD_KEY, staticKey) }

        val entry = client(keys).fetchUsage("", "token-p-id")

        assertEquals(12288.0, entry.totalMB, 0.001)
        assertEquals(listOf<String?>("token-p-id"), authHeaders) // sin "Bearer"
        assertEquals(0, loginPageHits) // con la llave guardada no hace falta leer la página
    }

    @Test
    fun `si Movistar rotó la llave la lee de la página y reintenta`() {
        datos = """{"content":"${encrypt(fixture, rotatedKey)}"}"""
        pageKey = rotatedKey
        val keys = InMemoryKeyCache().apply { put(MovistarClient.GUARD_KEY, staticKey) }

        val entry = client(keys).fetchUsage("", "token")

        assertEquals(12288.0, entry.totalMB, 0.001)
        assertEquals(rotatedKey, keys.get(MovistarClient.GUARD_KEY))
        assertEquals(1, loginPageHits)
    }

    @Test
    fun `sin página usa la llave de respaldo`() {
        datos = """{"content":"${encrypt(fixture, staticKey)}"}"""
        pageKey = null

        assertEquals(12288.0, client().fetchUsage("", "token").totalMB, 0.001)
    }

    @Test
    fun `sesión expirada o sin token pide iniciar sesión de nuevo`() {
        datos = """{"response":"401"}"""
        assertThrows(SessionExpiredException::class.java) { client().fetchUsage("", "token-viejo") }
        assertThrows(SessionExpiredException::class.java) { client().fetchUsage("", "") }
    }
}
