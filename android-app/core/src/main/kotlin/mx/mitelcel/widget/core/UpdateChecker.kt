package mx.mitelcel.widget.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Una versión publicada en GitHub Releases. */
data class ReleaseInfo(val version: String, val apkUrl: String, val notes: String)

/**
 * Revisa si hay una versión más nueva en GitHub Releases y descarga su APK.
 * La instalación la hace Android (siempre pide confirmación al usuario: no existe la
 * actualización silenciosa para apps fuera de la Play Store).
 */
class UpdateChecker(
    private val latestReleaseUrl: String = "https://api.github.com/repos/$REPO/releases/latest",
    private val http: OkHttpClient = TelcelClient.DEFAULT_HTTP,
) {
    /** La versión publicada si es más nueva que [currentVersion]; null si ya estás al día. Bloqueante. */
    fun findUpdate(currentVersion: String): ReleaseInfo? {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = http.newCall(request).execute().use { resp ->
            if (resp.code != 200) return null // sin Releases, sin red o límite de la API: no es un error para el usuario
            parseRelease(resp.body?.string().orEmpty())
        } ?: return null
        return release.takeIf { isNewer(it.version, currentVersion) }
    }

    /** Descarga el APK a [target]. Bloqueante. */
    fun download(release: ReleaseInfo, target: File) {
        val request = Request.Builder().url(release.apkUrl).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw TelcelException("No se pudo descargar la actualización (HTTP ${resp.code})")
            val body = resp.body ?: throw TelcelException("Descarga vacía")
            target.parentFile?.mkdirs()
            // Primero a un archivo temporal: si la descarga se corta, no queda un APK a medias con el nombre final.
            val partial = File(target.path + ".part")
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!partial.renameTo(target)) throw TelcelException("No se pudo guardar la actualización")
        }
    }

    companion object {
        const val REPO = "serginiesc/companias-mx-widget"
        const val APK_NAME = "CompaniasMxWidget.apk"

        /** Lee la respuesta de /releases/latest. null si no trae el APK esperado. */
        fun parseRelease(json: String): ReleaseInfo? {
            val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: return null
            val tag = root.str("tag_name") ?: return null
            val apk = (root["assets"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name") == APK_NAME }
                ?.str("browser_download_url")
                ?: return null
            return ReleaseInfo(tag.removePrefix("v"), apk, root.str("body").orEmpty())
        }

        /**
         * "1.10" es más nueva que "1.9". Comparar como texto diría lo contrario ("1.1" < "1.9"),
         * así que se compara número por número.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
