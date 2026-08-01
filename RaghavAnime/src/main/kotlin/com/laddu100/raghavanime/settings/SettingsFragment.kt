package com.laddu100.raghavanime.settings

import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity

class SettingsFragment : DialogFragment {

    constructor() : super()

    private val cBg = Color.parseColor("#0A0A0A")
    private val cCard = Color.parseColor("#1A1A1A")
    private val cBorder = Color.parseColor("#2A2A2A")
    private val cAccent = Color.parseColor("#FF1744")
    private val cText = Color.parseColor("#FFFFFF")
    private val cTextSub = Color.parseColor("#9E9E9E")
    private val cTextDim = Color.parseColor("#616161")
    private val cWarning = Color.parseColor("#FFA726")

    companion object {
        private const val PREFIX = "raghavanime_"

        val providers = listOf(
            "miruro" to "Miruro",
            "anisuge" to "AniSuge",
            "aniwaves" to "AniWaves",
            "anikai" to "AniKai",
            "anidb" to "AniDB",
            "anikage" to "AniKage",
            "anineko" to "Anineko",
            "twodhive" to "2DHive",
            "anikoto" to "AniKoto",
            "enma" to "Enma",
            "animo" to "Animo",
            "anidap" to "Anidap",
            "senshi" to "Senshi",
            "aninami" to "AniNami",
            "anidao" to "AniDao",
            "anichan" to "AniChan"
        )

        fun isEnabled(key: String): Boolean = getKey<Boolean>(PREFIX + key) ?: true
        fun setEnabled(key: String, enabled: Boolean) { setKey(PREFIX + key, enabled) }
        fun isSubDLEnabled(): Boolean = getKey<Boolean>(PREFIX + "subdl") ?: false
        fun setSubDLEnabled(enabled: Boolean) { setKey(PREFIX + "subdl", enabled) }
        fun isSmartSortEnabled(): Boolean = getKey<Boolean>(PREFIX + "smartsort") ?: true
        fun setSmartSortEnabled(enabled: Boolean) { setKey(PREFIX + "smartsort", enabled) }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val maxW = (420 * dm.density).toInt()
            val w = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.92f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 24.dp())
            setBackgroundColor(cBg)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "RAGHAVANIME"
            textSize = 24f; setTextColor(cAccent); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f
        })
        root.addView(TextView(ctx).apply {
            text = "Tap a section to configure"
            textSize = 12f; setTextColor(cTextDim); gravity = Gravity.CENTER
            setPadding(0, 2.dp(), 0, 16.dp())
        })

        root.addView(sectionButton(ctx, "SOURCES", "Enable or disable anime providers", density) {
            showSourcesDialog(ctx, density)
        })

        root.addView(sectionButton(ctx, "EXPERIMENTAL", "SubDL subtitles & smart sorting", density) {
            showExperimentalDialog(ctx, density)
        })

        root.addView(Button(ctx).apply {
            text = "SAVE & RESTART"
            setTextColor(cAccent); textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16.dp(), 0, 0)
            setOnClickListener { showRestartDialog(ctx) }
        })

        return scroll
    }

    private fun sectionButton(
        ctx: Context, title: String, subtitle: String, density: Float, onClick: () -> Unit
    ): LinearLayout {
        fun Int.dp() = (this * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply {
                setStroke(1, cBorder); cornerRadius = 14 * density; setColor(cCard)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
            isClickable = true; isFocusable = true

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title; textSize = 16f; setTextColor(cText)
                setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f
            })
            col.addView(TextView(ctx).apply {
                text = subtitle; textSize = 12f; setTextColor(cTextDim)
                setPadding(0, 2.dp(), 0, 0)
            })
            addView(col)
            addView(TextView(ctx).apply { text = ">"; textSize = 20f; setTextColor(cAccent) })
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSourcesDialog(ctx: Context, density: Float) {
        fun Int.dp() = (this * density).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply {
            text = "Sources"; textSize = 18f; setTextColor(cAccent)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12.dp())
        })

        for ((key, name) in providers) {
            layout.addView(toggleRow(ctx, name, isEnabled(key), density) { checked ->
                setEnabled(key, checked)
            })
        }
        scroll.addView(layout)

        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Save") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent)
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim)
            }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showExperimentalDialog(ctx: Context, density: Float) {
        fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply {
            text = "Experimental Features"; textSize = 18f; setTextColor(cAccent)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12.dp())
        })

        layout.addView(toggleRow(ctx, "Smart Source Priority", isSmartSortEnabled(), density) { checked ->
            setSmartSortEnabled(checked)
        })

        layout.addView(toggleRow(ctx, "SubDL English Subtitles", isSubDLEnabled(), density) { checked ->
            setSubDLEnabled(checked)
        })

        layout.addView(TextView(ctx).apply {
            text = "  Experimental - may not work properly"
            setTextColor(cWarning); textSize = 11f
            setPadding(16.dp(), 2.dp(), 16.dp(), 12.dp())
        })

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Save") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent)
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim)
            }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(
        ctx: Context, label: String, checked: Boolean, density: Float, onChange: (Boolean) -> Unit
    ): LinearLayout {
        fun Int.dp() = (this * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 10.dp())

            addView(TextView(ctx).apply {
                text = label; textSize = 15f; setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(SwitchCompat(ctx).apply {
                isChecked = checked
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(cAccent, Color.parseColor("#333333"))
                )
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#666666"))
                )
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }
    }

    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Restart Required")
            .setMessage("Changes have been saved. Restart the app to apply them?")
            .setPositiveButton("Yes") { _, _ -> restartApp() }
            .setNegativeButton("No") { _, _ ->
                try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {}
            }
            .show()
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        } catch (_: Throwable) {
            try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {}
        }
    }
}
