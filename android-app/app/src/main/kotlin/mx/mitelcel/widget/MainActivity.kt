package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.mitelcel.widget.core.TelcelClient
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Credentials
import mx.mitelcel.widget.data.UsageRepository
import mx.mitelcel.widget.widget.MiTelcelWidgetProvider
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.work.RefreshWorker

/** Pantalla de configuración: capturar credenciales, probarlas y ver la vista previa. */
class MainActivity : AppCompatActivity() {
    private lateinit var repository: UsageRepository

    private lateinit var phoneLayout: TextInputLayout
    private lateinit var phoneInput: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var previewContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la app se cerró la vez anterior, primero mostramos por qué.
        if (CrashReporter.pending(this) != null) {
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.main_root))

        repository = UsageRepository(applicationContext)
        phoneLayout = findViewById(R.id.phone_layout)
        phoneInput = findViewById(R.id.phone_input)
        passwordLayout = findViewById(R.id.password_layout)
        passwordInput = findViewById(R.id.password_input)
        saveButton = findViewById(R.id.save_button)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        previewContainer = findViewById(R.id.preview_container)

        saveButton.setOnClickListener { onSaveClicked() }
        findViewById<MaterialButton>(R.id.clear_button).setOnClickListener { onClearClicked() }
        setupPinButton(findViewById(R.id.pin_button))

        showPreview(repository.cachedOrFallback())
        loadSavedPhone()
    }

    private fun loadSavedPhone() {
        lifecycleScope.launch {
            // Descifrar usa el Keystore: rápido, pero mejor fuera del hilo principal.
            val saved = withContext(Dispatchers.IO) { repository.credentials.load() }
            if (saved != null) {
                phoneInput.setText(saved.phone)
                passwordLayout.helperText = "Ya hay una contraseña guardada. Déjala vacía para conservarla."
            }
        }
    }

    private fun onSaveClicked() {
        val phone = TelcelClient.normalizePhone(phoneInput.text?.toString().orEmpty())
        val typedPassword = passwordInput.text?.toString().orEmpty()

        phoneLayout.error = if (phone.length != 10) "Debe tener 10 dígitos" else null
        if (phoneLayout.error != null) return

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val password = typedPassword.ifEmpty { repository.credentials.load()?.password.orEmpty() }
                if (password.isEmpty()) return@withContext null
                repository.credentials.save(Credentials(phone, password))
                repository.refresh(minAgeMillis = 0) // 0 = forzar consulta real a Telcel
            }
            setBusy(false)

            if (result == null) {
                passwordLayout.error = "Escribe tu contraseña"
                return@launch
            }
            passwordLayout.error = null
            passwordInput.setText("")

            WidgetRenderer.updateAll(applicationContext, result.entry)
            RefreshWorker.schedulePeriodic(applicationContext)
            showPreview(result.entry)
            statusText.text = result.error?.let { "⚠ $it" } ?: "✓ Actualizado. Ya puedes agregar el widget."
        }
    }

    private fun onClearClicked() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.credentials.clear() }
            phoneInput.setText("")
            passwordInput.setText("")
            passwordLayout.helperText = null
            statusText.text = "Datos borrados."
            RefreshWorker.cancelPeriodic(applicationContext)
        }
    }

    private fun setupPinButton(button: MaterialButton) {
        val manager = AppWidgetManager.getInstance(this)
        // Algunos launchers no permiten que una app agregue widgets; ahí se hace a mano.
        if (!manager.isRequestPinAppWidgetSupported) {
            button.visibility = View.GONE
            return
        }
        button.setOnClickListener {
            manager.requestPinAppWidget(ComponentName(this, MiTelcelWidgetProvider::class.java), null, null)
        }
    }

    private fun showPreview(entry: UsageEntry) {
        previewContainer.removeAllViews()
        // RemoteViews.apply(context, parent) infla la vista real (no es la función "apply" de Kotlin).
        // Se usa applicationContext a propósito: con la Activity, AppCompat cambia ImageView por
        // AppCompatImageView al inflar, y RemoteViews no puede llamar setImageBitmap en esa clase.
        previewContainer.addView(WidgetRenderer.build(this, entry).apply(applicationContext, previewContainer))
    }

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) statusText.text = "Consultando a Telcel…"
    }

    /** Android 15 dibuja la app detrás de las barras del sistema; dejamos espacio para no taparla. */
    private fun applySystemBarInsets(root: View) {
        val basePadding = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = basePadding + bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }
}
