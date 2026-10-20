package mx.mitelcel.widget

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Android 15+ dibuja la app detrás de las barras del sistema; dejamos espacio para no taparla. */
object SystemBars {
    fun applyInsets(root: View) {
        val basePadding = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = basePadding + bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }
}
