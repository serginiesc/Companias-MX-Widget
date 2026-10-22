package mx.mitelcel.widget.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mi Movistar. El login tiene reCAPTCHA v3, así que NO se puede hacer desde código: el usuario
 * inicia sesión en la app (WebView) y guardamos su token de sesión `p_id`. Con ese token esta
 * clase consulta el consumo (también en segundo plano) y descifra la respuesta.
 * Port de `src/MiMovistarWidget.js` de amaynez/telcel-widget-agy.
 */
class MovistarClient(
    private val baseUrl: String = "https://mi.movistar.com.mx",
    private val http: OkHttpClient = TelcelClient.DEFAULT_HTTP,
    private val keys: KeyCache = InMemoryKeyCache(),
) : UsageProvider {

    /** @param password el token de sesión `p_id` capturado al iniciar sesión. */
    override fun fetchUsage(rawPhone: String, password: String?): UsageEntry {
        val token = password?.trim().orEmpty()
        if (token.isEmpty()) throw SessionExpiredException("Inicia sesión en Movistar desde la app")
        return MovistarParser.parse(decrypt(fetchContent(token)))
    }

    private fun fetchContent(token: String): String {
        val request = Request.Builder()
            .url("$baseUrl/consumptions-controller/datos-iniciales")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", token) // el p_id va tal cual, sin "Bearer"
            .build()
        http.newCall(request).execute().use { resp ->
            val content = runCatching {
                (Json.parseToJsonElement(resp.body?.string().orEmpty()) as JsonObject).str("content")
            }.getOrNull()
            // Sin "content" válido = el token ya no sirve. Reintentar no ayuda: hay que volver a iniciar sesión.
            if (content == null || ':' !in content) {
                throw SessionExpiredException("Tu sesión de Movistar expiró: abre la app e inicia sesión de nuevo")
            }
            return content
        }
    }

    /**
     * El contenido es "<cifrado>:<iv>". La llave (guardString) viene en la página de login y Movistar
     * puede rotarla: si la guardada ya no descifra, se lee la actual de la página y se reintenta una vez.
     */
    private fun decrypt(content: String): String {
        val (cipher, iv) = content.split(':').takeIf { it.size == 2 && it.all(String::isNotEmpty) }
            ?.let { it[0] to it[1] }
            ?: throw TelcelException("Respuesta cifrada de Movistar inválida")

        val key = keys.get(GUARD_KEY) ?: currentGuardKey()
        return try {
            MovistarCrypto.decrypt(cipher, iv, key)
        } catch (e: GeneralSecurityException) {
            val fresh = currentGuardKey()
            if (fresh == key) throw TelcelException("No se pudo descifrar la respuesta de Movistar", e)
            MovistarCrypto.decrypt(cipher, iv, fresh)
        }
    }

    /** Llave vigente según la página de login; si no se puede leer, la conocida (respaldo). */
    private fun currentGuardKey(): String {
        val fromPage = runCatching {
            http.newCall(Request.Builder().url("$baseUrl/login").build()).execute().use { extractGuardKey(it.body?.string().orEmpty()) }
        }.getOrNull()
        val key = fromPage ?: FALLBACK_GUARD_KEY
        keys.put(GUARD_KEY, key)
        return key
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val GUARD_KEY = "movistar_guard_key"

        /** Llave pública del sitio mi.movistar.com.mx (viene en su HTML); respaldo si no se puede leer. */
        internal const val FALLBACK_GUARD_KEY = "03818954072261267933921453028068"

        private val GUARD_STRING = Regex("""guardString\s*=\s*["']([^"']+)["']""")

        /** Busca `guardString = "<base64>"` en el HTML. null si no está o no es una llave de 32 caracteres. */
        fun extractGuardKey(html: String): String? = GUARD_STRING.find(html)?.groupValues?.get(1)?.let(::decodeGuardString)

        /** El guardString es Base64 de una llave de 32 caracteres (AES-256). */
        fun decodeGuardString(base64: String): String? =
            runCatching { String(Base64.getDecoder().decode(base64.trim()), Charsets.UTF_8) }.getOrNull()?.takeIf { it.length == 32 }
    }
}

/** AES-256-GCM como lo usa el sitio de Movistar (WebCrypto): la etiqueta de 128 bits va al final del cifrado. */
object MovistarCrypto {
    fun decrypt(cipherBase64Url: String, ivBase64Url: String, guardKey: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // La llave son los 32 caracteres tal cual (cada carácter = 1 byte), igual que charCodeAt en JS.
        val key = SecretKeySpec(guardKey.toByteArray(Charsets.ISO_8859_1), "AES")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, base64Url(ivBase64Url)))
        return String(cipher.doFinal(base64Url(cipherBase64Url)), Charsets.UTF_8)
    }

    /** Acepta Base64 normal o "URL-safe", con o sin "=" al final (el sitio usa URL-safe sin relleno). */
    internal fun base64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').trimEnd('='))
}

/**
 * Interpreta el JSON descifrado de /consumptions-controller/datos-iniciales.
 * Suma las bolsas de navegación de `bolton[]` e ignora Redes Sociales, Minutos y SMS.
 */
object MovistarParser {
    private const val MOVISTAR_MB_PER_GB = 1024
    private val IGNORED = Regex("""^(redes sociales|minutos|sms|voz|mensajes)\b""", RegexOption.IGNORE_CASE)
    private val UNLIMITED = Regex("ilimitado", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""(\d+(?:\.\d+)?)\s*(KB|MB|GB|TB)""", RegexOption.IGNORE_CASE)
    private val DMY = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun parse(json: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): UsageEntry {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull()
            ?: throw TelcelException("Respuesta de Movistar no es JSON válido")
        val boltons = (root["bolton"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (boltons.isEmpty()) throw TelcelException("Sin bolsas de datos activas en la línea Movistar")

        val buckets = mutableListOf<BucketUsage>()
        var latest: LocalDate? = null
        var unlimited = false
        fun track(date: LocalDate?) {
            if (date != null && (latest == null || date > latest)) latest = date
        }

        for (item in boltons) {
            val name = item.str("name").orEmpty().trim()
            if (IGNORED.containsMatchIn(name)) continue

            val label = item.str("consumptionLabel").orEmpty().trim()
            val benefit = item.str("totalBenefit").orEmpty().trim()
            if (UNLIMITED.containsMatchIn(label) || UNLIMITED.containsMatchIn(benefit)) {
                unlimited = true
                track(parseDate(item["vigencia"], zone))
                continue
            }

            // "Llevas 6.50 GB de 10.00 GB" -> usado y total. Si no viene así, total + porcentaje.
            val pair = label.replace(
                Regex("""^llevas\s+""", RegexOption.IGNORE_CASE),
                "",
            ).split(Regex("""\s+de\s+""", RegexOption.IGNORE_CASE))
            val (used, total) = if (pair.size == 2) {
                toMB(pair[0]) to toMB(pair[1])
            } else {
                val t = toMB(benefit.ifEmpty { label })
                val pct = (item["consumption"] as? JsonPrimitive)?.doubleOrNull
                (if (t > 0 && pct != null) t * pct.coerceIn(0.0, 100.0) / 100 else 0.0) to t
            }
            val available = maxOf(0.0, total - used)
            if (total > 0 || available > 0) {
                track(parseDate(item["vigencia"], zone))
                buckets += BucketUsage(name.ifEmpty { "bolton-${buckets.size}" }, name.ifEmpty { "Navegación" }, used, total, available)
            }
        }

        // Prepago: si ninguna bolsa trae vigencia, se usa la del saldo.
        val saldos = root["saldos"] as? JsonObject
        if (latest == null) track(parseDate(saldos?.get("topupsBalanceExpiration"), zone))
        if (latest == null) track(parseDate(saldos?.get("topupsBalanceExpirationDate"), zone))

        if (buckets.isEmpty() && !unlimited) throw TelcelException("Sin bolsas de navegación activas en la línea Movistar")

        val today = now.atZone(zone).toLocalDate()
        val expire = latest
        val offering = root.str("offering").orEmpty().trim()
        return UsageEntry(
            timestampMillis = now.toEpochMilli(),
            consumedMB = buckets.sumOf { it.consumedMB },
            totalMB = buckets.sumOf { it.totalMB },
            availableMB = buckets.sumOf { it.availableMB },
            daysLeft = expire?.let { maxOf(0, ChronoUnit.DAYS.between(today, it).toInt()) } ?: 0,
            cutoffDate = expire?.let { "${it.dayOfMonth} de ${MONTHS[it.monthValue - 1]}" } ?: "",
            planName = offering.ifEmpty { if (unlimited) "Movistar Ilimitado" else "Mi Movistar" },
            buckets = buckets,
            updatedAt = TIME_FORMAT.format(now.atZone(zone)),
            mbPerGb = MOVISTAR_MB_PER_GB,
            unlimited = unlimited && buckets.isEmpty(),
        )
    }

    /** "6.50 GB" -> 6656.0 MB (Movistar cuenta 1 GB = 1024 MB). */
    internal fun toMB(text: String): Double {
        val m = AMOUNT.find(text.replace(",", "")) ?: return 0.0
        val value = m.groupValues[1].toDouble()
        return when (m.groupValues[2].uppercase()) {
            "TB" -> value * 1024 * 1024
            "GB" -> value * 1024
            "KB" -> value / 1024
            else -> value
        }
    }

    /** Acepta "28/10/2026", "2026-10-28", "2026-10-28T…" o milisegundos (número o texto de 11–13 dígitos). */
    internal fun parseDate(raw: kotlinx.serialization.json.JsonElement?, zone: ZoneId): LocalDate? {
        val p = raw as? JsonPrimitive ?: return null
        val epoch = p.longOrNull?.takeIf { !p.isString || p.content.length in 11..13 }
        if (epoch != null) return Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
        val s = p.content.trim()
        DMY.matchEntire(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
        return runCatching { LocalDate.parse(s.substringBefore('T')) }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
