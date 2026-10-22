package mx.mitelcel.widget.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Telcel y Bait usan una llave PÚBLICA que viene en el JavaScript de su sitio. Si la rotan, la app
 * la lee del sitio y reintenta una vez, en lugar de quedar rota hasta la siguiente versión.
 */
class KeyRefreshTest {
    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    /** La llave que el servidor acepta "hoy" y la que anuncia el JavaScript del portal (normalmente iguales). */
    private var currentTelcelBasic = "NUEVA_BASIC"
    private var portalTelcelBasic = "NUEVA_BASIC"
    private var currentBaitKey = "0123456789abcdef0123456789abcdef"

    private val home = """{"blocks":[{"id":"tabs-plans-v2-plan-name-0-0-0","config":{"value":"Plan"}},
        {"id":"tabs-plans-v2-total-data-value-0-0-0","config":{"value":"10"}},
        {"id":"tabs-plans-v2-total-data-unit-0-0-0","config":{"value":"GB"}}]}"""
    private val bait = """{"success":true,"data":[{"category":"Data","type":"BestEffort",
        "resume":{"total":1024,"unused":512,"expire_date":"2026-11-01T00:00:00Z"}}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                paths += path
                return when (path) {
                    // --- Telcel ---
                    "/login" -> MockResponse().setBody("""{"token":"SSO"}""")
                    "/token" ->
                        if (request.getHeader("Authorization") == "Basic $currentTelcelBasic") {
                            MockResponse().setBody("""{"tokenResponse":{"tokens":[{"id":"token:b2f","access_token":"JWT"}]}}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
                        }
                    "/home" -> MockResponse().setBody(home)
                    "/revoke" -> MockResponse().setBody("{}")
                    "/portal/mitelcel/login" -> MockResponse().setBody("""<script src="/assets/index-a1b2.js"></script>""")
                    "/portal/assets/index-a1b2.js" -> MockResponse().setBody("""x={MTL_AUTHORIZATION_BASIC:"$portalTelcelBasic"}""")
                    // --- Bait ---
                    "/bait/" -> MockResponse().setBody("""<script src="main.abc123.js"></script>""")
                    "/bait/main.abc123.js" -> MockResponse().setBody("""c={xOrdService:"$currentBaitKey"}""")
                    "/bait/api/core/q/dn/5512345678/get/consumption" ->
                        if (request.getHeader("x-ord-service") == currentBaitKey) {
                            MockResponse().setBody(bait)
                        } else {
                            MockResponse().setBody("""{"success":false,"message":"Servicio no autorizado"}""")
                        }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun telcel(keys: KeyCache) = TelcelClient(
        TelcelEndpoints(
            login = server.url("/login").toString(),
            token = server.url("/token").toString(),
            home = server.url("/home").toString(),
            revoke = server.url("/revoke").toString(),
            portal = server.url("/portal").toString(),
        ),
        keys = keys,
    )

    @Test
    fun `Telcel lee la llave Basic nueva del portal, reintenta y la recuerda`() {
        val keys = InMemoryKeyCache()

        val entry = telcel(keys).fetchUsage("5512345678", "clave")

        assertEquals(10000.0, entry.totalMB, 0.001)
        assertEquals("NUEVA_BASIC", keys.get(TelcelClient.BASIC_KEY))
        assertEquals("/revoke", paths.last()) // la sesión SSO se revoca igual

        // La segunda vez ya usa la llave recordada: no vuelve a descargar el JavaScript.
        paths.clear()
        telcel(keys).fetchUsage("5512345678", "clave")
        assertTrue("/portal/mitelcel/login" !in paths)
    }

    @Test
    fun `Telcel falla una sola vez si la llave del portal tampoco sirve`() {
        portalTelcelBasic = "TAMBIEN_VIEJA" // el portal anuncia una llave que el servidor no acepta

        assertThrows(TelcelException::class.java) { telcel(InMemoryKeyCache()).fetchUsage("5512345678", "clave") }

        assertEquals(2, paths.count { it == "/token" }) // la conocida + la del portal, y se rinde: sin bucle
        assertEquals("/revoke", paths.last())
    }

    @Test
    fun `Bait lee el xOrdService nuevo de su JavaScript y reintenta`() {
        val keys = InMemoryKeyCache()

        val entry = BaitClient(baseUrl = server.url("/bait/").toString(), keys = keys).fetchUsage("5512345678", null)

        assertEquals(1024.0, entry.totalMB, 0.001)
        assertEquals(currentBaitKey, keys.get(BaitClient.SERVICE_KEY))
    }

    @Test
    fun `Bait muestra el error original si la llave del sitio es la misma`() {
        val keys = InMemoryKeyCache().apply { put(BaitClient.SERVICE_KEY, currentBaitKey) }

        // Número que Bait no conoce (404): el sitio anuncia la misma llave, así que no hay nada que reintentar.
        val e = assertThrows(TelcelException::class.java) {
            BaitClient(baseUrl = server.url("/bait/").toString(), keys = keys).fetchUsage("5500000000", null)
        }

        assertTrue(e.message!!.contains("404"))
        assertEquals(1, paths.count { it.endsWith("/get/consumption") })
    }
}
