package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import mx.mitelcel.widget.core.Forecast
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.AccountStore
import mx.mitelcel.widget.data.LayoutStyle
import mx.mitelcel.widget.data.SecretBox
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore
import mx.mitelcel.widget.widget.Panel
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.widget.WidgetStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

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
    fun `la configuración abre con dos líneas marcadas en diseño barra`() {
        val accounts = AccountStore(
            context,
            object : SecretBox {
                override fun encrypt(plain: String) = plain
                override fun decrypt(encoded: String) = encoded
            },
        )
        val a = accounts.save("Mía", "5511111111", "x")
        val b = accounts.save("Esposa", "5522222222", "y")
        WidgetConfigStore(context).save(8, WidgetConfig(listOf(a.id, b.id), 40, layoutStyle = LayoutStyle.BAR))

        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 8)
        Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup()
    }

    @Test
    fun `el widget se dibuja con 0 1 y 2 líneas en todos los estilos`() {
        val parent = FrameLayout(context)
        val withData = UsageEntry.errorFallback.copy(totalMB = 15000.0, consumedMB = 9500.0, availableMB = 5500.0, daysLeft = 9)
        // "Mía" no alcanza (pinta amarillo); "Esposa" tiene un dato viejo (se atenúa).
        val mine = Panel(withData, "Mía", "a", Forecast.RunsOut(LocalDate.of(2026, 10, 25)))
        val other = Panel(withData.copy(timestampMillis = 1L), "Esposa", "b", Forecast.Enough)
        val photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        // Todas las combinaciones: diseño × color de texto × opacidad × con/sin imagen.
        val styles = LayoutStyle.entries.flatMap { layout ->
            listOf(0, 60, 100).flatMap { opacity ->
                listOf(
                    WidgetStyle(opacity, darkText = false, layoutStyle = layout),
                    WidgetStyle(opacity, darkText = true, layoutStyle = layout),
                    WidgetStyle(opacity, darkText = true, backgroundImage = photo, layoutStyle = layout),
                )
            }
        }
        styles.forEach { style ->
            WidgetRenderer.build(context, emptyList(), style).apply(context, parent)
            WidgetRenderer.build(context, listOf(mine), style, appWidgetId = 3).apply(context, parent)
            WidgetRenderer.build(context, listOf(mine, other), style, appWidgetId = 3, statusText = "Actualizando…", statusAccountId = "b")
                .apply(context, parent)
        }
    }

    /**
     * Los layouts "_dark" son copias obligadas (RemoteViews no deja cambiar la sombra del texto en
     * código). Si alguien edita uno y olvida su copia, esta prueba falla.
     */
    @Test
    fun `cada layout de panel oscuro solo difiere de su original en el estilo del texto`() {
        fun normalized(name: String) = File("src/main/res/layout/$name").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "") // los comentarios sí pueden diferir
            .replace("@style/WidgetTextDark", "@style/WidgetText")
        assertEquals(normalized("widget_panel.xml"), normalized("widget_panel_dark.xml"))
        assertEquals(normalized("widget_bar_panel.xml"), normalized("widget_bar_panel_dark.xml"))
    }

    @Test
    fun `el login de Movistar abre sin cerrarse`() {
        Robolectric.buildActivity(MovistarLoginActivity::class.java).setup()
    }

    @Test
    fun `un plan ilimitado se dibuja en los dos diseños`() {
        val parent = FrameLayout(context)
        val unlimited = UsageEntry.errorFallback.copy(timestampMillis = System.currentTimeMillis(), unlimited = true, daysLeft = 7)
        LayoutStyle.entries.forEach { layout ->
            WidgetRenderer.build(context, listOf(Panel(unlimited, "Movistar")), WidgetStyle(layoutStyle = layout), appWidgetId = 4)
                .apply(context, parent)
        }
    }

    @Test
    fun `la pantalla de error abre sin cerrarse`() {
        Robolectric.buildActivity(CrashReportActivity::class.java).setup()
    }
}
