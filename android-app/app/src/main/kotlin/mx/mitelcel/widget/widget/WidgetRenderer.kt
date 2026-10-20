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
import mx.mitelcel.widget.data.LayoutStyle
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore

/** Lo que se dibuja de UNA línea dentro del widget. */
data class Panel(val entry: UsageEntry, val title: String, val accountId: String? = null)

/**
 * Convierte datos en la vista del widget. No sabe nada de red.
 *
 * Estructura: un contenedor (widget_mitelcel: fondo + fila vacía) al que se le agrega un
 * panel por cada línea con RemoteViews.addView. Así 1 o 2 líneas usan el mismo código.
 * Estilo 1 (medidor): paneles lado a lado. Estilo 2 (barra): paneles uno debajo del otro.
 */
object WidgetRenderer {

    /**
     * @param appWidgetId null = vista previa (sin acciones al tocar).
     * @param statusText si viene, reemplaza el pie de página de los paneles de [statusAccountId]
     *                   (o de todos, si [statusAccountId] es null). Ej. "Actualizando…".
     */
    fun build(
        context: Context,
        panels: List<Panel>,
        style: WidgetStyle = WidgetStyle(),
        appWidgetId: Int? = null,
        statusText: String? = null,
        statusAccountId: String? = null,
    ): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.widget_mitelcel)
        // RemoteViews no permite cambiar la transparencia de un "background", pero sí la de una
        // imagen: por eso el fondo es un ImageView y usamos setImageAlpha (0-255).
        val alpha = style.opacityPercent.coerceIn(0, 100) * 255 / 100
        val image = style.backgroundImage
        if (image != null) {
            root.setImageViewBitmap(R.id.widget_bg_image, image)
            root.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)
            root.setViewVisibility(R.id.widget_bg_image, View.VISIBLE)
            root.setViewVisibility(R.id.widget_bg, View.GONE)
        } else {
            root.setInt(R.id.widget_bg, "setImageAlpha", alpha)
        }

        // RemoteViews no deja cambiar la orientación de un LinearLayout: hay dos filas y se muestra una.
        val row = if (style.layoutStyle == LayoutStyle.BAR) R.id.widget_panels_stacked else R.id.widget_panels
        root.setViewVisibility(R.id.widget_panels, if (row == R.id.widget_panels) View.VISIBLE else View.GONE)
        root.setViewVisibility(R.id.widget_panels_stacked, if (row == R.id.widget_panels_stacked) View.VISIBLE else View.GONE)
        root.removeAllViews(row)
        panels.ifEmpty { listOf(Panel(UsageEntry.errorFallback, context.getString(R.string.app_short_name))) }
            .forEachIndexed { index, panel ->
                val showStatus = statusText != null && (statusAccountId == null || statusAccountId == panel.accountId)
                root.addView(
                    row,
                    buildPanel(context, panel, style, appWidgetId, index, if (showStatus) statusText else null),
                )
            }
        return root
    }

    private fun buildPanel(
        context: Context,
        panel: Panel,
        style: WidgetStyle,
        appWidgetId: Int?,
        index: Int,
        statusText: String?,
    ): RemoteViews {
        val palette = style.palette
        // El layout depende del diseño y de la sombra del texto (RemoteViews no deja cambiarla en código).
        val views = RemoteViews(context.packageName, style.panelLayout)
        val entry = panel.entry
        val hasData = entry.totalMB > 0

        views.setTextViewText(R.id.widget_title, panel.title)
        views.setTextViewText(
            R.id.widget_cutoff,
            if (hasData) "Corte ${entry.shortCutoffDate}" else entry.cutoffDate,
        )
        views.setViewVisibility(R.id.widget_days, if (hasData) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_days, entry.daysLeftText)
        views.setTextColor(R.id.widget_days, palette.forDaysLeft(entry.daysLeft))
        views.setInt(R.id.widget_days, "setBackgroundResource", palette.pillBackground)
        views.setTextColor(R.id.widget_title, palette.primary)
        views.setTextColor(R.id.widget_cutoff, palette.secondary)
        views.setTextColor(R.id.widget_spent, palette.spent)
        views.setTextColor(R.id.widget_daily, palette.available)
        views.setTextColor(R.id.widget_footer, palette.secondary)

        when (style.layoutStyle) {
            LayoutStyle.GAUGE -> views.setImageViewBitmap(R.id.widget_gauge, GaugeRenderer.render(entry, palette))
            LayoutStyle.BAR -> {
                // Solo existen en el layout de barra: en el medidor estos números van dentro del Bitmap.
                // (Llamar setTextViewText con un id que no existe en el layout truena al aplicarse.)
                views.setImageViewBitmap(R.id.widget_gauge, BarRenderer.render(entry, palette))
                views.setTextViewText(R.id.widget_amount, entry.remainingText)
                views.setTextViewText(R.id.widget_total, "libres de ${entry.totalText}")
                views.setTextColor(R.id.widget_amount, palette.primary)
                views.setTextColor(R.id.widget_total, palette.secondary)
            }
        }
        views.setTextViewText(R.id.widget_spent, "● ${entry.spentShortText} (${entry.percentUsed}%)")
        views.setTextViewText(R.id.widget_daily, "● ${entry.dailyAllowanceText}")
        views.setTextViewText(R.id.widget_footer, statusText ?: entry.footerText)

        if (appWidgetId != null) {
            // Encabezado = configurar ESTE widget; resto del panel = actualizar ESA línea.
            views.setOnClickPendingIntent(R.id.widget_header, configureIntent(context, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_body, refreshIntent(context, appWidgetId, index, panel.accountId))
        }
        return views
    }

    /** Redibuja un widget con las líneas y opacidad que tiene configuradas. */
    fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        statusText: String? = null,
        statusAccountId: String? = null,
    ) {
        val config = WidgetConfigStore(context).get(appWidgetId)
        val cache = UsageCache(context)
        val panels = resolveAccounts(AccountStore(context), config).map { account ->
            Panel(cache.load(account.id) ?: UsageEntry.errorFallback, account.alias, account.id)
        }
        manager.updateAppWidget(
            appWidgetId,
            build(context, panels, WidgetStyle.forWidget(context, appWidgetId, config), appWidgetId, statusText, statusAccountId),
        )
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgetIds(context).forEach { update(context, manager, it) }
    }

    /** Muestra un aviso ("Actualizando…") en el pie de los paneles de esa línea (null = todas). */
    fun showStatus(context: Context, accountId: String?, text: String) {
        val manager = AppWidgetManager.getInstance(context)
        val accounts = AccountStore(context)
        val configs = WidgetConfigStore(context)
        widgetIds(context)
            .filter { id -> accountId == null || resolveAccounts(accounts, configs.get(id)).any { it.id == accountId } }
            .forEach { update(context, manager, it, text, accountId) }
    }

    /** Líneas que se ven en algún widget: son las únicas que vale la pena consultar en segundo plano. */
    fun accountIdsInUse(context: Context): Set<String> {
        val accounts = AccountStore(context)
        val configs = WidgetConfigStore(context)
        // asList(): IntArray (arreglo de primitivos) no tiene flatMap/mapNotNull; List<Int> sí.
        return widgetIds(context).asList().flatMap { id -> resolveAccounts(accounts, configs.get(id)).map { it.id } }.toSet()
    }

    /** Líneas configuradas que aún existen; si ninguna (o el widget no se configuró), la primera guardada. */
    fun resolveAccounts(accounts: AccountStore, config: WidgetConfig): List<Account> {
        val all = accounts.list()
        val chosen = config.accountIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        return chosen.ifEmpty { listOfNotNull(all.firstOrNull()) }.take(WidgetConfig.MAX_LINES)
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
     * requestCode único por widget Y por panel. Android considera "el mismo" PendingIntent si
     * coinciden requestCode e Intent (los extras NO cuentan). Si se repitiera, tocar el panel
     * de una línea actualizaría la otra.
     */
    private fun refreshIntent(context: Context, appWidgetId: Int, panelIndex: Int, accountId: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            appWidgetId * WidgetConfig.MAX_LINES + panelIndex,
            Intent(context, MiTelcelWidgetProvider::class.java)
                .setAction(MiTelcelWidgetProvider.ACTION_REFRESH)
                .putExtra(MiTelcelWidgetProvider.EXTRA_ACCOUNT_ID, accountId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
