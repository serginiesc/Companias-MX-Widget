package mx.mitelcel.widget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * Imagen de fondo de cada widget, copiada a la carpeta privada de la app.
 * Copiamos (y reducimos) la foto en lugar de guardar su Uri: el permiso para leer una foto de la
 * galería caduca, y el launcher que dibuja el widget no tendría acceso a ella.
 */
class BackgroundImageStore(private val context: Context) {
    private val dir = File(context.filesDir, "backgrounds")

    /** Bloqueante. Prepara la imagen elegida como "pendiente" hasta que el usuario guarde. Devuelve si es clara. */
    fun savePending(appWidgetId: Int, uri: Uri): Boolean {
        val bitmap = decodeScaled(uri) ?: throw IllegalArgumentException("No se pudo leer la imagen")
        dir.mkdirs()
        pending(appWidgetId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return isLight(bitmap)
    }

    fun loadPending(appWidgetId: Int): Bitmap? = decodeFile(pending(appWidgetId))

    fun load(appWidgetId: Int): Bitmap? = decodeFile(final(appWidgetId))

    /** Al guardar la configuración: la pendiente pasa a ser la definitiva. */
    fun commitPending(appWidgetId: Int) {
        val p = pending(appWidgetId)
        if (p.exists()) {
            final(appWidgetId).delete()
            p.renameTo(final(appWidgetId))
        }
    }

    fun discardPending(appWidgetId: Int) {
        pending(appWidgetId).delete()
    }

    fun delete(appWidgetId: Int) {
        final(appWidgetId).delete()
        pending(appWidgetId).delete()
    }

    private fun final(id: Int) = File(dir, "widget_$id.jpg")
    private fun pending(id: Int) = File(dir, "widget_${id}_pending.jpg")

    private fun decodeFile(file: File): Bitmap? =
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    /**
     * Una foto del celular puede pesar 12 MP (≈48 MB en memoria) y los widgets tienen un límite
     * de memoria para imágenes: la reducimos a máx. [MAX_SIDE] px de lado.
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize (potencia de 2) reduce al decodificar, sin cargar la foto completa en memoria.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    }

    /** Luminancia promedio en una versión de 16×16 px: suficiente para saber si la foto es clara. */
    private fun isLight(bitmap: Bitmap): Boolean {
        val tiny = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var sum = 0f
        for (x in 0 until 16) for (y in 0 until 16) sum += Color.luminance(tiny.getPixel(x, y))
        return sum / 256f > 0.55f
    }

    private companion object {
        const val MAX_SIDE = 800
    }
}
