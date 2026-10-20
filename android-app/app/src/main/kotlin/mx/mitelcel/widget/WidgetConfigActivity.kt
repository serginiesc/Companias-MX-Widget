package mx.mitelcel.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.AccountStore
import mx.mitelcel.widget.data.LayoutStyle
import mx.mitelcel.widget.data.TextTone
import mx.mitelcel.widget.data.UsageCache
import mx.mitelcel.widget.data.WidgetConfig
import mx.mitelcel.widget.data.WidgetConfigStore
import mx.mitelcel.widget.widget.BackgroundImageStore
import mx.mitelcel.widget.widget.Panel
import mx.mitelcel.widget.widget.TextToneResolver
import mx.mitelcel.widget.widget.WidgetRenderer
import mx.mitelcel.widget.widget.WidgetStyle
import mx.mitelcel.widget.work.RefreshWorker

/**
 * Se abre al agregar un widget (Android la lanza sola) o al tocar el encabezado del widget.
 * Permite elegir las líneas (una o dos), la opacidad, el color del texto y una imagen de fondo.
 */
class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var configStore: WidgetConfigStore
    private lateinit var accountStore: AccountStore
    private lateinit var cache: UsageCache
    private lateinit var images: BackgroundImageStore

    private lateinit var accountList: LinearLayout
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySlider: Slider
    private lateinit var toneGroup: MaterialButtonToggleGroup
    private lateinit var layoutGroup: MaterialButtonToggleGroup
    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: FrameLayout

    /** Casilla -> línea, en el mismo orden en que se muestran. */
    private val checkBoxes = linkedMapOf<CheckBox, Account>()

    private val toneButtons = mapOf(
        TextTone.AUTO to R.id.tone_auto,
        TextTone.WHITE to R.id.tone_white,
        TextTone.BLACK to R.id.tone_black,
    )

    private val layoutButtons = mapOf(
        LayoutStyle.GAUGE to R.id.layout_gauge,
        LayoutStyle.BAR to R.id.layout_bar,
    )

    // Estado de la imagen de fondo mientras se configura (se aplica al Guardar).
    private var hasImage = false
    private var imageIsLight = false
    private var imageIsPending = false // true = el usuario eligió una foto nueva aún no guardada
    private var imageBitmap: Bitmap? = null // decodificada una sola vez, no en cada movimiento del slider
    private var saved = false

    /** Consultar los colores del fondo de pantalla es una llamada al sistema: una sola vez por pantalla. */
    private val wallpaperIsLight by lazy { WidgetStyle.wallpaperIsLight(applicationContext) }

    /** Photo Picker: no requiere permiso de galería; Android solo nos da la foto que el usuario elige. */
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

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
        images = BackgroundImageStore(applicationContext)

        accountList = findViewById(R.id.account_list)
        opacityLabel = findViewById(R.id.opacity_label)
        opacitySlider = findViewById(R.id.opacity_slider)
        toneGroup = findViewById(R.id.text_tone_group)
        layoutGroup = findViewById(R.id.layout_style_group)
        previewFrame = findViewById(R.id.preview_frame)
        preview = findViewById(R.id.config_preview)

        findViewById<MaterialButton>(R.id.manage_accounts_button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.config_save_button).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.pick_image_button).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.remove_image_button).setOnClickListener { removeImage() }

        val current = configStore.get(appWidgetId)
        // El Slider truena si el valor no es múltiplo de stepSize (5): redondeamos por si acaso.
        opacitySlider.value = ((current.opacityPercent + 2) / 5 * 5).coerceIn(0, 100).toFloat()
        opacitySlider.addOnChangeListener { _, _, _ -> renderPreview() }
        toneGroup.check(toneButtons.getValue(current.textTone))
        toneGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) renderPreview() }
        layoutGroup.check(layoutButtons.getValue(current.layoutStyle))
        layoutGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) renderPreview() }

        hasImage = current.hasBackgroundImage
        imageIsLight = current.imageIsLight
        imageBitmap = if (hasImage) images.load(appWidgetId) else null
        hasImage = imageBitmap != null // si el archivo se perdió, no fingimos que hay imagen
        updateImageButtons()

        bindAccounts(WidgetRenderer.resolveAccounts(accountStore, current).map { it.id })
    }

    override fun onResume() {
        super.onResume()
        // Al volver de "Administrar líneas" puede haber líneas nuevas.
        if (::accountList.isInitialized) bindAccounts(selectedAccounts().map { it.id })
    }

    override fun onDestroy() {
        // Salió sin guardar: la foto elegida no se usa, así que no la dejamos ocupando espacio.
        if (isFinishing && !saved && ::images.isInitialized) images.discardPending(appWidgetId)
        super.onDestroy()
    }

    // ----------------------------------------------------------------- líneas

    private fun bindAccounts(selectedIds: List<String>) {
        val accounts = accountStore.list()
        accountList.removeAllViews()
        checkBoxes.clear()
        findViewById<View>(R.id.no_accounts_text).visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE

        accounts.forEach { account ->
            val box = CheckBox(this).apply {
                text = "${account.alias}  ·  ${account.carrier.displayName}  ·  ${account.phone}"
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

    // ------------------------------------------------------------ apariencia

    private fun selectedLayout(): LayoutStyle =
        layoutButtons.entries.firstOrNull { it.value == layoutGroup.checkedButtonId }?.key ?: LayoutStyle.GAUGE

    private fun selectedTone(): TextTone =
        toneButtons.entries.firstOrNull { it.value == toneGroup.checkedButtonId }?.key ?: TextTone.AUTO

    private fun onImagePicked(uri: Uri) {
        lifecycleScope.launch {
            // Leer y reducir una foto grande tarda: fuera del hilo principal.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val light = images.savePending(appWidgetId, uri)
                    light to images.loadPending(appWidgetId)
                }
            }
            result.onSuccess { (light, bitmap) ->
                hasImage = bitmap != null
                imageIsLight = light
                imageIsPending = true
                imageBitmap = bitmap
                updateImageButtons()
                renderPreview()
            }.onFailure {
                Toast.makeText(this@WidgetConfigActivity, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeImage() {
        images.discardPending(appWidgetId)
        hasImage = false
        imageIsPending = false
        imageBitmap = null
        updateImageButtons()
        renderPreview()
    }

    private fun updateImageButtons() {
        findViewById<View>(R.id.remove_image_button).visibility = if (hasImage) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.pick_image_button).setText(if (hasImage) R.string.change_image else R.string.pick_image)
    }

    private fun currentStyle(): WidgetStyle {
        val opacity = opacitySlider.value.toInt()
        return WidgetStyle(
            opacityPercent = opacity,
            darkText = TextToneResolver.useDarkText(
                tone = selectedTone(),
                opacityPercent = opacity,
                hasImage = hasImage,
                imageIsLight = imageIsLight,
                wallpaperIsLight = { wallpaperIsLight },
            ),
            backgroundImage = imageBitmap,
            layoutStyle = selectedLayout(),
        )
    }

    private fun renderPreview() {
        val style = currentStyle()
        opacityLabel.text = getString(R.string.config_opacity, style.opacityPercent)

        val panels = selectedAccounts().map { Panel(cache.load(it.id) ?: UsageEntry.errorFallback, it.alias, it.id) }
        // La vista previa toma la proporción con la que se ve mejor cada diseño en la pantalla de inicio:
        // medidor = 2×2 (o 4×2 con dos líneas); barra = 4×1 (o 4×2 con dos líneas, apiladas).
        val twoLines = panels.size > 1
        val (widthDp, heightDp) = when (style.layoutStyle) {
            LayoutStyle.GAUGE -> (if (twoLines) 340 else 200) to 200
            LayoutStyle.BAR -> 330 to (if (twoLines) 220 else 130)
        }
        val density = resources.displayMetrics.density
        previewFrame.layoutParams = previewFrame.layoutParams.apply {
            width = (widthDp * density).toInt()
            height = (heightDp * density).toInt()
        }

        val views = WidgetRenderer.build(applicationContext, panels, style)
        preview.removeAllViews()
        // applicationContext, NO la Activity: AppCompat cambiaría ImageView por AppCompatImageView
        // y RemoteViews truena (el bug de la versión 1.0).
        preview.addView(views.apply(applicationContext, preview))
    }

    private fun save() {
        val accounts = selectedAccounts()
        if (hasImage && imageIsPending) images.commitPending(appWidgetId)
        if (!hasImage) images.delete(appWidgetId)

        configStore.save(
            appWidgetId,
            WidgetConfig(
                accountIds = accounts.map { it.id },
                opacityPercent = opacitySlider.value.toInt(),
                textTone = selectedTone(),
                hasBackgroundImage = hasImage,
                imageIsLight = imageIsLight,
                layoutStyle = selectedLayout(),
            ),
        )
        saved = true
        WidgetRenderer.update(applicationContext, AppWidgetManager.getInstance(this), appWidgetId)
        accounts.forEach { RefreshWorker.refreshNow(applicationContext, it.id, RefreshWorker.PERIODIC_MIN_AGE_MS) }
        RefreshWorker.schedulePeriodic(applicationContext)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
