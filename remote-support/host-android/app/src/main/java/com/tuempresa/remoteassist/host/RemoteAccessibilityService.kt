package com.tuempresa.remoteassist.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class RemoteAccessibilityService : AccessibilityService() {
    companion object { var instance: RemoteAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun gesture(x1: Float, y1: Float, x2: Float?, y2: Float?, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x2 != null && y2 != null) lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    private fun setText(f: (String) -> String) {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val a = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f(n.text?.toString() ?: ""))
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)
    }

    fun handle(j: JSONObject) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        fun coordinate(key: String): Float? {
            val value = j.optDouble(key, Double.NaN)
            return if (value.isFinite() && value in 0.0..1.0) value.toFloat() else null
        }
        when (j.getString("t")) {
            "tap" -> {
                val x = coordinate("x"); val y = coordinate("y")
                if (x != null && y != null) gesture(x * w, y * h, null, null, 50)
            }
            "swipe" -> {
                val x1 = coordinate("x1"); val y1 = coordinate("y1")
                val x2 = coordinate("x2"); val y2 = coordinate("y2")
                if (x1 != null && y1 != null && x2 != null && y2 != null)
                    gesture(x1 * w, y1 * h, x2 * w, y2 * h, j.optLong("ms", 300).coerceIn(80, 1500))
            }
            "scroll" -> {
                val up = j.getInt("dy") > 0
                gesture(w / 2, h * (if (up) 0.7f else 0.3f), w / 2, h * (if (up) 0.3f else 0.7f), 250)
            }
            "nav" -> performGlobalAction(when (j.getString("k")) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                else -> GLOBAL_ACTION_RECENTS
            })
            "text" -> {
                val s = j.optString("s")
                if (s.length <= 128) setText { it + s }
            }
            "del" -> setText {
                val count = it.codePointCount(0, it.length)
                if (count == 0) it else it.substring(0, it.offsetByCodePoints(0, count - 1))
            }
        }
    }
}
