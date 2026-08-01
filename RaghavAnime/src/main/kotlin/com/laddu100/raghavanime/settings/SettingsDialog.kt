package com.laddu100.raghavanime.settings

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.laddu100.raghavanime.settings.SettingsTheme.dp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object SettingsDialog {

    private const val PREFIX = "raghavanime_"

    private val providers = listOf(
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
    fun setEnabled(key: String, enabled: Boolean) = setKey(PREFIX + key, enabled)
    fun isSubDLEnabled(): Boolean = getKey<Boolean>(PREFIX + "subdl") ?: false
    fun setSubDLEnabled(enabled: Boolean) = setKey(PREFIX + "subdl", enabled)
    fun isSmartSortEnabled(): Boolean = getKey<Boolean>(PREFIX + "smartsort") ?: true
    fun setSmartSortEnabled(enabled: Boolean) = setKey(PREFIX + "smartsort", enabled)
    fun getConcurrency(): Int = getKey<Int>(PREFIX + "concurrency") ?: 8
    fun setConcurrency(value: Int) = setKey(PREFIX + "concurrency", value)

    fun recordSourceSuccess(key: String) {
        val count = getKey<Int>(PREFIX + "success_$key") ?: 0
        setKey(PREFIX + "success_$key", count + 1)
    }

    fun getSourcePriority(key: String): Int = getKey<Int>(PREFIX + "success_$key") ?: 0

    fun show(context: Context) {
        val t = SettingsTheme
        val scroll = ScrollView(context).apply {
            background = t.roundRect(t.BG_DARK, 0f)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(context), 32.dp(context), 24.dp(context), 32.dp(context))
            background = t.roundRect(t.BG_DARK, 0f)
        }

        // Header
        root.addView(TextView(context).apply {
            text = "RaghavAnime"
            setTextColor(t.ACCENT_RED)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4.dp(context))
        })
        root.addView(TextView(context).apply {
            text = "Settings"
            setTextColor(t.TEXT_SECONDARY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24.dp(context))
        })

        // Section: Sources
        root.addView(sectionHeader(context, "SOURCES"))

        for ((key, name) in providers) {
            root.addView(toggleRow(context, name, isEnabled(key)) { checked ->
                setEnabled(key, checked)
            })
        }

        // Divider
        root.addView(divider(context))

        // Section: Features
        root.addView(sectionHeader(context, "FEATURES"))

        root.addView(toggleRow(context, "Smart Source Priority", isSmartSortEnabled()) { checked ->
            setSmartSortEnabled(checked)
        })

        root.addView(toggleRow(context, "SubDL English Subtitles", isSubDLEnabled()) { checked ->
            setSubDLEnabled(checked)
        })

        // Warning text for SubDL
        root.addView(TextView(context).apply {
            text = "  Experimental feature - may not work properly"
            setTextColor(t.WARNING)
            textSize = 11f
            setPadding(16.dp(context), 2.dp(context), 16.dp(context), 12.dp(context))
        })

        // Divider
        root.addView(divider(context))

        // Section: Performance
        root.addView(sectionHeader(context, "PERFORMANCE"))

        val concurrencyLabel = TextView(context).apply {
            text = "Concurrent Sources: ${getConcurrency()}"
            setTextColor(t.TEXT_PRIMARY)
            textSize = 14f
            setPadding(16.dp(context), 8.dp(context), 16.dp(context), 4.dp(context))
        }
        root.addView(concurrencyLabel)

        val seekBar = android.widget.SeekBar(context).apply {
            max = 16
            progress = getConcurrency()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val val_ = if (progress < 1) 1 else progress
                    concurrencyLabel.text = "Concurrent Sources: $val_"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    setConcurrency(if (seekBar!!.progress < 1) 1 else seekBar.progress)
                }
            })
            setPadding(16.dp(context), 0, 16.dp(context), 16.dp(context))
        }
        root.addView(seekBar)

        scroll.addView(root)

        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> }
            .create()
            .apply {
                window?.setBackgroundDrawable(t.roundRect(t.BG_DARK, 16f))
            }
            .show()
    }

    private fun sectionHeader(context: Context, title: String): TextView {
        val t = SettingsTheme
        return TextView(context).apply {
            text = title
            setTextColor(t.ACCENT_RED)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(16.dp(context), 16.dp(context), 16.dp(context), 8.dp(context))
        }
    }

    private fun toggleRow(
        context: Context,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val t = SettingsTheme
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp(context), 10.dp(context), 16.dp(context), 10.dp(context))
            gravity = Gravity.CENTER_VERTICAL
            background = t.roundRect(t.BG_CARD, 8f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2.dp(context), 0, 2.dp(context))
            layoutParams = params
        }

        val text = TextView(context).apply {
            text = label
            setTextColor(t.TEXT_PRIMARY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switch = Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }

        row.addView(text)
        row.addView(switch)
        return row
    }

    private fun divider(context: Context): View {
        val t = SettingsTheme
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp(context)).also {
                it.setMargins(0, 16.dp(context), 0, 8.dp(context))
            }
            setBackgroundColor(t.DIVIDER)
        }
    }
}
