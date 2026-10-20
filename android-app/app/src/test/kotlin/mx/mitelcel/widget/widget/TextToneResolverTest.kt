package mx.mitelcel.widget.widget

import mx.mitelcel.widget.data.TextTone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica pura: no necesita Robolectric. */
class TextToneResolverTest {
    private fun dark(tone: TextTone, opacity: Int, hasImage: Boolean = false, imageLight: Boolean = false, wallpaperLight: Boolean? = null) =
        TextToneResolver.useDarkText(tone, opacity, hasImage, imageLight) { wallpaperLight }

    @Test
    fun `blanco y negro forzados ignoran el fondo`() {
        assertFalse(dark(TextTone.WHITE, 0, wallpaperLight = true))
        assertTrue(dark(TextTone.BLACK, 100))
    }

    @Test
    fun `automático con fondo opaco mira el fondo del widget`() {
        assertFalse(dark(TextTone.AUTO, 100)) // fondo oscuro propio
        assertTrue(dark(TextTone.AUTO, 80, hasImage = true, imageLight = true))
        assertFalse(dark(TextTone.AUTO, 80, hasImage = true, imageLight = false))
    }

    @Test
    fun `automático con fondo transparente mira el fondo de pantalla`() {
        assertTrue(dark(TextTone.AUTO, 20, wallpaperLight = true))
        assertFalse(dark(TextTone.AUTO, 20, wallpaperLight = false))
        assertFalse(dark(TextTone.AUTO, 0, wallpaperLight = null)) // sin saber: blanco con sombra
    }
}
