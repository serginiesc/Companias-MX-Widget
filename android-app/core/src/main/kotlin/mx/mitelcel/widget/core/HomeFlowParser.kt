package mx.mitelcel.widget.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class TelcelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Convierte la respuesta de `api/components/flow/home` (Wave B2F) en un [UsageEntry].
 *
 * La respuesta es un árbol de componentes de UI; cada componente tiene `id` y `config`.
 * Recorremos todo el árbol, indexamos los `config` por `id` y luego buscamos por nombre.
 * Port directo de `parseHomeFlowJSON` (Swift) y `parseTelcelResponse` (JS).
 */
object HomeFlowParser {
    private const val PLAN_NAME_PREFIX = "tabs-plans-v2-plan-name-"
    private const val DEFAULT_DAYS_LEFT = 28
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun parse(json: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): UsageEntry {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw TelcelException("Respuesta de Telcel no es JSON válido", e)
        }

        val byId = mutableMapOf<String, JsonObject>()
        val suffixes = linkedSetOf<String>()
        indexComponents(root, byId, suffixes)

        val orderedSuffixes = suffixes.sortedWith(SuffixComparator).ifEmpty { listOf("0-0-0") }
        val buckets = orderedSuffixes.mapNotNull { parseBucket(it, byId) }
        if (buckets.isEmpty()) throw TelcelException("Respuesta sin bolsas de datos")

        // Los días restantes vienen como texto ("Faltan 9 días") en la primera bolsa que lo tenga.
        val daysLeft = buckets.firstNotNullOfOrNull { daysFromCutoffMessage(byId["tabs-plans-v2-limited-cutoff-message-${it.id}"]) }
            ?: DEFAULT_DAYS_LEFT

        return UsageEntry(
            timestampMillis = now.toEpochMilli(),
            consumedMB = buckets.sumOf { it.consumedMB },
            totalMB = buckets.sumOf { it.totalMB },
            availableMB = buckets.sumOf { it.availableMB },
            daysLeft = daysLeft,
            cutoffDate = byId["plan-details-row-template-col-2-0-0"].string("value")
                ?: byId["plan-details-row-template-col-2-web-0-0"].string("value")
                ?: "",
            planName = byId["text-plan-name"].string("value")
                ?: byId["plan-details-name"].string("value")
                ?: "Mi Telcel",
            buckets = buckets,
            updatedAt = TIME_FORMAT.format(now.atZone(zone)),
        )
    }

    private fun indexComponents(node: JsonElement, byId: MutableMap<String, JsonObject>, suffixes: MutableSet<String>) {
        when (node) {
            is JsonObject -> {
                val id = (node["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val config = node["config"] as? JsonObject
                if (id != null && config != null) {
                    byId[id] = config
                    if (id.startsWith(PLAN_NAME_PREFIX)) {
                        val sfx = id.removePrefix(PLAN_NAME_PREFIX)
                        if (sfx.isNotEmpty() && sfx.all { it.isDigit() || it == '-' }) suffixes += sfx
                    }
                }
                node.values.forEach { indexComponents(it, byId, suffixes) }
            }
            is JsonArray -> node.forEach { indexComponents(it, byId, suffixes) }
            else -> Unit
        }
    }

    /** Devuelve null si la bolsa está vacía (total y disponible en 0), para ignorarla. */
    private fun parseBucket(sfx: String, byId: Map<String, JsonObject>): BucketUsage? {
        fun cfg(name: String) = byId["tabs-plans-v2-$name-$sfx"]

        // Respaldo: la gráfica trae usado/disponible en KB cuando no vienen los textos.
        val chart = cfg("usage-chart")?.get("data") as? JsonArray
        val chartUsedKB = chartValue(chart, "used")
        val chartAvailKB = chartValue(chart, "available")

        val used = toMB(cfg("used-data-value"), cfg("used-data-unit"))
            ?: chartUsedKB?.let { it / 1024.0 }
            ?: 0.0
        val total = toMB(cfg("total-data-value"), cfg("total-data-unit"))
            ?: if (chartUsedKB != null && chartAvailKB != null) (chartUsedKB + chartAvailKB) / 1024.0 else 0.0
        val available = toMB(cfg("available-data-value"), cfg("available-data-unit"))
            ?: max(0.0, total - used)

        if (total <= 0 && available <= 0) return null
        return BucketUsage(
            id = sfx,
            name = cfg("plan-name").string("value") ?: sfx,
            consumedMB = used,
            totalMB = total,
            availableMB = available,
        )
    }

    private fun chartValue(chart: JsonArray?, key: String): Double? = chart?.firstOrNull { (it as? JsonObject).string("key") == key }
        ?.let { (it as JsonObject)["value"].asDouble() }

    private fun toMB(valueCfg: JsonObject?, unitCfg: JsonObject?): Double? {
        val n = valueCfg?.get("value").asDouble() ?: return null
        return when (unitCfg.string("value")?.uppercase() ?: "MB") {
            "GB" -> n * 1000.0
            "KB" -> n / 1024.0
            else -> n
        }
    }

    private fun daysFromCutoffMessage(cfg: JsonObject?): Int? {
        val segments = cfg?.get("segments") as? JsonArray ?: return null
        val text = segments.joinToString("") { (it as? JsonObject).string("text") ?: "" }
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Acepta números (5) o textos numéricos ("5"), como el parser de iOS. */
    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

    /** Orden "natural": 0-1-0 antes que 0-10-0 (un orden de texto simple los invertiría). */
    private object SuffixComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val pa = a.split('-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until minOf(pa.size, pb.size)) {
                if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
