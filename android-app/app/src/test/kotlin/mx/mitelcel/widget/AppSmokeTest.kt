package mx.mitelcel.widget

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.widget.WidgetRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas de humo: abren las pantallas en un Android simulado (Robolectric).
 * Si algo truena al iniciar (tema, layout, recurso faltante), falla aquí y no en tu celular.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmokeTest {

    @Test
    fun `la pantalla principal abre sin cerrarse`() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @Test
    fun `el widget se dibuja sin datos y con datos`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(context)
        WidgetRenderer.build(context, UsageEntry.errorFallback).apply(context, parent)
        WidgetRenderer.build(context, UsageEntry.errorFallback.copy(totalMB = 15000.0, consumedMB = 9500.0, availableMB = 5500.0, daysLeft = 9))
            .apply(context, parent)
    }

    @Test
    fun `la pantalla de error abre sin cerrarse`() {
        Robolectric.buildActivity(CrashReportActivity::class.java).setup()
    }
}
