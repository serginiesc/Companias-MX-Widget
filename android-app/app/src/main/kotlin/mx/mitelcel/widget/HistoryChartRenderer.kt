package mx.mitelcel.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import mx.mitelcel.widget.core.DayUsage

/** Gráfica de barras del consumo por día (últimos 14 días) para el diálogo de historial. */
object HistoryChartRenderer {
    private const val WIDTH = 840
    private const val HEIGHT = 420
    private const val BOTTOM = 60f // espacio para las etiquetas de día
    private const val TOP = 50f // espacio para el máximo

    fun render(days: List<DayUsage>, mbPerGb: Int, textColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val max = days.maxOfOrNull { it.consumedMB }?.takeIf { it > 0 } ?: 1.0
        val slot = WIDTH.toFloat() / days.size.coerceAtLeast(1)
        val usable = HEIGHT - BOTTOM - TOP

        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(51, 160, 74) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }

        days.forEachIndexed { i, day ->
            val h = (day.consumedMB / max * usable).toFloat()
            val left = i * slot + slot * 0.18f
            val right = (i + 1) * slot - slot * 0.18f
            canvas.drawRoundRect(RectF(left, TOP + usable - h, right, TOP + usable), 6f, 6f, bar)
            // Etiqueta día cada 2 barras para que no se encimen.
            if (i % 2 == days.size % 2) {
                canvas.drawText(day.date.dayOfMonth.toString(), (left + right) / 2, HEIGHT - 20f, label)
            }
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("máx. ${format(max, mbPerGb)}/día", 8f, 32f, label)
        return bitmap
    }

    private fun format(mb: Double, mbPerGb: Int) =
        if (mb >= mbPerGb) String.format(Locale.US, "%.1f GB", mb / mbPerGb) else String.format(Locale.US, "%.0f MB", mb)
}
