package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.AccountStore
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.work.RefreshWorker

/**
 * Se abre al agregar un widget (Android la lanza sola) o al tocar el encabezado del widget.
 * Permite elegir qué línea muestra y la transparencia del fondo.
 */
class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var configStore: WidgetConfigStore
    private lateinit var accountStore: AccountStore
    private lateinit var cache: UsageCache

    private lateinit var accountGroup: RadioGroup
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySlider: Slider
    private lateinit var preview: FrameLayout

    /** RadioButton (id de vista) -> línea. Los ids de vista se generan al vuelo. */
    private val accountByViewId = mutableMapOf<Int, Account>()

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

        accountGroup = findViewById(R.id.account_group)
        opacityLabel = findViewById(R.id.opacity_label)
        opacitySlider = findViewById(R.id.opacity_slider)
        preview = findViewById(R.id.config_preview)

        findViewById<MaterialButton>(R.id.manage_accounts_button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.config_save_button).setOnClickListener { save() }

        val current = configStore.get(appWidgetId)
        // El Slider truena si el valor no es múltiplo de stepSize (5): redondeamos por si acaso.
        opacitySlider.value = ((current.opacityPercent + 2) / 5 * 5).coerceIn(0, 100).toFloat()
        opacitySlider.addOnChangeListener { _, _, _ -> renderPreview() }
        accountGroup.setOnCheckedChangeListener { _, _ -> renderPreview() }
        bindAccounts(WidgetRenderer.resolveAccount(accountStore, current)?.id)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de "Administrar líneas" puede haber líneas nuevas.
        if (::accountGroup.isInitialized) bindAccounts(selectedAccount()?.id)
    }

    private fun bindAccounts(selectedId: String?) {
        val accounts = accountStore.list()
        accountGroup.removeAllViews()
        accountByViewId.clear()
        findViewById<View>(R.id.no_accounts_text).visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE

        accounts.forEach { account ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${account.alias}  ·  ${account.phone}"
            }
            accountByViewId[button.id] = account
            accountGroup.addView(button)
            if (account.id == selectedId) accountGroup.check(button.id)
        }
        if (accountGroup.checkedRadioButtonId == View.NO_ID && accountGroup.childCount > 0) {
            accountGroup.check(accountGroup.getChildAt(0).id)
        }
        renderPreview()
    }

    private fun selectedAccount(): Account? = accountByViewId[accountGroup.checkedRadioButtonId]

    private fun renderPreview() {
        val opacity = opacitySlider.value.toInt()
        opacityLabel.text = getString(R.string.config_opacity, opacity)
        val account = selectedAccount()
        val entry = account?.let { cache.load(it.id) } ?: UsageEntry.errorFallback
        val views = WidgetRenderer.build(applicationContext, entry, account?.alias ?: "Mi Telcel", opacity)
        preview.removeAllViews()
        // applicationContext, NO la Activity: AppCompat cambiaría ImageView por AppCompatImageView
        // y RemoteViews truena (el bug de la versión 1.0).
        preview.addView(views.apply(applicationContext, preview))
    }

    private fun save() {
        val account = selectedAccount()
        configStore.save(appWidgetId, WidgetConfig(account?.id, opacitySlider.value.toInt()))
        WidgetRenderer.update(applicationContext, AppWidgetManager.getInstance(this), appWidgetId)
        account?.let { RefreshWorker.refreshNow(applicationContext, it.id, RefreshWorker.PERIODIC_MIN_AGE_MS) }
        RefreshWorker.schedulePeriodic(applicationContext)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
