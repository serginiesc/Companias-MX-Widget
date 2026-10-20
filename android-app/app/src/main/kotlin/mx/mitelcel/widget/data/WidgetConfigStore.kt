package mx.mitelcel.widget.data

import android.content.Context

/**
 * Configuración de UN widget en la pantalla de inicio.
 * @param accountId línea que muestra; null = la primera línea guardada.
 * @param opacityPercent opacidad del fondo: 0 = transparente, 100 = sólido.
 */
data class WidgetConfig(val accountId: String? = null, val opacityPercent: Int = DEFAULT_OPACITY) {
    companion object {
        const val DEFAULT_OPACITY = 100
    }
}

/** Guarda la configuración de cada widget usando el appWidgetId que Android le asigna. */
class WidgetConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget_configs", Context.MODE_PRIVATE)

    fun get(appWidgetId: Int): WidgetConfig = WidgetConfig(
        accountId = prefs.getString("account_$appWidgetId", null),
        opacityPercent = prefs.getInt("opacity_$appWidgetId", WidgetConfig.DEFAULT_OPACITY),
    )

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString("account_$appWidgetId", config.accountId)
            .putInt("opacity_$appWidgetId", config.opacityPercent.coerceIn(0, 100))
            .apply()
    }

    fun delete(appWidgetId: Int) {
        prefs.edit().remove("account_$appWidgetId").remove("opacity_$appWidgetId").apply()
    }
}
