package mx.mitelcel.widget.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Mi Bait (red Altán, plataforma Ordenaris). Una sola consulta GET por número: no hay login.
 * Port de `src/MiBaitWidget.js` de amaynez/telcel-widget-agy.
 */
class BaitClient(
    private val baseUrl: String = "https://mibait.com",
    private val http: OkHttpClient = TelcelClient.DEFAULT_HTTP,
    private val keys: KeyCache = InMemoryKeyCache(),
) : UsageProvider {

    override fun fetchUsage(rawPhone: String, password: String?): UsageEntry {
        val phone = TelcelClient.normalizePhone(rawPhone)
        if (phone.length != 10) throw TelcelException("Configura un número Bait a 10 dígitos")

        val known = keys.get(SERVICE_KEY) ?: ORD_SERVICE_KEY
        return try {
            query(phone, known)
        } catch (e: TelcelException) {
            // ¿Bait rotó su llave? Se lee la actual de su JavaScript y se reintenta UNA vez.
            val fresh = runCatching { fetchServiceKey() }.getOrNull()
            if (fresh == null || fresh == known) throw e
            query(phone, fresh).also { keys.put(SERVICE_KEY, fresh) }
        }
    }

    private fun query(phone: String, serviceKey: String): UsageEntry {
        // addPathSegment codifica el número; armar la URL concatenando texto es un error común.
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/core/q/dn")
            .addPathSegment(phone)
            .addPathSegments("get/consumption")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("x-ord-service", serviceKey)
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code != 200 && body.isBlank()) throw TelcelException("Bait respondió HTTP ${resp.code}")
            return BaitParser.parse(body)
        }
    }

    /** mibait.com -> main.<hash>.js -> xOrdService:"<32 hex>" */
    private fun fetchServiceKey(): String? {
        val html = getText(baseUrl) ?: return null
        val script = Regex("""src="(main\.[a-f0-9]+\.js)"""").find(html)?.groupValues?.get(1) ?: return null
        val js = getText("${baseUrl.trimEnd('/')}/$script") ?: return null
        return Regex("""xOrdService:"([a-f0-9]{32})"""").find(js)?.groupValues?.get(1)
    }

    private fun getText(url: String): String? = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (it.isSuccessful) it.body?.string() else null
    }

    companion object {
        const val SERVICE_KEY = "bait_service_key"

        /** Llave pública del sitio mibait.com (viene en su JavaScript); no es tuya ni da acceso a tu cuenta. */
        private const val ORD_SERVICE_KEY = "57bfb26735aa4ec3a1baafa864006d7d"
    }
}

/**
 * Suma las bolsas de datos de Bait:
 * - BestEffort (velocidad normal) y Throttling (velocidad reducida) = plan base.
 * - Benefits = promociones; solo cuentan las que no han vencido.
 * La vigencia es la fecha de vencimiento más lejana de esas bolsas.
 */
object BaitParser {
    private val MONTHS_ES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Bait reporta en MB "binarios": 1 GB = 1024 MB, igual que su app. */
    private const val BAIT_MB_PER_GB = 1024

    fun parse(json: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): UsageEntry {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull()
            ?: throw TelcelException("Respuesta de Bait no es JSON válido")
        val data = root["data"] as? JsonArray
        if (root.bool("success") != true || data == null) {
            throw TelcelException(root.str("message") ?: "Respuesta de Mi Bait sin datos de consumo")
        }

        val today = now.atZone(zone).toLocalDate()
        val buckets = mutableListOf<BucketUsage>()
        var latestExpire: LocalDate? = null
        fun track(date: LocalDate?) {
            if (date != null && (latestExpire == null || date > latestExpire)) latestExpire = date
        }

        for (item in data.mapNotNull { it as? JsonObject }) {
            when (val type = item.str("type")) {
                "BestEffort", "Throttling" -> {
                    val resume = item["resume"] as? JsonObject
                    val total = resume.num("total")
                    val available = resume.num("unused")
                    if (total > 0 || available > 0) {
                        track(parseDate(resume.str("expire_date")))
                        buckets += BucketUsage(
                            id = type,
                            name = if (type == "BestEffort") "Velocidad normal" else "Velocidad reducida",
                            consumedMB = maxOf(0.0, total - available),
                            totalMB = total,
                            availableMB = available,
                        )
                    }
                }
                "Benefits" -> {
                    val offers = (item["offers_detail"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    var total = 0.0
                    var available = 0.0
                    for (offer in offers) {
                        val expire = parseDate(offer.str("expire_date"))
                        if (expire == null || expire >= today) {
                            total += offer.num("total")
                            available += offer.num("unused")
                            track(expire)
                        }
                    }
                    if (total > 0 || available > 0) {
                        buckets += BucketUsage("Benefits", "Beneficios", maxOf(0.0, total - available), total, available)
                    }
                }
            }
        }
        if (buckets.isEmpty()) throw TelcelException("Sin bolsas de datos activas en la línea Bait")

        val expire = latestExpire
        val unlimited = data.mapNotNull { it as? JsonObject }.any {
            it.str("category") == "Data" && it.str("type") !in setOf("Unkown", "SocialMedia") && it.bool("unlimited") == true
        }
        return UsageEntry(
            timestampMillis = now.toEpochMilli(),
            consumedMB = buckets.sumOf { it.consumedMB },
            totalMB = buckets.sumOf { it.totalMB },
            availableMB = buckets.sumOf { it.availableMB },
            daysLeft = expire?.let { maxOf(0, ChronoUnit.DAYS.between(today, it).toInt()) } ?: 0,
            cutoffDate = expire?.let { "${it.dayOfMonth} de ${MONTHS_ES[it.monthValue - 1]}" } ?: "",
            planName = if (unlimited) "Bait Ilimitado" else "Mi Bait",
            buckets = buckets,
            updatedAt = TIME_FORMAT.format(now.atZone(zone)),
            mbPerGb = BAIT_MB_PER_GB,
        )
    }

    /** "2026-11-01T23:59:59.000Z" -> 2026-11-01 (solo la fecha, como la app de Bait). */
    private fun parseDate(iso: String?): LocalDate? = iso?.substringBefore('T')?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.num(key: String): Double {
        val p = this?.get(key) as? JsonPrimitive ?: return 0.0
        return p.doubleOrNull ?: p.content.toDoubleOrNull() ?: 0.0
    }
}
