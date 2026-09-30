package com.tuempresa.remoteassist.host

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val signalingUrl = BuildConfig.SIGNALING_URL
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var accessView: TextView
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var copyButton: TextView
    private lateinit var shareButton: TextView

    private val navy = Color.rgb(23, 59, 103)
    private val ink = Color.rgb(28, 39, 56)
    private val muted = Color.rgb(102, 116, 136)
    private val teal = Color.rgb(36, 165, 157)
    private val canvas = Color.rgb(245, 248, 252)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(createScreen())
    }

    private fun createScreen(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(canvas); isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
        }
        scroll.addView(page)

        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = label("RA", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(navy, 16)
        }
        brand.addView(mark, LinearLayout.LayoutParams(dp(52), dp(52)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("RemoteAssist", 19f, ink, true))
            addView(label("ASISTENCIA REMOTA SEGURA", 10f, muted, true).apply { letterSpacing = .08f })
        }
        brand.addView(brandText)
        page.addView(brand)

        page.addView(label("Tu teléfono,\nconectado a tu manera", 29f, ink, true).apply {
            setPadding(0, dp(34), 0, dp(8)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        page.addView(label("Inicia una sesión cuando estés listo. Tú decides cuándo compartir tu pantalla.", 15f, muted).apply {
            setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, 0, 0, dp(22))
        })

        val statusCard = card()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = label("●", 14f, teal, true)
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)))
        val statusColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusColumn.addView(label("Estado de la sesión", 12f, muted))
        statusView = label("Listo para empezar", 15f, ink, true)
        statusColumn.addView(statusView)
        statusRow.addView(statusColumn, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(statusRow)
        page.addView(statusCard, marginBottom(16))

        val codeCard = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        codeCard.addView(label("CÓDIGO DE CONEXIÓN", 11f, muted, true).apply { letterSpacing = .1f })
        codeView = label("— — —   — — —", 30f, navy, true).apply {
            gravity = Gravity.CENTER; letterSpacing = .09f; setPadding(0, dp(12), 0, dp(4))
        }
        codeCard.addView(codeView)
        codeCard.addView(label("Android genera el código. Compártelo tú con la persona en la PC.", 13f, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f) })
        copyButton = actionButton("Copiar código", false).apply {
            setOnClickListener {
                val code = ScreenCaptureService.currentCode
                if (!code.isNullOrBlank()) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Código RemoteAssist", code))
                    statusView.text = "Código copiado"
                }
            }
        }
        copyButton.isEnabled = false
        copyButton.alpha = .5f
        shareButton = actionButton("Compartir código", true).apply {
            setOnClickListener {
                val code = ScreenCaptureService.currentCode
                if (!code.isNullOrBlank()) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Mi código RemoteAssist es $code. Escríbelo en el visor de la PC para conectarte a mi teléfono.")
                    }
                    startActivity(Intent.createChooser(share, "Comparte el código con la persona de confianza"))
                }
            }
        }
        shareButton.isEnabled = false
        shareButton.alpha = .5f
        val codeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(shareButton, LinearLayout.LayoutParams(0, -2, 1f))
        }
        codeCard.addView(codeActions, marginTop(14))
        page.addView(codeCard, marginBottom(18))

        startButton = actionButton("Solicitar permiso para compartir pantalla", true).apply {
            setOnClickListener {
                isEnabled = false
                alpha = .75f
                statusView.text = "Esperando permiso para compartir pantalla…"
                try {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
                } catch (_: Exception) {
                    isEnabled = true; alpha = 1f
                    statusView.text = "No se pudo abrir el permiso de Android"
                }
            }
        }
        page.addView(startButton, marginBottom(10))
        stopButton = actionButton("Detener sesión", false).apply {
            visibility = View.GONE
            setOnClickListener { stopSession() }
        }
        page.addView(stopButton, marginBottom(18))

        val accessCard = card()
        accessCard.addView(label("Control remoto · opcional", 16f, ink, true))
        accessCard.addView(label("Para ver la pantalla no tienes que entrar aquí. Android exige activar Accesibilidad manualmente solo si también quieres permitir toques desde la PC.", 13f, muted).apply {
            setPadding(0, dp(6), 0, dp(12)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        accessView = actionButton("Abrir ajustes de Accesibilidad", false).apply {
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { statusView.text = "Abre Accesibilidad desde Ajustes del teléfono" }
            }
        }
        accessCard.addView(accessView)
        page.addView(accessCard, marginBottom(16))

        page.addView(label("PRIVACIDAD", 10f, teal, true).apply { letterSpacing = .12f })
        page.addView(label("Android te pedirá permiso cada vez que compartas la pantalla. Puedes detener la sesión desde aquí o desde la notificación.", 12f, muted).apply {
            setPadding(0, dp(6), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
        })
        return scroll
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureService.onCode = { c -> runOnUiThread { showCode(c) } }
        ScreenCaptureService.onStatus = { s -> runOnUiThread { showStatus(s) } }
        showCode(ScreenCaptureService.currentCode.orEmpty())
        showStatus(ScreenCaptureService.currentStatus)
        updateAccessButton()
    }

    override fun onStop() {
        ScreenCaptureService.onCode = null
        ScreenCaptureService.onStatus = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::accessView.isInitialized) updateAccessButton()
        if (::startButton.isInitialized) {
            val active = ScreenCaptureService.sessionActive
            startButton.visibility = if (active) View.GONE else View.VISIBLE
            startButton.isEnabled = !active
            stopButton.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    @Deprecated("Android media projection consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                    .putExtra("data", data).putExtra("url", signalingUrl)
                startForegroundService(serviceIntent)
                stopButton.visibility = View.VISIBLE
                startButton.visibility = View.GONE
                showStatus("Conectando de forma segura…")
            } catch (_: Exception) {
                startButton.isEnabled = true; startButton.alpha = 1f
                showStatus("No se pudo iniciar. Cierra otras sesiones e inténtalo de nuevo.")
            }
        } else {
            startButton.isEnabled = true; startButton.alpha = 1f
            showStatus("Permiso no concedido. La pantalla no se compartió.")
        }
    }

    private fun showCode(code: String) {
        if (!::codeView.isInitialized) return
        ScreenCaptureService.currentCode = code.takeIf { it.isNotBlank() }
        if (code.isBlank()) {
            codeView.text = "— — —   — — —"
            copyButton.isEnabled = false; copyButton.alpha = .5f
            shareButton.isEnabled = false; shareButton.alpha = .5f
            return
        }
        codeView.text = "${code.take(3)}   ${code.drop(3)}"
        statusView.text = "Compártelo tú con la persona que verá el teléfono en la PC"
        copyButton.isEnabled = true; copyButton.alpha = 1f
        shareButton.isEnabled = true; shareButton.alpha = 1f
    }

    private fun showStatus(status: String) {
        if (!::statusView.isInitialized) return
        ScreenCaptureService.currentStatus = status
        statusView.text = status
        if (!ScreenCaptureService.sessionActive && (status.contains("terminad", true) || status.startsWith("No se pudo iniciar", true))) {
            startButton.visibility = View.VISIBLE; startButton.isEnabled = true; startButton.alpha = 1f
            stopButton.visibility = View.GONE
            codeView.text = "— — —   — — —"
            copyButton.isEnabled = false; copyButton.alpha = .5f
            shareButton.isEnabled = false; shareButton.alpha = .5f
            ScreenCaptureService.currentCode = null
        }
    }

    private fun updateAccessButton() {
        if (!::accessView.isInitialized) return
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        accessView.text = if (enabled) "Accesibilidad activada ✓" else "Abrir ajustes de Accesibilidad"
        accessView.alpha = if (enabled) .8f else 1f
    }

    private fun stopSession() {
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        startButton.visibility = View.VISIBLE; startButton.isEnabled = true; startButton.alpha = 1f
        stopButton.visibility = View.GONE
        codeView.text = "— — —   — — —"
        copyButton.isEnabled = false; copyButton.alpha = .5f
        shareButton.isEnabled = false; shareButton.alpha = .5f
        ScreenCaptureService.currentCode = null
        ScreenCaptureService.currentStatus = "Sesión finalizada"
        showStatus("Sesión finalizada")
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = shape(Color.WHITE, 20, Color.rgb(231, 237, 245))
        elevation = dp(2).toFloat()
    }

    private fun actionButton(text: String, primary: Boolean) = TextView(this).apply {
        this.text = text; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER; minHeight = dp(50); setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) Color.WHITE else navy)
        background = shape(if (primary) navy else Color.rgb(237, 242, 249), 14)
        isClickable = true; isFocusable = true
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun marginTop(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }

    private fun accessibilityOn(): Boolean = Settings.Secure.getString(contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true

    companion object { private const val REQUEST_CAPTURE = 100 }
}
