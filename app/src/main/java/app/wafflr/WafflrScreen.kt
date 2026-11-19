package app.wafflr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Shared look and helpers for Wafflr's own screens. */
abstract class WafflrScreen : Activity() {
    protected val app get() = WafflrApp.instance
    protected val prefs get() = app.prefs
    protected val dp get() = resources.displayMetrics.density

    protected fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WafflrService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    // --- small view helpers shared by the screens ------------------------------------------------------------------
    protected fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
    }

    protected fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    protected fun mono(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    protected fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    protected fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

}
