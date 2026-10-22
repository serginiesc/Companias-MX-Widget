package mx.mitelcel.widget.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** URLs de los 4 servicios. Se inyectan para poder probar el cliente con un servidor falso. */
data class TelcelEndpoints(
    val login: String = "https://wbl.telcel-id.com:8443/login",
    val token: String = "https://mi-telcel.smapps.mx/security/1.0.0/v2/token",
    val home: String = "https://api.journeys.telcel.wavebybemobi.com/api/components/flow/home",
    val revoke: String = "https://dev.smapps.mx:9000/sso/revoke",
    /** Portal web: de su JavaScript se lee la llave Basic vigente si Telcel la rota. */
    val portal: String = "https://www.mitelcel.com",
)

/**
 * Flujo de 4 pasos: WebLogin SSO -> JWT B2F -> Wave B2F Home -> Revocar SSO.
 *
 * IMPORTANTE: [fetchUsage] es bloqueante (hace red). Llámalo solo desde un hilo de fondo
 * (Worker o Dispatchers.IO); en el hilo principal Android lanza NetworkOnMainThreadException.
 */
class TelcelClient(
    private val endpoints: TelcelEndpoints = TelcelEndpoints(),
    private val baseHttp: OkHttpClient = DEFAULT_HTTP,
    private val keys: KeyCache = InMemoryKeyCache(),
) : UsageProvider {
    override fun fetchUsage(rawPhone: String, password: String?): UsageEntry {
        val phone = normalizePhone(rawPhone)
        if (phone.length != 10 || password.isNullOrEmpty()) {
            throw TelcelException("Configura un número a 10 dígitos y tu contraseña")
        }

        // Un cliente por consulta con cookies solo en memoria (equivale a la sesión "ephemeral" de iOS).
        val http = baseHttp.newBuilder().cookieJar(InMemoryCookieJar()).build()
        val ssoToken = login(http, phone, password)
        try {
            val b2fToken = exchangeTokenWithFreshKey(http, phone, ssoToken)
            return HomeFlowParser.parse(fetchHome(http, phone, b2fToken))
        } finally {
            // Siempre revocar: Telcel bloquea la cuenta si se acumulan sesiones SSO abiertas.
            runCatching { revoke(http, ssoToken) }
        }
    }

    private fun login(http: OkHttpClient, phone: String, password: String): String {
        val passB64 = Base64.getEncoder().encodeToString(password.replace(Regex("""\s+"""), "").toByteArray())
        val body = listOf(
            "username=${enc(phone)}",
            "telcel-pass=${enc(password)}",
            "sitedomain=https://www.mitelcel.com",
            "CCode=52",
            "Type=MSISDN",
            "Invocation=iFrame",
            "MSISDN=${enc(phone)}",
            "Source=",
            "pB64=${enc(passB64)}",
            "url=https://wbl.telcel-id.com:8443",
        ).joinToString("&")

        val request = Request.Builder()
            .url(endpoints.login)
            .header("Origin", "https://wbl.telcel-id.com:8443")
            .header(
                "Referer",
                "https://wbl.telcel-id.com:8443/login?sitedomain=https://www.mitelcel.com&CCode=52&Type=MSISDN&Invocation=iFrame",
            )
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", MOBILE_UA)
            .post(body.toRequestBody(FORM_UTF8))
            .build()

        val json = executeJson(http, request, "login")
        return json.str("token")
            ?: throw TelcelException((json["error"] as? JsonObject).str("description") ?: "Número o contraseña incorrectos")
    }

    /**
     * Intenta con la llave Basic conocida; si Telcel la rechaza, lee la actual de su JavaScript y
     * reintenta UNA vez. Así el JavaScript (pesado) solo se descarga cuando de verdad cambió la llave.
     */
    private fun exchangeTokenWithFreshKey(http: OkHttpClient, phone: String, ssoToken: String): String {
        val known = keys.get(BASIC_KEY) ?: MTL_BASIC
        return try {
            exchangeToken(http, phone, ssoToken, known)
        } catch (e: TelcelException) {
            val fresh = runCatching { fetchBasicFromPortal(http) }.getOrNull()
            if (fresh == null || fresh == known) throw e
            exchangeToken(http, phone, ssoToken, fresh).also { keys.put(BASIC_KEY, fresh) }
        }
    }

    /** Portal -> /assets/index-*.js -> MTL_AUTHORIZATION_BASIC:"…" */
    private fun fetchBasicFromPortal(http: OkHttpClient): String? {
        val html = getText(http, "${endpoints.portal}/mitelcel/login") ?: return null
        val script = Regex("""src="(/assets/index-[^"]+\.js)"""").find(html)?.groupValues?.get(1) ?: return null
        val js = getText(http, endpoints.portal + script) ?: return null
        return Regex("""MTL_AUTHORIZATION_BASIC\s*:\s*"([^"]+)"""").find(js)?.groupValues?.get(1)
    }

    private fun getText(http: OkHttpClient, url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", MOBILE_UA).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }

    private fun exchangeToken(http: OkHttpClient, phone: String, ssoToken: String, basic: String): String {
        val url = endpoints.token.toHttpUrl().newBuilder()
            .addQueryParameter("user", phone)
            .addQueryParameter("token", ssoToken)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .header("scope", "token:mit|token:b2f")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Version", "0.14")
            .header("Code-Version", "38")
            .header("Device-Id", "DSK")
            .header("tipoRequest", "1")
            .header("Os", "WEB")
            .header("Os-Version", "13")
            .header("X-Channel", "web-browser")
            .header("User-Agent", "ktor-client")
            .build()

        val json = executeJson(http, request, "token")
        val tokens = (json["tokenResponse"] as? JsonObject)?.get("tokens") as? JsonArray
        return tokens
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("id") == "token:b2f" }
            ?.str("access_token")
            ?: throw TelcelException(json.str("message") ?: "No se obtuvo token:b2f")
    }

    private fun fetchHome(http: OkHttpClient, phone: String, b2fToken: String): String {
        val url: HttpUrl = endpoints.home.toHttpUrl().newBuilder()
            .addQueryParameter("externalCode", "52$phone")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $b2fToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Is-iPad", "false")
            .header("x-wave-sdk", "WEB")
            .header("x-wave-device", "iPhone")
            .header("x-wave-device-type", "mobile")
            .header("x-wave-os", "iOS-web")
            .header("x-wave-os-version", "18.0")
            .header("User-Agent", MOBILE_UA)
            .build()

        http.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw TelcelException("Telcel respondió HTTP ${resp.code} al consultar consumo")
            return resp.body?.string() ?: throw TelcelException("Respuesta vacía de Telcel")
        }
    }

    private fun revoke(http: OkHttpClient, ssoToken: String) {
        val request = Request.Builder()
            .url(endpoints.revoke)
            .post("token=${enc(ssoToken)}&token_type=revoke".toRequestBody(FORM))
            .build()
        http.newCall(request).execute().close()
    }

    private fun executeJson(http: OkHttpClient, request: Request, step: String): JsonObject = http.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: throw TelcelException("Respuesta inesperada en paso '$step' (HTTP ${resp.code})")
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private class InMemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain } }
            this.cookies += cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        /** Credencial pública de la app web Mi Telcel (no es tuya; viene embebida en su JS). */
        private const val MTL_BASIC = "bXRwc2VydmljZXM6NyQ4N3JmRnN3ZSQyMV8="
        const val BASIC_KEY = "telcel_basic"
        private const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
        private val FORM_UTF8 = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        internal val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** "+52 (55) 1234-5678" -> "5512345678" */
        fun normalizePhone(raw: String): String = raw.filter(Char::isDigit).takeLast(10)

        private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
