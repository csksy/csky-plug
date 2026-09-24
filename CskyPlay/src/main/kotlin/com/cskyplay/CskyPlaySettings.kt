package com.cskyplay

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
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.Plugin

class CskyPlaySettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

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

        fun siteEnabled(id: String): Boolean = try {
            CloudStreamApp.getKey<Boolean>("CSKYPLAY_SITE_$id") ?: true
        } catch (e: Exception) {
            true
        }

        fun setSiteEnabled(id: String, value: Boolean) {
            try {
                CloudStreamApp.setKey("CSKYPLAY_SITE_$id", value)
            } catch (e: Exception) {
            }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(ctx).apply {
            text = "CskyPlay Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        })

        root.addView(TextView(ctx).apply {
            text = "Toggle the download sources. All sites run in parallel when you open a movie or episode, so enabling more sites means more links but slightly more loading. If a site changes its domain, it can be updated from Firebase without updating the plugin."
            textSize = 13f
            setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        })

        val sites = listOf(
            Triple("vegamovies", "VegaMovies", "Fast direct download links"),
            Triple("hdhub4u", "HDHub4u", "Movies and series with watch online"),
            Triple("4khdhub", "4KHDHub", "4K UHD and pack downloads"),
            Triple("moviebox", "MovieBox", "Multi audio streaming"),
            Triple("netnaija", "NetNaija", "Direct mp4 streaming"),
            Triple("themoviesflix", "TheMoviesFlix", "Movies and web series"),
            Triple("multimovies", "Multimovies", "Streaming servers"),
            Triple("movies4u", "Movies4u", "Movies and series")
        )

        for ((id, label, desc) in sites) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, smallPad / 2, 0, smallPad / 2)
                background = makeBg(0xFF1E1E2A.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad / 2 }
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(ctx).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor("#9E9EAE"))
            })
            row.addView(textCol)

            val toggle = Button(ctx).apply {
                textSize = 12f
                setPadding(0, 0, 0, 0)
                minWidth = (72 * dp).toInt()
                minHeight = (36 * dp).toInt()
                setTextColor(Color.WHITE)
                val on = siteEnabled(id)
                text = if (on) "ON" else "OFF"
                background = makeBg(if (on) 0xFF2E7D32.toInt() else 0xFF3A3A48.toInt())
                layoutParams = LinearLayout.LayoutParams((88 * dp).toInt(), (40 * dp).toInt()).also {
                    it.leftMargin = smallPad
                }
                setOnClickListener {
                    val now = !siteEnabled(id)
                    setSiteEnabled(id, now)
                    text = if (now) "ON" else "OFF"
                    background = makeBg(if (now) 0xFF2E7D32.toInt() else 0xFF3A3A48.toInt())
                    Toast.makeText(ctx, "$label " + if (now) "enabled" else "disabled", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(toggle)
            root.addView(row)
        }

        root.addView(TextView(ctx).apply {
            text = "Domain overrides are read from the Firebase realtime database every 5 minutes. If a site changes its domain add its key there: cskyplay_vegamovies_url cskyplay_hdhub4u_url cskyplay_4khdhub_url cskyplay_moviebox_url cskyplay_netnaija_url cskyplay_themoviesflix_url cskyplay_multimovies_url cskyplay_movies4u_url with the full site address like https://movies4u.cr as the value"
            textSize = 11f
            setTextColor(Color.parseColor("#61616F"))
            setPadding(0, smallPad, 0, smallPad)
        })

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"
            textSize = 14f
            background = makeBg(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)

        saveBtn.setOnClickListener {
            try {
                MainActivity.reloadHomeEvent?.invoke(true)
            } catch (e: Exception) {
            }
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
