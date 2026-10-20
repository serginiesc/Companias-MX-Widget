package mx.mitelcel.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import mx.mitelcel.widget.core.ReleaseInfo
import mx.mitelcel.widget.core.UpdateChecker
import java.io.File

/**
 * Actualizaciones "OTA" desde GitHub Releases: revisa, descarga y abre el instalador de Android.
 * Android siempre muestra "¿Actualizar?" al usuario; una app fuera de la Play Store no puede
 * actualizarse sola en silencio (es una protección del sistema, no una limitación nuestra).
 */
class UpdateManager(
    private val context: Context,
    private val checker: UpdateChecker = UpdateChecker(),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val apkFile = File(context.cacheDir, "updates/${UpdateChecker.APK_NAME}")

    /**
     * Bloqueante. Consulta GitHub como máximo cada [CHECK_INTERVAL_MS] (la API sin cuenta permite
     * 60 consultas por hora); entre consultas devuelve lo último que encontró.
     */
    fun check(): ReleaseInfo? {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return pendingRelease()

        val found = runCatching { checker.findUpdate(currentVersion) }.getOrNull()
        prefs.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putString(KEY_VERSION, found?.version)
            .putString(KEY_URL, found?.apkUrl)
            .apply()
        if (found == null) apkFile.delete() // ya estás al día: borra el APK de una actualización anterior
        return found
    }

    /** Bloqueante. Descarga el APK de [release] a la caché privada de la app. */
    fun download(release: ReleaseInfo): File {
        checker.download(release, apkFile)
        return apkFile
    }

    /** Android 8+: el usuario debe permitir que ESTA app instale apps (una sola vez). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Abre el instalador. Android verifica que el APK nuevo tenga la MISMA firma que la app instalada;
     * si alguien lo cambiara en el camino, el sistema rechaza la actualización.
     */
    fun installIntent(apk: File): Intent {
        // Desde Android 7 no se pueden compartir rutas file:// entre apps: FileProvider da un content://
        // con permiso de lectura temporal solo para el instalador.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun pendingRelease(): ReleaseInfo? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        return ReleaseInfo(version, url, "").takeIf { UpdateChecker.isNewer(version, currentVersion) }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_VERSION = "pending_version"
        const val KEY_URL = "pending_url"
    }
}
