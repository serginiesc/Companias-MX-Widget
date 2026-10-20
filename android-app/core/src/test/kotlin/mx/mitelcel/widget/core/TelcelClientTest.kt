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

/** Prueba el flujo de 4 pasos contra un servidor falso (no toca Telcel real). */
class TelcelClientTest {
    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()
    private var tokenResponse = """{"tokenResponse":{"tokens":[{"id":"token:b2f","access_token":"JWT123"}]}}"""

    private val homeJson = """
        {"blocks":[{"id":"tabs-plans-v2-plan-name-0-0-0","config":{"value":"Plan"}},
                   {"id":"tabs-plans-v2-total-data-value-0-0-0","config":{"value":"10"}},
                   {"id":"tabs-plans-v2-total-data-unit-0-0-0","config":{"value":"GB"}}]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!.substringBefore('?')
                return when (request.path!!.substringBefore('?')) {
                    "/login" -> MockResponse().setBody("""{"token":"SSO-ABC"}""")
                    "/token" -> MockResponse().setBody(tokenResponse)
                    "/home" -> MockResponse().setBody(homeJson)
                    "/revoke" -> MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = TelcelClient(
        TelcelEndpoints(
            login = server.url("/login").toString(),
            token = server.url("/token").toString(),
            home = server.url("/home").toString(),
            revoke = server.url("/revoke").toString(),
        ),
    )

    @Test
    fun `flujo completo y revoca la sesión al final`() {
        val entry = client().fetchUsage("55 1234 5678", "mi clave+1")

        assertEquals(10000.0, entry.totalMB, 0.0001)
        assertEquals(listOf("/login", "/token", "/home", "/revoke"), paths)
    }

    @Test
    fun `revoca la sesión aunque falle el paso del token`() {
        tokenResponse = """{"message":"Token inválido"}"""

        val e = assertThrows(TelcelException::class.java) { client().fetchUsage("5512345678", "x") }

        assertEquals("Token inválido", e.message)
        assertEquals("/revoke", paths.last())
    }

    @Test
    fun `codifica la contraseña en el formulario de login`() {
        client().fetchUsage("5512345678", "a+b&c")

        // Sin codificar, "&" partiría el campo en dos y "+" llegaría al servidor como espacio.
        val loginBody = server.takeRequest().body.readUtf8()
        assertTrue(loginBody, loginBody.contains("telcel-pass=a%2Bb%26c&"))
        assertTrue(loginBody, loginBody.contains("username=5512345678&"))
    }

    @Test
    fun `no llama a la red si faltan credenciales`() {
        assertThrows(TelcelException::class.java) { client().fetchUsage("123", "") }
        assertTrue(paths.isEmpty())
    }
}
