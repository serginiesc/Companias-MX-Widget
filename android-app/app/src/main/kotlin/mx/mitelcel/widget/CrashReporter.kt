package mx.mitelcel.widget

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Guarda el último error que cerró la app, para mostrarlo la próxima vez que se abra.
 * Sin esto, en un celular sin cable USB no hay forma de saber por qué se cerró.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(appContext).writeText(buildReport(appContext, error)) }
            // Dejamos que el manejador original cierre la app como siempre.
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun buildReport(context: Context, error: Throwable): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return buildString {
            appendLine("App: $version")
            appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(error.stackTraceToString())
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
