package mx.mitelcel.widget.widget

import android.graphics.Color

/** Mismos colores que la versión iOS. */
object WidgetColors {
    val BRAND_GREEN = Color.rgb(51, 214, 74)
    val SPENT_RED = Color.rgb(255, 69, 59)
    val WARN_YELLOW = Color.rgb(255, 214, 10)
    val SUBTITLE_GRAY = Color.rgb(153, 153, 158)
    /** Blanco semitransparente: se ve gris sobre el fondo oscuro y sigue viéndose sobre el fondo de pantalla. */
    val TRACK = Color.argb(70, 255, 255, 255)

    /** Sombra para que el texto se lea con el fondo transparente sobre fondos claros. */
    val TEXT_SHADOW = Color.argb(160, 0, 0, 0)
    val WHITE = Color.WHITE

    fun forDaysLeft(daysLeft: Int): Int = when {
        daysLeft <= 2 -> SPENT_RED
        daysLeft <= 5 -> WARN_YELLOW
        else -> WHITE
    }
}
