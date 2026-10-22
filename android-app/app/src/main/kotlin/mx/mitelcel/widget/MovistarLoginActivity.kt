package mx.mitelcel.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mx.mitelcel.widget.core.MovistarClient
import mx.mitelcel.widget.data.PrefsKeyCache
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abre el portal real de Mi Movistar para que el usuario inicie sesión (el reCAPTCHA v3 impide
 * hacerlo desde código) y captura el token de sesión `p_id` en cuanto el portal lo usa.
 * Devuelve el token a quien la abrió; NUNCA lo escribe en el log ni lo muestra.
 */
class MovistarLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    /** shouldInterceptRequest corre en otro hilo: la bandera atómica evita capturar (y cerrar) dos veces. */
    private val captured = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled") // el portal de Movistar es una app web: sin JavaScript no hay login
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val hint = TextView(this).apply {
            setText(R.string.movistar_login_hint)
            setPadding(pad, pad, pad, pad / 2)
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = CaptureClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(webView)
        }
        setContentView(root)
        SystemBars.applyInsets(root)
        webView.loadUrl(LOGIN_URL)

        // "Atrás" navega dentro del portal; en la primera página, cierra.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            },
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private inner class CaptureClient : WebViewClient() {
        // El portal manda el token en la URL de sus peticiones (…?p_id=…): se revisan TODAS.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            captureFrom(request.url)
            return null // no modificamos nada: la página carga normal
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            captureFrom(Uri.parse(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            // La llave para descifrar (guardString) está en la página: se guarda para no descargarla después.
            view.evaluateJavascript("typeof guardString !== 'undefined' ? String(guardString) : ''") { raw ->
                jsString(raw)?.let(MovistarClient::decodeGuardString)?.let {
                    PrefsKeyCache(applicationContext).put(MovistarClient.GUARD_KEY, it)
                }
            }
            // Algunas versiones del portal dejan el token en una variable global en lugar de la URL.
            view.evaluateJavascript(
                "typeof p_id !== 'undefined' ? String(p_id) : (new URLSearchParams(location.search).get('p_id') || '')",
            ) { raw -> jsString(raw)?.let(::deliver) }
        }
    }

    private fun captureFrom(uri: Uri?) {
        val token = runCatching { uri?.getQueryParameter("p_id") }.getOrNull()
        if (!token.isNullOrBlank()) deliver(token)
    }

    private fun deliver(token: String) {
        if (token.isBlank() || !captured.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token.trim()))
            finish()
        }
    }

    /** evaluateJavascript devuelve el resultado como literal JSON ("\"abc\""): se decodifica. */
    private fun jsString(raw: String?): String? = runCatching { (Json.parseToJsonElement(raw ?: "") as JsonPrimitive).content }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val EXTRA_TOKEN = "movistar_token"
        private const val LOGIN_URL = "https://mi.movistar.com.mx/login"
    }
}
