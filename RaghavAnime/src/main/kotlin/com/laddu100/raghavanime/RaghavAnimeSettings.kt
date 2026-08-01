package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.CheckBox
import android.widget.ScrollView

object RaghavAnimeSettings {

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

    fun isEnabled(key: String): Boolean {
        return CloudStreamApp.getKey<Boolean>(PREFIX + key) ?: true
    }

    fun setEnabled(key: String, enabled: Boolean) {
        CloudStreamApp.setKey(PREFIX + key, enabled)
    }

    fun getConcurrency(): Int {
        return CloudStreamApp.getKey<Int>(PREFIX + "concurrency") ?: 8
    }

    fun setConcurrency(value: Int) {
        CloudStreamApp.setKey(PREFIX + "concurrency", value)
    }

    fun isSubDLEnabled(): Boolean {
        return CloudStreamApp.getKey<Boolean>(PREFIX + "subdl") ?: false
    }

    fun setSubDLEnabled(enabled: Boolean) {
        CloudStreamApp.setKey(PREFIX + "subdl", enabled)
    }

    fun isSmartSortEnabled(): Boolean {
        return CloudStreamApp.getKey<Boolean>(PREFIX + "smartsort") ?: true
    }

    fun setSmartSortEnabled(enabled: Boolean) {
        CloudStreamApp.setKey(PREFIX + "smartsort", enabled)
    }

    fun recordSourceSuccess(key: String) {
        val count = CloudStreamApp.getKey<Int>(PREFIX + "success_$key") ?: 0
        CloudStreamApp.setKey(PREFIX + "success_$key", count + 1)
    }

    fun getSourcePriority(key: String): Int {
        return CloudStreamApp.getKey<Int>(PREFIX + "success_$key") ?: 0
    }

    fun getSortedProviders(): List<Pair<String, String>> {
        val enabled = providers.filter { isEnabled(it.first) }
        if (!isSmartSortEnabled()) return enabled
        return enabled.sortedByDescending { getSourcePriority(it.first) }
    }

    fun showSettingsDialog(context: Context) {
        val activity = CommonActivity.activity ?: return

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundColor(Color.parseColor("#1A000000"))
        }

        val titleView = TextView(context).apply {
            text = "RaghavAnime Settings"
            setTextColor(Color.parseColor("#FF1744"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        container.addView(titleView)

        val sectionConcurrency = TextView(context).apply {
            text = "Concurrency: ${getConcurrency()}"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        container.addView(sectionConcurrency)

        val seekBar = SeekBar(context).apply {
            max = 16
            progress = getConcurrency()
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sectionConcurrency.text = "Concurrency: ${if (progress < 1) 1 else progress}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    setConcurrency(if (seekBar!!.progress < 1) 1 else seekBar.progress)
                }
            })
        }
        container.addView(seekBar)

        val smartSortSwitch = Switch(context).apply {
            text = "Smart Source Priority"
            setTextColor(Color.WHITE)
            isChecked = isSmartSortEnabled()
            setOnCheckedChangeListener { _, checked -> setSmartSortEnabled(checked) }
            setPadding(0, 24, 0, 8)
        }
        container.addView(smartSortSwitch)

        val subdlSwitch = Switch(context).apply {
            text = "SubDL English Subtitles"
            setTextColor(Color.WHITE)
            isChecked = isSubDLEnabled()
            setOnCheckedChangeListener { _, checked -> setSubDLEnabled(checked) }
            setPadding(0, 8, 0, 24)
        }
        container.addView(subdlSwitch)

        val sourcesHeader = TextView(context).apply {
            text = "Sources"
            setTextColor(Color.parseColor("#FF1744"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
        container.addView(sourcesHeader)

        for ((key, name) in providers) {
            val switch = Switch(context).apply {
                text = name
                setTextColor(Color.WHITE)
                isChecked = isEnabled(key)
                setOnCheckedChangeListener { _, checked -> setEnabled(key, checked) }
                setPadding(16, 8, 16, 8)
            }
            container.addView(switch)
        }

        val priorityInfo = TextView(context).apply {
            text = "\nSmart Priority tracks which sources successfully return links and tries them first."
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }
        container.addView(priorityInfo)

        scrollView.addView(container)

        AlertDialog.Builder(activity)
            .setView(scrollView)
            .setPositiveButton("Done") { _, _ -> }
            .create()
            .show()
    }
}
