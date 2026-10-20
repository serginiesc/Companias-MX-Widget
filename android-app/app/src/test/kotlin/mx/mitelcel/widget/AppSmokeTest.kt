package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
@Config(sdk = [34, 35])
class AppSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `la pantalla principal abre sin cerrarse`() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @Test
    fun `la configuración del widget abre sin cerrarse`() {
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7)
        Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup()
    }

    @Test
    fun `el widget se dibuja transparente, a medias y sólido`() {
        val parent = FrameLayout(context)
        val withData = UsageEntry.errorFallback.copy(totalMB = 15000.0, consumedMB = 9500.0, availableMB = 5500.0, daysLeft = 9)
        listOf(0, 60, 100).forEach { opacity ->
            WidgetRenderer.build(context, UsageEntry.errorFallback, "Mía", opacity).apply(context, parent)
            WidgetRenderer.build(context, withData, "Esposa", opacity, appWidgetId = 3, accountId = "abc").apply(context, parent)
        }
    }

    @Test
    fun `la pantalla de error abre sin cerrarse`() {
        Robolectric.buildActivity(CrashReportActivity::class.java).setup()
    }
}
