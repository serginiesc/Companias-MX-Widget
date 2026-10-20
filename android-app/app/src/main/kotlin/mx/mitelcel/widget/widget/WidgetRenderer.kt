package mx.mitelcel.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import mx.mitelcel.widget.MainActivity
import mx.mitelcel.widget.R
import mx.mitelcel.widget.core.UsageEntry

/** Convierte un [UsageEntry] en la vista del widget. No sabe nada de red ni de caché. */
object WidgetRenderer {

    fun build(context: Context, entry: UsageEntry): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_mitelcel)
        val hasData = entry.totalMB > 0

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

        // Tocar el encabezado abre la app; tocar el resto actualiza.
        views.setOnClickPendingIntent(R.id.widget_header, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_body, refreshIntent(context))
        return views
    }

    fun updateAll(context: Context, entry: UsageEntry) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MiTelcelWidgetProvider::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context, entry))
    }

    /** Cambia solo el pie de página ("Actualizando…") sin redibujar todo. */
    fun showStatus(context: Context, text: String) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MiTelcelWidgetProvider::class.java))
        val views = RemoteViews(context.packageName, R.layout.widget_mitelcel)
        views.setTextViewText(R.id.widget_footer, text)
        ids.forEach { manager.partiallyUpdateAppWidget(it, views) }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, MiTelcelWidgetProvider::class.java).setAction(MiTelcelWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
