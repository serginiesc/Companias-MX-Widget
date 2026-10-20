package mx.mitelcel.widget.data

import android.content.Context

/** Color del texto del widget. AUTO lo decide según lo que haya detrás (ver TextToneResolver). */
enum class TextTone { AUTO, WHITE, BLACK }

/**
 * Configuración de UN widget en la pantalla de inicio.
 * @param accountIds líneas que muestra (máx. [MAX_LINES]); vacío = la primera línea guardada.
 * @param opacityPercent opacidad del fondo (color o imagen): 0 = transparente, 100 = sólido.
 * @param hasBackgroundImage si usa una imagen propia (BackgroundImageStore) en lugar del fondo oscuro.
 * @param imageIsLight si esa imagen es clara: en modo AUTO decide texto negro.
 */
data class WidgetConfig(
    val accountIds: List<String> = emptyList(),
    val opacityPercent: Int = DEFAULT_OPACITY,
    val textTone: TextTone = TextTone.AUTO,
    val hasBackgroundImage: Boolean = false,
    val imageIsLight: Boolean = false,
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
            // Un valor desconocido (ej. guardado por una versión futura) cae en AUTO en lugar de tronar.
            textTone = prefs.getString(toneKey(appWidgetId), null)
                ?.let { runCatching { TextTone.valueOf(it) }.getOrNull() } ?: TextTone.AUTO,
            hasBackgroundImage = prefs.getBoolean(imageKey(appWidgetId), false),
            imageIsLight = prefs.getBoolean(imageLightKey(appWidgetId), false),
        )
    }

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString(accountsKey(appWidgetId), config.accountIds.take(WidgetConfig.MAX_LINES).joinToString(SEPARATOR))
            .putInt(opacityKey(appWidgetId), config.opacityPercent.coerceIn(0, 100))
            .putString(toneKey(appWidgetId), config.textTone.name)
            .putBoolean(imageKey(appWidgetId), config.hasBackgroundImage)
            .putBoolean(imageLightKey(appWidgetId), config.imageIsLight)
            .remove(legacyAccountKey(appWidgetId))
            .apply()
    }

    fun delete(appWidgetId: Int) {
        prefs.edit()
            .remove(accountsKey(appWidgetId))
            .remove(legacyAccountKey(appWidgetId))
            .remove(opacityKey(appWidgetId))
            .remove(toneKey(appWidgetId))
            .remove(imageKey(appWidgetId))
            .remove(imageLightKey(appWidgetId))
            .apply()
    }

    private fun accountsKey(id: Int) = "accounts_$id"
    private fun legacyAccountKey(id: Int) = "account_$id"
    private fun opacityKey(id: Int) = "opacity_$id"
    private fun toneKey(id: Int) = "tone_$id"
    private fun imageKey(id: Int) = "image_$id"
    private fun imageLightKey(id: Int) = "image_light_$id"

    private companion object {
        const val SEPARATOR = "," // los ids son UUID: nunca contienen comas
    }
}
