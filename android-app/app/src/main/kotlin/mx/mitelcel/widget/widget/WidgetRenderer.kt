package mx.mitelcel.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import mx.mitelcel.widget.R
import mx.mitelcel.widget.WidgetConfigActivity
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.AccountStore
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore

/** Convierte datos en la vista del widget. No sabe nada de red. */
object WidgetRenderer {

    /** Construye la vista. [appWidgetId] = null para vistas previas (sin acciones al tocar). */
    fun build(
        context: Context,
        entry: UsageEntry,
        title: String,
        opacityPercent: Int,
        appWidgetId: Int? = null,
        accountId: String? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_mitelcel)
        val hasData = entry.totalMB > 0

        // RemoteViews no permite cambiar la transparencia de un "background", pero sí la de una
        // imagen: por eso el fondo es un ImageView y usamos setImageAlpha (0-255).
        views.setInt(R.id.widget_bg, "setImageAlpha", opacityPercent.coerceIn(0, 100) * 255 / 100)

        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(
            R.id.widget_cutoff,
            if (hasData) "Corte ${entry.shortCutoffDate}" else entry.cutoffDate,
        )
        views.setViewVisibility(R.id.widget_days, if (hasData) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_days, entry.daysLeftText)
        views.setTextColor(R.id.widget_days, WidgetColors.forDaysLeft(entry.daysLeft))

        views.setImageViewBitmap(R.id.widget_gauge, GaugeRenderer.render(entry))
        views.setTextViewText(R.id.widget_spent, "● ${entry.spentShortText} (${entry.percentUsed}%)")
        views.setTextViewText(R.id.widget_daily, "● ${entry.dailyAllowanceText}")
        views.setTextViewText(R.id.widget_footer, entry.footerText)

        if (appWidgetId != null) {
            // Encabezado = configurar ESTE widget (línea y transparencia); resto = actualizar.
            views.setOnClickPendingIntent(R.id.widget_header, configureIntent(context, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_body, refreshIntent(context, appWidgetId, accountId))
        }
        return views
    }

    /** Redibuja un widget con la línea y opacidad que tiene configuradas. */
    fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val config = WidgetConfigStore(context).get(appWidgetId)
        val account = resolveAccount(AccountStore(context), config)
        val entry = account?.let { UsageCache(context).load(it.id) } ?: UsageEntry.errorFallback
        val views = build(context, entry, account?.alias ?: "Mi Telcel", config.opacityPercent, appWidgetId, account?.id)
        manager.updateAppWidget(appWidgetId, views)
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgetIds(context).forEach { update(context, manager, it) }
    }

    /** Cambia solo el pie de página ("Actualizando…") de los widgets de esa línea. */
    fun showStatus(context: Context, accountId: String?, text: String) {
        val manager = AppWidgetManager.getInstance(context)
        val accounts = AccountStore(context)
        val configs = WidgetConfigStore(context)
        widgetIds(context)
            .filter { accountId == null || resolveAccount(accounts, configs.get(it))?.id == accountId }
            .forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_mitelcel)
                views.setTextViewText(R.id.widget_footer, text)
                manager.partiallyUpdateAppWidget(id, views)
            }
    }

    /** Líneas que se ven en algún widget: son las únicas que vale la pena consultar en segundo plano. */
    fun accountIdsInUse(context: Context): Set<String> {
        val accounts = AccountStore(context)
        val configs = WidgetConfigStore(context)
        return widgetIds(context).mapNotNull { resolveAccount(accounts, configs.get(it))?.id }.toSet()
    }

    /** Si la línea configurada se borró (o el widget nunca se configuró), usa la primera. */
    fun resolveAccount(accounts: AccountStore, config: WidgetConfig): Account? {
        val all = accounts.list()
        return all.firstOrNull { it.id == config.accountId } ?: all.firstOrNull()
    }

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MiTelcelWidgetProvider::class.java))

    private fun configureIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * requestCode = appWidgetId A PROPÓSITO. Android considera "el mismo" PendingIntent si coinciden
     * requestCode e Intent (los extras NO cuentan). Con un requestCode fijo, el widget 2 pisaría al
     * widget 1 y tocar cualquiera actualizaría siempre la misma línea.
     */
    private fun refreshIntent(context: Context, appWidgetId: Int, accountId: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            appWidgetId,
            Intent(context, MiTelcelWidgetProvider::class.java)
                .setAction(MiTelcelWidgetProvider.ACTION_REFRESH)
                .putExtra(MiTelcelWidgetProvider.EXTRA_ACCOUNT_ID, accountId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
