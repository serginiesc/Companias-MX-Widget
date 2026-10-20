package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.AccountStore
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore
import mx.mitelcel.widget.widget.Panel
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.work.RefreshWorker

/**
 * Se abre al agregar un widget (Android la lanza sola) o al tocar el encabezado del widget.
 * Permite elegir qué líneas muestra (una o dos) y la transparencia del fondo.
 */
class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var configStore: WidgetConfigStore
    private lateinit var accountStore: AccountStore
    private lateinit var cache: UsageCache

    private lateinit var accountList: LinearLayout
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySlider: Slider
    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: FrameLayout

    /** Casilla -> línea, en el mismo orden en que se muestran. */
    private val checkBoxes = linkedMapOf<CheckBox, Account>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si el usuario sale con "atrás", Android NO debe agregar el widget: por eso CANCELED primero.
        setResult(RESULT_CANCELED)

        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)
        SystemBars.applyInsets(findViewById(R.id.config_root))
        configStore = WidgetConfigStore(applicationContext)
        accountStore = AccountStore(applicationContext)
        cache = UsageCache(applicationContext)

        accountList = findViewById(R.id.account_list)
        opacityLabel = findViewById(R.id.opacity_label)
        opacitySlider = findViewById(R.id.opacity_slider)
        previewFrame = findViewById(R.id.preview_frame)
        preview = findViewById(R.id.config_preview)

        findViewById<MaterialButton>(R.id.manage_accounts_button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.config_save_button).setOnClickListener { save() }

        val current = configStore.get(appWidgetId)
        // El Slider truena si el valor no es múltiplo de stepSize (5): redondeamos por si acaso.
        opacitySlider.value = ((current.opacityPercent + 2) / 5 * 5).coerceIn(0, 100).toFloat()
        opacitySlider.addOnChangeListener { _, _, _ -> renderPreview() }
        bindAccounts(WidgetRenderer.resolveAccounts(accountStore, current).map { it.id })
    }

    override fun onResume() {
        super.onResume()
        // Al volver de "Administrar líneas" puede haber líneas nuevas.
        if (::accountList.isInitialized) bindAccounts(selectedAccounts().map { it.id })
    }

    private fun bindAccounts(selectedIds: List<String>) {
        val accounts = accountStore.list()
        accountList.removeAllViews()
        checkBoxes.clear()
        findViewById<View>(R.id.no_accounts_text).visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE

        accounts.forEach { account ->
            val box = CheckBox(this).apply {
                text = "${account.alias}  ·  ${account.phone}"
                isChecked = account.id in selectedIds
                setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
            }
            checkBoxes[box] = account
            accountList.addView(box)
        }
        // Si no había nada elegido, marca la primera para que el widget no quede vacío.
        if (checkBoxes.keys.none { it.isChecked }) checkBoxes.keys.firstOrNull()?.isChecked = true
        onSelectionChanged()
    }

    /** Con 2 líneas marcadas, las demás casillas se deshabilitan (máximo [WidgetConfig.MAX_LINES]). */
    private fun onSelectionChanged() {
        val full = selectedAccounts().size >= WidgetConfig.MAX_LINES
        checkBoxes.keys.forEach { it.isEnabled = it.isChecked || !full }
        renderPreview()
    }

    private fun selectedAccounts(): List<Account> = checkBoxes.filterKeys { it.isChecked }.values.toList()

    private fun renderPreview() {
        val opacity = opacitySlider.value.toInt()
        opacityLabel.text = getString(R.string.config_opacity, opacity)

        val panels = selectedAccounts().map { Panel(cache.load(it.id) ?: UsageEntry.errorFallback, it.alias, it.id) }
        // Con 2 líneas la vista previa es más ancha, como se verá el widget de 4×2.
        val widthDp = if (panels.size > 1) 340 else 200
        previewFrame.layoutParams = previewFrame.layoutParams.apply {
            width = (widthDp * resources.displayMetrics.density).toInt()
        }

        val views = WidgetRenderer.build(applicationContext, panels, opacity)
        preview.removeAllViews()
        // applicationContext, NO la Activity: AppCompat cambiaría ImageView por AppCompatImageView
        // y RemoteViews truena (el bug de la versión 1.0).
        preview.addView(views.apply(applicationContext, preview))
    }

    private fun save() {
        val accounts = selectedAccounts()
        configStore.save(appWidgetId, WidgetConfig(accounts.map { it.id }, opacitySlider.value.toInt()))
        WidgetRenderer.update(applicationContext, AppWidgetManager.getInstance(this), appWidgetId)
        accounts.forEach { RefreshWorker.refreshNow(applicationContext, it.id, RefreshWorker.PERIODIC_MIN_AGE_MS) }
        RefreshWorker.schedulePeriodic(applicationContext)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
