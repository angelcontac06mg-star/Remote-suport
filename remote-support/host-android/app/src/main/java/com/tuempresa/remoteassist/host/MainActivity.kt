package com.tuempresa.remoteassist.host

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val signalingUrl = BuildConfig.SIGNALING_URL
    private lateinit var codeTv: TextView
    private lateinit var statusTv: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 120, 56, 56) }
        l.addView(TextView(this).apply { text = "RemoteAssist"; textSize = 24f })
        val btn = Button(this).apply { text = "Iniciar sesión de soporte" }
        l.addView(btn)
        codeTv = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER; setPadding(0, 64, 0, 16) }
        statusTv = TextView(this)
        l.addView(codeTv); l.addView(statusTv)
        setContentView(l)

        btn.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }
        ScreenCaptureService.onCode = { c ->
            runOnUiThread {
                codeTv.text = c
                statusTv.text = "Dale este código a quien te ayuda"
                if (!accessibilityOn()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        ScreenCaptureService.onStatus = { s -> runOnUiThread { statusTv.text = s } }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java)
                .putExtra("data", data).putExtra("url", signalingUrl))
        } else statusTv.text = "Necesitas aceptar compartir pantalla"
    }

    private fun accessibilityOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    override fun onDestroy() {
        ScreenCaptureService.onCode = null
        ScreenCaptureService.onStatus = null
        super.onDestroy()
    }
}
