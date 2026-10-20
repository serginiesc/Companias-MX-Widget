package mx.mitelcel.widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import mx.mitelcel.widget.data.WidgetConfigStore
import mx.mitelcel.widget.work.RefreshWorker

/**
 * Recibe los eventos del sistema sobre los widgets. Debe ser rápido (≈10 s máx.),
 * por eso NUNCA consulta a Telcel aquí: delega el trabajo de red a [RefreshWorker].
 */
class MiTelcelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Pinta de inmediato lo último guardado y luego pide datos nuevos en segundo plano.
        appWidgetIds.forEach { WidgetRenderer.update(context, manager, it) }
        RefreshWorker.schedulePeriodic(context)
        // minAge de 5 min: onUpdate puede dispararse varias veces seguidas (ej. al reiniciar el
        // launcher); con dato reciente en caché no se vuelve a iniciar sesión en Telcel.
        RefreshWorker.refreshNow(context, accountId = null, minAgeMillis = RefreshWorker.PERIODIC_MIN_AGE_MS)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // El usuario quitó widgets: borramos su configuración e imagen de fondo para no dejar basura.
        val configs = WidgetConfigStore(context)
        val images = BackgroundImageStore(context)
        appWidgetIds.forEach {
            configs.delete(it)
            images.delete(it)
        }
    }

    override fun onDisabled(context: Context) {
        // Se quitó el último widget: ya no tiene sentido iniciar sesión cada 30 min.
        RefreshWorker.cancelPeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
            WidgetRenderer.showStatus(context, accountId, "Actualizando…")
            RefreshWorker.refreshNow(context, accountId, minAgeMillis = RefreshWorker.MANUAL_MIN_AGE_MS)
        }
    }

    companion object {
        const val ACTION_REFRESH = "mx.mitelcel.widget.action.REFRESH"
        const val EXTRA_ACCOUNT_ID = "account_id"
    }
}
