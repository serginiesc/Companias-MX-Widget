package mx.mitelcel.widget

import android.app.Application

/** Se crea antes que cualquier pantalla, widget o Worker: el lugar correcto para instalar el reporte de errores. */
class MiTelcelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
