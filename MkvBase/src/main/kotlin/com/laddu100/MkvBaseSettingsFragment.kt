package com.laddu100

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// BottomSheet settings fragment with 3 actions: Bypass Cloudflare, Clear CF Cookies, Save.
// Built programmatically (no XML resources) so we don't need requiresResources=true and
// don't have to ship a compiled layout. This keeps the plugin .cs3 small and avoids
// resource-ID collisions with other plugins.
//
// Modeled on Cinemacity's SettingsFragment but simplified:
// - Bypass: clears stale CF cookies, opens the WebView CF solver, saves fresh cookies
// - Clear:  removes saved CF cookies + UA + host
// - Save:   no-op placeholder (settings auto-save); shows confirmation toast
class MkvBaseSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val TAG = "MkvBase_Settings"

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val smallPad = (8 * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        // Header
        val header = TextView(ctx).apply {
            text = "MkvBase Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(header)

        // Help text explaining what the buttons do
        val help = TextView(ctx).apply {
            text = "If MkvBase shows a \"Just a moment\" screen or returns no links, tap Bypass Cloudflare to open a WebView and solve the challenge. Cookies are saved automatically and reused for 15 hours."
            textSize = 13f
            setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(help)

        // Bypass button — dynamic label shows current cookie state
        val bypassBtn = Button(ctx).apply {
            text = if (MkvBasePlugin.cfCookies.isNotBlank()) {
                "✅ CF Cookies Saved — Refresh"
            } else {
                "🛡️ Bypass Cloudflare"
            }
            background = makeButtonBackground(0xFF6D5ACF.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(bypassBtn)

        // Clear button
        val clearBtn = Button(ctx).apply {
            text = "🗑️ Clear CF Cookies"
            background = makeButtonBackground(0xFFE5484D.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(clearBtn)

        // Save button (closes the sheet — settings auto-save)
        val saveBtn = Button(ctx).apply {
            text = "💾 Save & Close"
            background = makeButtonBackground(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)

        // --- Bypass button handler ---
        // Clears any stale cookies (both in CookieManager and in CloudStreamApp datastore),
        // then launches the WebView CF solver. When the solver finishes, the new cookies
        // are persisted by MkvBaseCFStore.save() inside the solver, AND mirrored to the
        // plugin Companion so the Settings button label updates correctly.
        bypassBtn.setOnClickListener {
            val host = MkvBasePlugin.cfCookieHost.ifBlank { "https://mkvbase.site" }
            try {
                val cm = android.webkit.CookieManager.getInstance()
                listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                    cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                cm.flush()
            } catch (e: Exception) {
                Log.e(TAG, "CookieManager clear: ${e.message}")
            }
            MkvBasePlugin.cfCookies = ""
            MkvBasePlugin.cfUserAgent = ""
            MkvBasePlugin.cfCookieHost = ""
            MkvBaseCFStore.clear()

            bypassBtn.text = "⏳ Solving..."
            // Launch the solver as a coroutine on the Main dispatcher. WebView requires the
            // main thread, and showMkvBaseCFBypassDialogAndWait already switches to
            // Dispatchers.Main internally. CoroutineScope(Dispatchers.Main).launch is the
            // simplest fire-and-forget pattern for a fragment without lifecycleScope access.
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val success = showMkvBaseCFBypassDialogAndWait(host)
                    bypassBtn.text = if (success && MkvBasePlugin.cfCookies.isNotBlank()) {
                        "✅ CF Cookies Saved — Refresh"
                    } else {
                        "🛡️ Bypass Cloudflare"
                    }
                    if (success) {
                        Toast.makeText(ctx, "CF cookies saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "CF bypass cancelled", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bypass dialog error: ${e.message}")
                    bypassBtn.text = "🛡️ Bypass Cloudflare"
                    Toast.makeText(ctx, "Bypass failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // --- Clear button handler ---
        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear CF Cookies?")
                .setMessage("This will remove the saved Cloudflare cookies and User-Agent. You will need to bypass Cloudflare again before links load.")
                .setPositiveButton("Clear") { _, _ ->
                    val host = MkvBasePlugin.cfCookieHost
                    if (host.isNotBlank()) {
                        try {
                            val cm = android.webkit.CookieManager.getInstance()
                            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                                cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                            }
                            cm.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "CookieManager clear: ${e.message}")
                        }
                    }
                    MkvBasePlugin.cfCookies = ""
                    MkvBasePlugin.cfUserAgent = ""
                    MkvBasePlugin.cfCookieHost = ""
                    MkvBaseCFStore.clear()
                    bypassBtn.text = "🛡️ Bypass Cloudflare"
                    Toast.makeText(ctx, "CF cookies cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
        }

        // --- Save button handler ---
        saveBtn.setOnClickListener {
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        return root
    }

    // Rounded-corner colored button background. Using a GradientDrawable programmatically
    // avoids needing XML drawable resources (which would require requiresResources=true
    // and a resource package).
    private fun makeButtonBackground(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(color)
        }
    }
}
