package mx.mitelcel.widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.work.RefreshWorker

/**
 * Recibe los eventos del sistema sobre el widget. Debe ser rápido (≈10 s máx.),
 * por eso NUNCA consulta a Telcel aquí: delega el trabajo de red a [RefreshWorker].
 */
class MiTelcelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Pinta de inmediato lo último guardado y luego pide datos nuevos en segundo plano.
        WidgetRenderer.updateAll(context, UsageCache(context).load() ?: UsageEntry.errorFallback)
        RefreshWorker.schedulePeriodic(context)
        // minAge de 5 min: onUpdate puede dispararse varias veces seguidas (ej. al reiniciar el
        // launcher); con dato reciente en caché no se vuelve a iniciar sesión en Telcel.
        RefreshWorker.refreshNow(context, minAgeMillis = RefreshWorker.PERIODIC_MIN_AGE_MS)
    }

    override fun onDisabled(context: Context) {
        // Se quitó el último widget: ya no tiene sentido iniciar sesión cada 30 min.
        RefreshWorker.cancelPeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            WidgetRenderer.showStatus(context, "Actualizando…")
            RefreshWorker.refreshNow(context, minAgeMillis = RefreshWorker.MANUAL_MIN_AGE_MS)
        }
    }

    companion object {
        const val ACTION_REFRESH = "mx.mitelcel.widget.action.REFRESH"
    }
}
