package mx.mitelcel.widget.widget

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import mx.mitelcel.widget.R
import mx.mitelcel.widget.data.TextTone
import mx.mitelcel.widget.data.WidgetConfig

/** Cómo se ve un widget: opacidad, color de texto e imagen de fondo opcional. */
data class WidgetStyle(
    val opacityPercent: Int = WidgetConfig.DEFAULT_OPACITY,
    val darkText: Boolean = false,
    val backgroundImage: Bitmap? = null,
) {
    val palette: WidgetPalette get() = if (darkText) WidgetPalette.DARK_TEXT else WidgetPalette.LIGHT_TEXT

    companion object {
        /** Lee la configuración guardada de un widget y decide el color de texto real. */
        fun forWidget(context: Context, appWidgetId: Int, config: WidgetConfig): WidgetStyle {
            val image = if (config.hasBackgroundImage) BackgroundImageStore(context).load(appWidgetId) else null
            return WidgetStyle(
                opacityPercent = config.opacityPercent,
                darkText = TextToneResolver.useDarkText(
                    tone = config.textTone,
                    opacityPercent = config.opacityPercent,
                    hasImage = image != null,
                    imageIsLight = config.imageIsLight,
                    wallpaperIsLight = { wallpaperIsLight(context) },
                ),
                backgroundImage = image,
            )
        }

        /**
         * ¿El fondo de pantalla es claro? Android 12+ lo dice directamente; en 8.1–11 se estima con el
         * color principal. null si no se puede saber (ej. fondo animado).
         */
        fun wallpaperIsLight(context: Context): Boolean? = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@runCatching null // API 27+
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
            } else {
                Color.luminance(colors.primaryColor.toArgb()) > 0.5f
            }
        }.getOrNull()
    }
}

/**
 * Decide si el texto va negro. Es lógica pura (sin Android) para poder probarla fácil.
 * "Automático" mira lo que realmente queda detrás del texto:
 * - Fondo opaco (≥ 50%): el fondo del widget (oscuro, o la imagen elegida).
 * - Fondo transparente (< 50%): el fondo de pantalla del teléfono.
 */
object TextToneResolver {
    fun useDarkText(
        tone: TextTone,
        opacityPercent: Int,
        hasImage: Boolean,
        imageIsLight: Boolean,
        wallpaperIsLight: () -> Boolean?,
    ): Boolean = when (tone) {
        TextTone.WHITE -> false
        TextTone.BLACK -> true
        TextTone.AUTO -> when {
            opacityPercent >= 50 -> hasImage && imageIsLight // el fondo propio es oscuro salvo imagen clara
            else -> wallpaperIsLight() ?: false // si no se sabe, blanco con sombra (lo más legible en general)
        }
    }
}

/** Colores del texto y del medidor para texto claro o texto oscuro. */
data class WidgetPalette(
    val primary: Int,
    val secondary: Int,
    val spent: Int,
    val available: Int,
    val warn: Int,
    val track: Int,
    val textShadow: Int?,
    val panelLayout: Int,
    val pillBackground: Int,
) {
    fun forDaysLeft(daysLeft: Int): Int = when {
        daysLeft <= 2 -> spent
        daysLeft <= 5 -> warn
        else -> primary
    }

    companion object {
        val LIGHT_TEXT = WidgetPalette(
            primary = Color.WHITE,
            secondary = Color.rgb(153, 153, 158),
            spent = Color.rgb(255, 69, 59),
            available = Color.rgb(51, 214, 74),
            warn = Color.rgb(255, 214, 10),
            track = Color.argb(70, 255, 255, 255),
            textShadow = Color.argb(160, 0, 0, 0),
            panelLayout = R.layout.widget_panel,
            pillBackground = R.drawable.pill_background,
        )

        /** Tonos más oscuros de rojo/verde/amarillo: los brillantes casi no se leen sobre blanco. */
        val DARK_TEXT = WidgetPalette(
            primary = Color.rgb(17, 17, 17),
            secondary = Color.rgb(80, 80, 85),
            spent = Color.rgb(200, 30, 30),
            available = Color.rgb(20, 130, 50),
            warn = Color.rgb(170, 110, 0),
            track = Color.argb(45, 0, 0, 0),
            textShadow = null,
            panelLayout = R.layout.widget_panel_dark,
            pillBackground = R.drawable.pill_background_dark,
        )
    }
}
