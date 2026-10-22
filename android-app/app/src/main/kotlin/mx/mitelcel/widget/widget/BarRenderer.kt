package mx.mitelcel.widget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import mx.mitelcel.widget.core.UsageEntry

/**
 * Estilo 2: barra horizontal (rojo = gastado, verde = disponible), dibujada en un Bitmap
 * por la misma razón que el medidor: RemoteViews no permite vistas personalizadas.
 */
object BarRenderer {
    private const val WIDTH = 600
    private const val HEIGHT = 16
    private const val RADIUS = HEIGHT / 2f
    private const val GAP = 6f

    fun render(entry: UsageEntry, palette: WidgetPalette): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = palette.track
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), RADIUS, RADIUS, paint)
        if (entry.unlimited) {
            // Ilimitado: barra completa "disponible".
            paint.color = palette.available
            canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), RADIUS, RADIUS, paint)
            return bitmap
        }
        if (entry.totalMB <= 0) return bitmap // sin datos: solo la pista vacía

        val split = WIDTH * entry.usageRatio.toFloat()
        // Hueco entre los dos colores, salvo en los extremos (0% o 100%), donde se vería raro.
        val gap = if (split > HEIGHT && split < WIDTH - HEIGHT) GAP / 2 else 0f

        if (split > 0.5f) {
            paint.color = palette.spent
            canvas.drawRoundRect(RectF(0f, 0f, maxOf(HEIGHT.toFloat(), split - gap), HEIGHT.toFloat()), RADIUS, RADIUS, paint)
        }
        if (split < WIDTH - 0.5f) {
            paint.color = palette.available
            canvas.drawRoundRect(
                RectF(minOf(WIDTH - HEIGHT.toFloat(), split + gap), 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                RADIUS,
                RADIUS,
                paint,
            )
        }
        return bitmap
    }
}
