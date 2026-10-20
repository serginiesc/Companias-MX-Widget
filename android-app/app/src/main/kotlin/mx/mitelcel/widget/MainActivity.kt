package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.mitelcel.widget.core.Carrier
import mx.mitelcel.widget.core.ReleaseInfo
import mx.mitelcel.widget.core.TelcelClient
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.UsageRepository
import mx.mitelcel.widget.widget.MiTelcelWidgetProvider
import mx.mitelcel.widget.widget.Panel
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.work.RefreshWorker

/** Administrar líneas (agregar, editar, borrar), probarlas y ver la vista previa. */
class MainActivity : AppCompatActivity() {
    private lateinit var repository: UsageRepository

    private lateinit var accountsContainer: LinearLayout
    private lateinit var emptyAccountsText: TextView
    private lateinit var formTitle: TextView
    private lateinit var aliasInput: TextInputEditText
    private lateinit var phoneLayout: TextInputLayout
    private lateinit var phoneInput: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var cancelEditButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var carrierGroup: MaterialButtonToggleGroup
    private lateinit var baitNotice: TextView
    private lateinit var updateManager: UpdateManager

    /** Un botón por compañía. Para agregar una compañía: valor en Carrier + botón en el layout + entrada aquí. */
    private val carrierButtons = mapOf(
        Carrier.TELCEL to R.id.carrier_telcel,
        Carrier.BAIT to R.id.carrier_bait,
    )

    /** null = el formulario agrega una línea nueva; con valor = está editando esa línea. */
    private var editingAccount: Account? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la app se cerró la vez anterior, primero mostramos por qué.
        if (CrashReporter.pending(this) != null) {
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        SystemBars.applyInsets(findViewById(R.id.main_root))

        repository = UsageRepository(applicationContext)
        accountsContainer = findViewById(R.id.accounts_container)
        emptyAccountsText = findViewById(R.id.empty_accounts_text)
        formTitle = findViewById(R.id.form_title)
        aliasInput = findViewById(R.id.alias_input)
        phoneLayout = findViewById(R.id.phone_layout)
        phoneInput = findViewById(R.id.phone_input)
        passwordLayout = findViewById(R.id.password_layout)
        passwordInput = findViewById(R.id.password_input)
        saveButton = findViewById(R.id.save_button)
        cancelEditButton = findViewById(R.id.cancel_edit_button)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        previewContainer = findViewById(R.id.preview_container)
        carrierGroup = findViewById(R.id.carrier_group)
        baitNotice = findViewById(R.id.bait_notice)
        updateManager = UpdateManager(applicationContext)

        carrierGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) onCarrierChanged() }
        saveButton.setOnClickListener { onSaveClicked() }
        cancelEditButton.setOnClickListener { resetForm() }
        setupPinButton(findViewById(R.id.pin_button))

        renderAccounts()
        showPreview(repository.accounts.list().firstOrNull())
        onCarrierChanged()
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) checkForUpdate()
    }

    // ------------------------------------------------------------------ compañía

    private fun selectedCarrier(): Carrier =
        carrierButtons.entries.firstOrNull { it.value == carrierGroup.checkedButtonId }?.key ?: Carrier.TELCEL

    /** Bait no usa contraseña: ocultamos el campo para no pedir algo que no se necesita. */
    private fun onCarrierChanged() {
        val carrier = selectedCarrier()
        passwordLayout.visibility = if (carrier.needsPassword) View.VISIBLE else View.GONE
        baitNotice.visibility = if (carrier == Carrier.BAIT) View.VISIBLE else View.GONE
        passwordLayout.error = null
    }

    // ------------------------------------------------------------- actualizaciones

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { updateManager.check() } ?: return@launch
            findViewById<TextView>(R.id.update_text).text = getString(R.string.update_available, release.version)
            findViewById<View>(R.id.update_card).visibility = View.VISIBLE
            findViewById<MaterialButton>(R.id.update_button).setOnClickListener { installUpdate(release) }
        }
    }

    private fun installUpdate(release: ReleaseInfo) {
        // Primero el permiso "instalar apps desconocidas" para ESTA app: sin él, Android rechaza el APK.
        if (!updateManager.canInstall()) {
            statusText.setText(R.string.update_allow_install)
            startActivity(updateManager.installPermissionIntent())
            return
        }
        val button = findViewById<MaterialButton>(R.id.update_button)
        button.isEnabled = false
        statusText.text = getString(R.string.update_downloading, release.version)
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) { runCatching { updateManager.download(release) } }
            button.isEnabled = true
            apk.onSuccess { startActivity(updateManager.installIntent(it)) }
                .onFailure { statusText.text = "⚠ ${it.message ?: "No se pudo descargar la actualización"}" }
        }
    }

    // --------------------------------------------------------------------- líneas

    private fun renderAccounts() {
        val accounts = repository.accounts.list()
        emptyAccountsText.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        accountsContainer.removeAllViews()
        accounts.forEach { accountsContainer.addView(accountRow(it)) }
    }

    /** Una fila: "Mía · Telcel · 5512345678   [Editar] [Borrar]". Tocar el texto muestra su vista previa. */
    private fun accountRow(account: Account): View {
        val label = TextView(this).apply {
            text = "${account.alias}  ·  ${account.carrier.displayName}  ·  ${account.phone}"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showPreview(account) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label)
            addView(textButton(this, "Editar") { startEditing(account) })
            addView(textButton(this, "Borrar") { confirmDelete(account) })
        }
    }

    private fun textButton(parent: LinearLayout, text: String, onClick: () -> Unit): MaterialButton =
        (layoutInflater.inflate(R.layout.item_text_button, parent, false) as MaterialButton).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun startEditing(account: Account) {
        editingAccount = account
        formTitle.text = getString(R.string.edit_line, account.alias)
        aliasInput.setText(account.alias)
        phoneInput.setText(account.phone)
        carrierGroup.check(carrierButtons.getValue(account.carrier))
        passwordInput.setText("")
        passwordLayout.helperText = getString(R.string.keep_password_hint)
        cancelEditButton.visibility = View.VISIBLE
        showPreview(account)
    }

    private fun resetForm() {
        editingAccount = null
        formTitle.setText(R.string.add_line)
        aliasInput.setText("")
        phoneInput.setText("")
        passwordInput.setText("")
        phoneLayout.error = null
        passwordLayout.error = null
        passwordLayout.helperText = null
        cancelEditButton.visibility = View.GONE
        carrierGroup.check(R.id.carrier_telcel)
    }

    private fun onSaveClicked() {
        val editing = editingAccount
        val carrier = selectedCarrier()
        val phone = TelcelClient.normalizePhone(phoneInput.text?.toString().orEmpty())
        val password = passwordInput.text?.toString().orEmpty()
        val alias = aliasInput.text?.toString()?.trim().orEmpty()
            .ifEmpty { "Línea ${repository.accounts.list().size + if (editing == null) 1 else 0}" }

        phoneLayout.error = if (phone.length != 10) "Debe tener 10 dígitos" else null
        // Se puede dejar vacía solo al editar una línea de la MISMA compañía (se conserva la guardada).
        val canKeepPassword = editing != null && editing.carrier == carrier
        passwordLayout.error =
            if (carrier.needsPassword && password.isEmpty() && !canKeepPassword) "Escribe la contraseña" else null
        if (phoneLayout.error != null || passwordLayout.error != null) return

        setBusy(true)
        lifecycleScope.launch {
            val (account, result) = withContext(Dispatchers.IO) {
                val saved = repository.accounts.save(alias, phone, password, editing?.id, carrier)
                saved to repository.refresh(saved.id, minAgeMillis = 0) // 0 = forzar consulta real
            }
            setBusy(false)

            WidgetRenderer.updateAll(applicationContext)
            RefreshWorker.schedulePeriodic(applicationContext)
            resetForm()
            renderAccounts()
            showPreview(account, result.entry)
            statusText.text = result.error?.let { "⚠ ${account.alias}: $it" }
                ?: "✓ ${account.alias} actualizada."
        }
    }

    private fun confirmDelete(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar ${account.alias}?")
            .setMessage("Se borrarán sus datos de este teléfono. Los widgets que la mostraban pasarán a la primera línea.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                repository.deleteAccount(account.id)
                if (editingAccount?.id == account.id) resetForm()
                renderAccounts()
                showPreview(repository.accounts.list().firstOrNull())
                WidgetRenderer.updateAll(applicationContext)
                statusText.text = "${account.alias} borrada."
            }
            .show()
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

    private fun showPreview(account: Account?, entry: UsageEntry = repository.cachedOrFallback(account?.id)) {
        val panel = Panel(entry, account?.alias ?: getString(R.string.app_short_name), account?.id)
        val views = WidgetRenderer.build(applicationContext, listOf(panel))
        previewContainer.removeAllViews()
        // RemoteViews.apply(context, parent) infla la vista real (no es la función "apply" de Kotlin).
        // Se usa applicationContext a propósito: con la Activity, AppCompat cambia ImageView por
        // AppCompatImageView al inflar, y RemoteViews no puede llamar setImageBitmap en esa clase.
        previewContainer.addView(views.apply(applicationContext, previewContainer))
    }

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) statusText.text = "Consultando a ${selectedCarrier().displayName}…"
    }
}
