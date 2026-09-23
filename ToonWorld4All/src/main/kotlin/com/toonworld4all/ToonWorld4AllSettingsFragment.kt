package com.toonworld4all

import android.annotation.SuppressLint
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ToonWorld4AllSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

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

        fun cfSaved(): Boolean = Tw4aCFStore.getSession("toonworld4all.me") != null ||
                Tw4aCFStore.getSession("archive.toonworld4all.me") != null

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(ctx).apply {
            text = "ToonWorld4All Settings"
            textSize = 20f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        })

        root.addView(TextView(ctx).apply {
            text = "If the site shows a \"Just a moment\" screen, tap Bypass Cloudflare to solve the challenge. Cookies are saved for 15 hours for each site that needs them."
            textSize = 13f; setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        })

        val bypassBtn = Button(ctx).apply {
            text = if (cfSaved()) "CF Cookies Saved" else "Bypass Cloudflare"
            background = makeBg(0xFF6D5ACF.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(bypassBtn)

        val clearBtn = Button(ctx).apply {
            text = "Clear CF Cookies"
            background = makeBg(0xFFE5484D.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(clearBtn)

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"
            background = makeBg(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)

        bypassBtn.setOnClickListener {
            Toast.makeText(ctx, "Solving toonworld4all.me...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                val ok = showTw4aCFBypassDialogAndWait("https://toonworld4all.me/")
                if (ok) {
                    showTw4aCFBypassDialogAndWait("https://archive.toonworld4all.me/")
                }
                bypassBtn.text = if (cfSaved()) "CF Cookies Saved" else "Bypass Cloudflare"
                Toast.makeText(
                    ctx,
                    if (ok) "Cloudflare solved" else "Cloudflare not solved",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        clearBtn.setOnClickListener {
            Tw4aCFStore.clearAll()
            bypassBtn.text = "Bypass Cloudflare"
            Toast.makeText(ctx, "Cookies cleared", Toast.LENGTH_SHORT).show()
        }

        saveBtn.setOnClickListener {
            dismiss()
        }

        return root
    }

    private fun makeBg(color: Int): GradientDrawable {
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = 12f
        return bg
    }
}
