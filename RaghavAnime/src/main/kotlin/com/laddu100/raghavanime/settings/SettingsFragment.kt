package com.laddu100.raghavanime.settings

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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.laddu100.raghavanime.RaghavAnimeFeatures
import com.laddu100.raghavanime.RaghavAnimeFeatures.CustomProfile

class SettingsFragment : DialogFragment() {


    private val cBg = Color.parseColor("#0A0A0A")
    private val cCard = Color.parseColor("#1A1A1A")
    private val cBorder = Color.parseColor("#2A2A2A")
    private val cAccent = Color.parseColor("#FF1744")
    private val cText = Color.parseColor("#FFFFFF")
    private val cTextSub = Color.parseColor("#9E9E9E")
    private val cTextDim = Color.parseColor("#616161")
    private val cWarning = Color.parseColor("#FF5252")

    companion object {
        private const val PREFIX = "raghavanime_"

        val providers = listOf(
            "miruro" to "Miruro", "anisuge" to "AniSuge", "aniwaves" to "AniWaves",
            "anikai" to "AniKai", "anidb" to "AniDB", "anikage" to "AniKage",
            "anineko" to "Anineko", "twodhive" to "2DHive", "anikoto" to "AniKoto",
            "enma" to "Enma", "animo" to "Animo", "anidap" to "Anidap",
            "senshi" to "Senshi", "aninami" to "AniNami", "anidao" to "AniDao",
            "anichan" to "AniChan"
        )

        fun isEnabled(key: String): Boolean = getKey<Boolean>(PREFIX + key) ?: true
        fun setEnabled(key: String, enabled: Boolean) { setKey(PREFIX + key, enabled) }
        fun isSubDLEnabled(): Boolean = getKey<Boolean>(PREFIX + "subdl") ?: false
        fun setSubDLEnabled(enabled: Boolean) { setKey(PREFIX + "subdl", enabled) }
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 24.dp()); setBackgroundColor(cBg)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "RAGHAVANIME"; textSize = 24f; setTextColor(cAccent); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f
        })
        root.addView(TextView(ctx).apply {
            text = "Tap a section to configure"; textSize = 12f; setTextColor(cTextDim)
            gravity = Gravity.CENTER; setPadding(0, 2.dp(), 0, 16.dp())
        })

        root.addView(sectionBtn(ctx, "SOURCES", "Enable or disable anime providers", d) { showSourcesDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "CUSTOM PROFILES", "Create up to 3 source profiles", d) { showProfilesDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "EXPERIMENTAL", "SubDL, recommendations, watch time", d) { showExperimentalDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "WATCH TIME STATS", "View your watching statistics", d) { showWatchTimeDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "DISCOVER ANIME", "Find new anime by genre & sort", d) { showDiscoverDialog(ctx, d) })

        root.addView(Button(ctx).apply {
            text = "SAVE & RESTART"; setTextColor(cAccent); textSize = 14f
            setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16.dp(), 0, 0); setOnClickListener { showRestartDialog(ctx) }
        })
        return scroll
    }

    private fun sectionBtn(ctx: Context, title: String, subtitle: String, d: Float, onClick: () -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 14 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp() }
            isClickable = true; isFocusable = true
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD) })
            col.addView(TextView(ctx).apply { text = subtitle; textSize = 12f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) })
            addView(col); addView(TextView(ctx).apply { text = ">"; textSize = 20f; setTextColor(cAccent) })
            setOnClickListener { onClick() }
        }
    }

    // =================== SOURCES ===================

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSourcesDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Sources"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })

        layout.addView(Button(ctx).apply {
            text = "ENABLE ALL"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 8.dp())
            setOnClickListener {
                for ((key, _) in providers) setEnabled(key, true)
                Toast.makeText(ctx, "All sources enabled", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
                showSourcesDialog(ctx, d)
            }
        })

        for ((key, name) in providers) {
            layout.addView(toggleRow(ctx, name, isEnabled(key), d) { setEnabled(key, it) })
        }
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Save") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    // =================== CUSTOM PROFILES ===================

    private fun showProfilesDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Custom Source Profiles"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply {
            text = "Create up to 3 profiles. When active, a profile overrides sources selected above."
            textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 12.dp())
        })

        val profiles = RaghavAnimeFeatures.getCustomProfiles()
        for (profile in profiles) {
            layout.addView(profileRow(ctx, profile, d))
        }

        if (profiles.size < 3) {
            layout.addView(Button(ctx).apply {
                text = "+ CREATE NEW PROFILE"; setTextColor(cAccent); textSize = 13f
                setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 8.dp(), 0, 8.dp())
                setOnClickListener { showCreateProfileDialog(ctx, d) }
            })
        }

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Done") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun profileRow(ctx: Context, profile: CustomProfile, d: Float): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 10.dp(), 8.dp(), 10.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 10 * d; setColor(cCard) }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = 6.dp(); layoutParams = params

            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(ctx).apply {
                text = if (profile.enabled) "${profile.name} (ACTIVE)" else profile.name
                textSize = 14f; setTextColor(if (profile.enabled) cAccent else cText)
                setTypeface(typeface, Typeface.BOLD)
            })
            info.addView(TextView(ctx).apply {
                text = "${profile.sources.size} sources"
                textSize = 11f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0)
            })
            addView(info)

            addView(SwitchCompat(ctx).apply {
                isChecked = profile.enabled
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        val activeCount = RaghavAnimeFeatures.getCustomProfiles().count { it.enabled }
                        if (activeCount >= 1) {
                            showSingleProfileWarning(ctx)
                        }
                        RaghavAnimeFeatures.setActiveProfile(profile.name)
                    } else {
                        RaghavAnimeFeatures.setActiveProfile(null)
                    }
                    dialog?.dismiss()
                    showProfilesDialog(ctx, d)
                }
            })

            addView(ImageButton(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(cWarning)
                setOnClickListener {
                    val updated = RaghavAnimeFeatures.getCustomProfiles().filter { it.name != profile.name }
                    RaghavAnimeFeatures.saveCustomProfiles(updated)
                    Toast.makeText(ctx, "Profile deleted", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    showProfilesDialog(ctx, d)
                }
            })
        }
    }

    private fun showSingleProfileWarning(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("WARNING")
            .setMessage("Select only one profile.\n\nOther profiles have been turned off.")
            .setPositiveButton("OK") { _, _ ->
                RaghavAnimeFeatures.getCustomProfiles().forEach { p ->
                    if (p.enabled) RaghavAnimeFeatures.setActiveProfile(null)
                }
            }
            .show()
    }

    private fun showCreateProfileDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Profile Name"; textSize = 14f; setTextColor(cAccent); setPadding(0, 0, 0, 4.dp()) })
        val nameInput = EditText(ctx).apply {
            hint = "Enter profile name"; setHintTextColor(cTextDim); setTextColor(cText)
            setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        layout.addView(nameInput)
        layout.addView(TextView(ctx).apply { text = "Select Sources"; textSize = 14f; setTextColor(cAccent); setPadding(0, 12.dp(), 0, 4.dp()) })

        val checksContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableMapOf<String, CheckBox>()
        for ((key, name) in providers) {
            val cb = CheckBox(ctx).apply {
                text = name; setTextColor(cText); textSize = 13f
                setOnCheckedChangeListener { _, _ -> }
            }
            checks[key] = cb
            checksContainer.addView(cb)
        }
        val checksScroll = ScrollView(ctx).apply {
            addView(checksContainer)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300.dp())
        }
        layout.addView(checksScroll)

        AlertDialog.Builder(ctx).setView(layout)
            .setTitle("Create Profile")
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                if (name.isBlank()) { Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val selectedSources = checks.filter { it.value.isChecked }.keys.toList()
                if (selectedSources.isEmpty()) { Toast.makeText(ctx, "Select at least 1 source", Toast.LENGTH_SHORT).show(); return@setPositiveButton }

                AlertDialog.Builder(ctx)
                    .setTitle("WARNING")
                    .setMessage("This profile overwrites the sources selected in the Sources tab.\nWhen this profile is active, only the sources selected here will run.\n\nDo you want to continue?")
                    .setPositiveButton("Yes, Save") { _, _ ->
                        val profiles = RaghavAnimeFeatures.getCustomProfiles().toMutableList()
                        profiles.add(CustomProfile(name, selectedSources, false))
                        RaghavAnimeFeatures.saveCustomProfiles(profiles)
                        Toast.makeText(ctx, "Profile '$name' created", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                        showProfilesDialog(ctx, d)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    // =================== EXPERIMENTAL ===================

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showExperimentalDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Experimental Features"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "May not work properly"; textSize = 11f; setTextColor(cWarning); setPadding(0, 0, 0, 12.dp()) })

        layout.addView(sectionLabel(ctx, "SUBTITLES", d))
        layout.addView(toggleRow(ctx, "SubDL English Subtitles", isSubDLEnabled(), d) { setSubDLEnabled(it) })
        layout.addView(descText(ctx, "Fetches English subtitles from SubDL", d))

        layout.addView(sectionLabel(ctx, "WATCH TRACKING", d))
        layout.addView(toggleRow(ctx, "Watch Time Tracker", RaghavAnimeFeatures.isEnabled("watch_time"), d) { RaghavAnimeFeatures.setEnabled("watch_time", it) })
        layout.addView(descText(ctx, "Tracks watch time per anime, view in Watch Time Stats", d))

        layout.addView(sectionLabel(ctx, "DISCOVERY", d))
        layout.addView(toggleRow(ctx, "Anime Recommendations", RaghavAnimeFeatures.isEnabled("recommendations"), d) { RaghavAnimeFeatures.setEnabled("recommendations", it) })
        layout.addView(descText(ctx, "Shows recommended anime based on watch history", d))

        layout.addView(Button(ctx).apply {
            text = "RESET RECOMMENDATIONS"; setTextColor(cWarning); textSize = 12f
            setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8.dp(), 0, 8.dp())
            setOnClickListener {
                RaghavAnimeFeatures.resetRecommendations()
                Toast.makeText(ctx, "Recommendations reset", Toast.LENGTH_SHORT).show()
            }
        })

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Save") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    // =================== WATCH TIME STATS ===================

    private fun showWatchTimeDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val history = RaghavAnimeFeatures.getWatchHistory().sortedByDescending { it.watchTimeMs }
        val totalTime = RaghavAnimeFeatures.getTotalWatchTime()
        val totalEps = RaghavAnimeFeatures.getTotalEpisodesWatched()
        val animeCount = RaghavAnimeFeatures.getAnimeWatchedCount()

        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }

        layout.addView(TextView(ctx).apply { text = "Watch Time Statistics"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12.dp()) })

        // Summary card
        val summary = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 12 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp() }
        }
        summary.addView(TextView(ctx).apply { text = "Total Watch Time: ${RaghavAnimeFeatures.formatWatchTime(totalTime)}"; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        summary.addView(TextView(ctx).apply { text = "Episodes Watched: $totalEps"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 2.dp(), 0, 0) })
        summary.addView(TextView(ctx).apply { text = "Anime Started: $animeCount"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 2.dp(), 0, 0) })
        layout.addView(summary)

        if (history.isEmpty()) {
            layout.addView(TextView(ctx).apply {
                text = "No watch history yet.\nStart watching anime to see stats here."; textSize = 14f; setTextColor(cTextDim)
                gravity = Gravity.CENTER; setPadding(0, 24.dp(), 0, 24.dp())
            })
        } else {
            layout.addView(sectionLabel(ctx, "TOP ANIME BY WATCH TIME", d))
            for ((index, entry) in history.take(20).withIndex()) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                    background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 8 * d; setColor(cCard) }
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.bottomMargin = 4.dp(); layoutParams = params
                }
                row.addView(TextView(ctx).apply { text = "${index + 1}."; textSize = 14f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 8.dp(), 0) })
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                col.addView(TextView(ctx).apply { text = entry.title; textSize = 14f; setTextColor(cText); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(TextView(ctx).apply {
                    text = "${entry.episodesWatched} eps - ${RaghavAnimeFeatures.formatWatchTime(entry.watchTimeMs)}"
                    textSize = 11f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0)
                })
                row.addView(col)
                layout.addView(row)
            }
        }

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Close") { _, _ -> }
            .create().apply { show(); styleButtons() }
    }

    // =================== DISCOVER ANIME ===================

    private fun showDiscoverDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Discover New Anime"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12.dp()) })

        // Genre spinner
        layout.addView(TextView(ctx).apply { text = "Genre"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 2.dp()) })
        val genreSpinner = Spinner(ctx).apply {
            adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableGenres)
        }
        layout.addView(genreSpinner)

        // Sort spinner
        layout.addView(TextView(ctx).apply { text = "Sort By"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val sortSpinner = Spinner(ctx).apply {
            adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableSorts.map { it.second })
        }
        layout.addView(sortSpinner)

        // Search input
        layout.addView(TextView(ctx).apply { text = "Search (optional)"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val searchInput = EditText(ctx).apply {
            hint = "Anime name for similar results"; setHintTextColor(cTextDim); setTextColor(cText)
            setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        }
        layout.addView(searchInput)

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Search") { _, _ ->
                val genre = RaghavAnimeFeatures.availableGenres[genreSpinner.selectedItemPosition]
                val sortBy = RaghavAnimeFeatures.availableSorts[sortSpinner.selectedItemPosition].first
                val query = searchInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                showDiscoverResults(ctx, d, query, genre, sortBy)
            }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun showDiscoverResults(ctx: Context, d: Float, query: String?, genre: String, sortBy: String) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg)
        }
        layout.addView(TextView(ctx).apply { text = "Searching..."; textSize = 14f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
        scroll.addView(layout)

        val dialog = AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Close") { _, _ -> }
            .create()
        dialog.show()

        Thread {
            try {
                val results = kotlinx.coroutines.runBlocking {
                    RaghavAnimeFeatures.discoverAnime(query, genre, sortBy)
                }
                requireActivity().runOnUiThread {
                    layout.removeAllViews()
                    layout.addView(TextView(ctx).apply {
                        text = if (results.isEmpty()) "No results found" else "Found ${results.size} anime"
                        textSize = 14f; setTextColor(cText); setPadding(0, 0, 0, 8.dp())
                    })
                    for (anime in results) {
                        layout.addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 8 * d; setColor(cCard) }
                            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            params.bottomMargin = 4.dp(); layoutParams = params
                            addView(TextView(ctx).apply {
                                text = anime.title; textSize = 14f; setTextColor(cText); maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            addView(TextView(ctx).apply { text = ">"; textSize = 16f; setTextColor(cAccent); setPadding(8.dp(), 0, 0, 0) })
                        })
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    layout.removeAllViews()
                    layout.addView(TextView(ctx).apply { text = "Error: ${e.message}"; textSize = 14f; setTextColor(cWarning) })
                }
            }
        }.start()
    }

    // =================== HELPERS ===================

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(ctx: Context, label: String, checked: Boolean, d: Float, onChange: (Boolean) -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 10.dp())
            addView(TextView(ctx).apply { text = label; textSize = 15f; setTextColor(cText); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(SwitchCompat(ctx).apply {
                isChecked = checked
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }
    }

    private fun sectionLabel(ctx: Context, text: String, d: Float): TextView {
        fun Int.dp() = (this * d).toInt()
        return TextView(ctx).apply { this.text = text; textSize = 12f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 12.dp(), 0, 4.dp()) }
    }

    private fun descText(ctx: Context, text: String, d: Float): TextView {
        fun Int.dp() = (this * d).toInt()
        return TextView(ctx).apply { this.text = "  $text"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp()) }
    }

    private fun darkAdapter(ctx: Context, items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) = (super.getView(position, convertView, parent) as TextView).apply { setTextColor(cText); textSize = 14f }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(cText); setBackgroundColor(cCard); setPadding(24, 20, 24, 20) }
        }
    }

    private fun AlertDialog.styleButtons() {
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim)
    }

    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx).setTitle("Restart Required").setMessage("Restart the app to apply changes?")
            .setPositiveButton("Yes") { _, _ -> restartApp() }
            .setNegativeButton("No") { _, _ -> try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} }
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
        } catch (_: Throwable) { try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} }
    }
}
