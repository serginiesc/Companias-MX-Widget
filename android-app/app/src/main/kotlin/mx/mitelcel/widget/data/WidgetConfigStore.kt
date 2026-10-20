package mx.mitelcel.widget.data

import android.content.Context

/**
 * Configuración de UN widget en la pantalla de inicio.
 * @param accountIds líneas que muestra (máx. [MAX_LINES]); vacío = la primera línea guardada.
 * @param opacityPercent opacidad del fondo: 0 = transparente, 100 = sólido.
 */
data class WidgetConfig(
    val accountIds: List<String> = emptyList(),
    val opacityPercent: Int = DEFAULT_OPACITY,
) {
    companion object {
        const val DEFAULT_OPACITY = 100
        const val MAX_LINES = 2
    }
}

/** Guarda la configuración de cada widget usando el appWidgetId que Android le asigna. */
class WidgetConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget_configs", Context.MODE_PRIVATE)

    fun get(appWidgetId: Int): WidgetConfig {
        val ids = prefs.getString(accountsKey(appWidgetId), null)
            ?.split(SEPARATOR)?.filter { it.isNotEmpty() }
            // Versión 1.2 guardaba una sola línea en otra llave.
            ?: listOfNotNull(prefs.getString(legacyAccountKey(appWidgetId), null))
        return WidgetConfig(
            accountIds = ids.take(WidgetConfig.MAX_LINES),
            opacityPercent = prefs.getInt(opacityKey(appWidgetId), WidgetConfig.DEFAULT_OPACITY),
        )
    }

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString(accountsKey(appWidgetId), config.accountIds.take(WidgetConfig.MAX_LINES).joinToString(SEPARATOR))
            .putInt(opacityKey(appWidgetId), config.opacityPercent.coerceIn(0, 100))
            .remove(legacyAccountKey(appWidgetId))
            .apply()
    }

    fun delete(appWidgetId: Int) {
        prefs.edit()
            .remove(accountsKey(appWidgetId))
            .remove(legacyAccountKey(appWidgetId))
            .remove(opacityKey(appWidgetId))
            .apply()
    }

    private fun accountsKey(id: Int) = "accounts_$id"
    private fun legacyAccountKey(id: Int) = "account_$id"
    private fun opacityKey(id: Int) = "opacity_$id"

    private companion object {
        const val SEPARATOR = "," // los ids son UUID: nunca contienen comas
    }
}
