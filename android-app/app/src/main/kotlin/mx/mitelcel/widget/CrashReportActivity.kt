package mx.mitelcel.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Muestra el error que cerró la app. A propósito usa solo vistas básicas de Android
 * (sin AppCompat ni Material ni XML): si el problema está en el tema o en un layout,
 * esta pantalla debe poder abrirse de todas formas.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.pending(this) ?: "No hay errores guardados."
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "La app se cerró la última vez. Copia este texto y envíalo para corregirlo:"
            textSize = 16f
            setPadding(0, 0, 0, pad)
        }
        val details = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val copy = Button(this).apply {
            text = "Copiar error"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Error Mi Telcel Widget", report))
                Toast.makeText(this@CrashReportActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }
        val share = Button(this).apply {
            text = "Compartir"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "Compartir error"))
            }
        }
        val retry = Button(this).apply {
            text = "Borrar y volver a intentar"
            setOnClickListener {
                CrashReporter.clear(this@CrashReportActivity)
                startActivity(Intent(this@CrashReportActivity, MainActivity::class.java))
                finish()
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad * 3)
            addView(title)
            addView(copy)
            addView(share)
            addView(retry)
            addView(details)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
