package mx.mitelcel.widget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import mx.mitelcel.widget.core.UsageEntry
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja el medidor semicircular (rojo = gastado, verde = disponible) en un Bitmap.
 * Los widgets de Android (RemoteViews) no permiten vistas personalizadas, así que
 * dibujamos en una imagen y la mostramos en un ImageView.
 */
object GaugeRenderer {
    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val STROKE = 26f
    private const val RADIUS = 128f

    fun render(entry: UsageEntry, palette: WidgetPalette = WidgetPalette.LIGHT_TEXT): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = WIDTH / 2f
        val top = STROKE / 2f + 2f
        val arcBounds = RectF(cx - RADIUS, top, cx + RADIUS, top + RADIUS * 2)
        val cy = top + RADIUS

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE
            strokeCap = Paint.Cap.ROUND
        }

        // 180° = izquierda; barrido positivo = sentido horario -> el arco pasa por arriba.
        arcPaint.color = palette.track
        canvas.drawArc(arcBounds, 180f, 180f, false, arcPaint)

        if (entry.unlimited) {
            // Ilimitado: todo el arco "disponible".
            arcPaint.color = palette.available
            canvas.drawArc(arcBounds, 180f, 180f, false, arcPaint)
        } else if (entry.totalMB > 0) {
            val ratio = entry.usageRatio.toFloat()
            // Pequeño hueco entre rojo y verde, salvo en los extremos (se vería raro).
            val gap = if (ratio > 0.04f && ratio < 0.96f) 0.06f else 0f

            if (ratio > 0.005f) {
                arcPaint.color = palette.spent
                canvas.drawArc(arcBounds, 180f, 180f * max(0.02f, ratio - gap / 2), false, arcPaint)
            }
            if (ratio < 0.995f) {
                val start = min(0.98f, ratio + gap / 2)
                arcPaint.color = palette.available
                canvas.drawArc(arcBounds, 180f + 180f * start, 180f * (1f - start), false, arcPaint)
            }
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            palette.textShadow?.let { setShadowLayer(4f, 0f, 1.5f, it) }
        }
        textPaint.apply {
            color = palette.primary
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(entry.remainingText, cx, cy - 12f, textPaint)

        textPaint.apply {
            color = palette.secondary
            textSize = 22f
            typeface = Typeface.DEFAULT
        }
        canvas.drawText(entry.subtitleText, cx, cy + 22f, textPaint)

        return bitmap
    }
}
