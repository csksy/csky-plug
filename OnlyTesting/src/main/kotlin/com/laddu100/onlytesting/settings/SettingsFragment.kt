package com.laddu100.onlytesting.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.laddu100.onlytesting.OnlyTesting
import com.laddu100.onlytesting.RaghavAnimeFeatures
import com.laddu100.onlytesting.RaghavAnimeFeatures.CustomProfile
import com.laddu100.onlytesting.RaghavAnimeFeatures.DiscoverResult
import com.laddu100.onlytesting.RaghavAnimeFeatures.DiscoverPage
import com.laddu100.onlytesting.RaghavAnimeFeatures.AnimeDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        root.addView(TextView(ctx).apply { text = "RAGHAVANIME"; textSize = 24f; setTextColor(cAccent); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f })
        root.addView(TextView(ctx).apply { text = "Tap a section to configure"; textSize = 12f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 2.dp(), 0, 2.dp()) })
        root.addView(TextView(ctx).apply { text = "All features are experimental (may not work)"; textSize = 11f; setTextColor(Color.parseColor("#FFD54F")); gravity = Gravity.CENTER; setPadding(0, 0, 0, 16.dp()) })
        root.addView(sectionBtn(ctx, "SOURCES", "Enable or disable anime providers", d) { showSourcesDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "CUSTOM PROFILES", "Create up to 3 source profiles", d) { showProfilesDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "EXPERIMENTAL", "SubDL, recommendations, watch time", d) { showExperimentalDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "WATCH TIME STATS", "View your watching statistics", d) { showWatchTimeDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "DISCOVER ANIME", "Find new anime by genre & sort", d) { showDiscoverDialog(ctx, d) })
        root.addView(Button(ctx).apply { text = "SAVE & RESTART"; setTextColor(cAccent); textSize = 14f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 16.dp(), 0, 0); setOnClickListener { showRestartDialog(ctx) } })
        return scroll
    }

    private fun sectionBtn(ctx: Context, title: String, subtitle: String, d: Float, onClick: () -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 14 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp() }
            isClickable = true; isFocusable = true
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(ctx).apply { text = subtitle; textSize = 12f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) }) })
            addView(TextView(ctx).apply { text = ">"; textSize = 20f; setTextColor(cAccent) })
            setOnClickListener { onClick() }
        }
    }

    // =================== SOURCES ===================
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSourcesDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Sources"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })

        // Enable All button — at the TOP so it's always visible without scrolling
        // Keep references to all switches so Enable All can update them in-place
        val switches = mutableMapOf<String, SwitchCompat>()
        layout.addView(Button(ctx).apply { text = "ENABLE ALL"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 8.dp())
            setOnClickListener {
                for ((key, _) in providers) {
                    setEnabled(key, true)
                    switches[key]?.let { sw ->
                        sw.setOnCheckedChangeListener(null)
                        sw.isChecked = true
                        sw.setOnCheckedChangeListener { _, isChecked -> setEnabled(key, isChecked) }
                    }
                }
                Toast.makeText(ctx, "All sources enabled", Toast.LENGTH_SHORT).show()
            } })

        for ((key, name) in providers) {
            val sw = SwitchCompat(ctx).apply {
                isChecked = isEnabled(key)
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, isChecked -> setEnabled(key, isChecked) }
            }
            switches[key] = sw
            layout.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10.dp(), 0, 10.dp())
                addView(TextView(ctx).apply { text = name; textSize = 15f; setTextColor(cText); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(sw)
            })
        }
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Save") { _, _ -> }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    // =================== CUSTOM PROFILES ===================
    private fun showProfilesDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Custom Source Profiles"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "When active, a profile overrides sources selected above."; textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 12.dp()) })

        val profiles = RaghavAnimeFeatures.getCustomProfiles()
        for (profile in profiles) layout.addView(profileRow(ctx, profile, d, layout, scroll))
        if (profiles.size < 3) {
            layout.addView(Button(ctx).apply { text = "+ CREATE NEW PROFILE"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 8.dp())
                setOnClickListener { showCreateProfileDialog(ctx, d, layout, scroll) } })
        }
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Done") { _, _ -> }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    private fun profileRow(ctx: Context, profile: CustomProfile, d: Float, layout: LinearLayout, scroll: ScrollView): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp(), 10.dp(), 8.dp(), 10.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 10 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp() }
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply { text = if (profile.enabled) "${profile.name} (ACTIVE)" else profile.name; textSize = 14f; setTextColor(if (profile.enabled) cAccent else cText); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(ctx).apply { text = "${profile.sources.size} sources"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) }) })
            addView(SwitchCompat(ctx).apply { isChecked = profile.enabled
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (RaghavAnimeFeatures.getCustomProfiles().count { it.enabled } >= 1) {
                            AlertDialog.Builder(ctx).setTitle("WARNING").setMessage("Select only one profile.\n\nOther profiles have been turned off.").setPositiveButton("OK") { _, _ ->
                                RaghavAnimeFeatures.getCustomProfiles().forEach { p -> if (p.enabled) RaghavAnimeFeatures.setActiveProfile(null) }
                                RaghavAnimeFeatures.setActiveProfile(profile.name)
                                refreshProfiles(layout, scroll, ctx, d)
                            }.show()
                        } else { RaghavAnimeFeatures.setActiveProfile(profile.name); refreshProfiles(layout, scroll, ctx, d) }
                    } else { RaghavAnimeFeatures.setActiveProfile(null); refreshProfiles(layout, scroll, ctx, d) }
                } })
            addView(Button(ctx).apply { text = "Delete"; setTextColor(cTextSub); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setPadding(8.dp(), 0, 8.dp(), 0)
                setOnClickListener {
                    val updated = RaghavAnimeFeatures.getCustomProfiles().filter { it.name != profile.name }
                    RaghavAnimeFeatures.saveCustomProfiles(updated); Toast.makeText(ctx, "Profile deleted", Toast.LENGTH_SHORT).show(); refreshProfiles(layout, scroll, ctx, d) } })
        }
    }

    private fun refreshProfiles(layout: LinearLayout, scroll: ScrollView, ctx: Context, d: Float) {
        layout.removeAllViews()
        fun Int.dp() = (this * d).toInt()
        layout.addView(TextView(ctx).apply { text = "Custom Source Profiles"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "When active, a profile overrides sources selected above."; textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 12.dp()) })
        val profiles = RaghavAnimeFeatures.getCustomProfiles()
        for (profile in profiles) layout.addView(profileRow(ctx, profile, d, layout, scroll))
        if (profiles.size < 3) layout.addView(Button(ctx).apply { text = "+ CREATE NEW PROFILE"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 8.dp())
            setOnClickListener { showCreateProfileDialog(ctx, d, layout, scroll) } })
    }

    private fun showCreateProfileDialog(ctx: Context, d: Float, layout: LinearLayout, scroll: ScrollView) {
        fun Int.dp() = (this * d).toInt()
        val createLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        createLayout.addView(TextView(ctx).apply { text = "Profile Name"; textSize = 14f; setTextColor(cAccent); setPadding(0, 0, 0, 4.dp()) })
        val nameInput = EditText(ctx).apply { hint = "Enter profile name"; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()) }
        createLayout.addView(nameInput)
        createLayout.addView(TextView(ctx).apply { text = "Select Sources"; textSize = 14f; setTextColor(cAccent); setPadding(0, 12.dp(), 0, 4.dp()) })
        val checksContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableMapOf<String, CheckBox>()
        for ((key, name) in providers) { val cb = CheckBox(ctx).apply { text = name; setTextColor(cText); textSize = 13f }; checks[key] = cb; checksContainer.addView(cb) }
        val checksScroll = ScrollView(ctx).apply { addView(checksContainer); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300.dp()) }
        createLayout.addView(checksScroll)
        AlertDialog.Builder(ctx).setView(createLayout).setTitle("Create Profile")
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                if (name.isBlank()) { Toast.makeText(ctx, "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val selectedSources = checks.filter { it.value.isChecked }.keys.toList()
                if (selectedSources.isEmpty()) { Toast.makeText(ctx, "Select at least 1 source", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                AlertDialog.Builder(ctx).setTitle("WARNING").setMessage("This profile overwrites the sources selected in the Sources tab.\nWhen this profile is active, only the sources selected here will run.\n\nDo you want to continue?")
                    .setPositiveButton("Yes, Save") { _, _ ->
                        val profiles = RaghavAnimeFeatures.getCustomProfiles().toMutableList()
                        profiles.add(CustomProfile(name, selectedSources, false)); RaghavAnimeFeatures.saveCustomProfiles(profiles)
                        Toast.makeText(ctx, "Profile '$name' created", Toast.LENGTH_SHORT).show(); refreshProfiles(layout, scroll, ctx, d) }
                    .setNegativeButton("Cancel", null).show() }
            .setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    // =================== EXPERIMENTAL ===================
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showExperimentalDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Experimental Features"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "May not work properly"; textSize = 11f; setTextColor(cWarning); setPadding(0, 0, 0, 12.dp()) })

        // --- SubDL Subtitles ---
        layout.addView(sectionLabel(ctx, "SUBTITLES", d))
        layout.addView(toggleRow(ctx, "SubDL English Subtitles", isSubDLEnabled(), d) { setSubDLEnabled(it) })
        layout.addView(descText(ctx, "Fetches English subtitles from SubDL", d))

        // --- AI Subtitles ---
        layout.addView(sectionLabel(ctx, "AI SUBTITLES (ENGLISH DUB)", d))
        layout.addView(toggleRow(ctx, "AI Subtitle Generation", com.laddu100.onlytesting.AISubtitleHelper.isEnabled(), d) { com.laddu100.onlytesting.AISubtitleHelper.setEnabled(it) })
        layout.addView(descText(ctx, "Auto-generates English subtitles for English dub using AI (Groq/OpenAI Whisper)", d))

        // AI Subtitle Settings button
        layout.addView(Button(ctx).apply { text = "AI SUBTITLE SETTINGS"; setTextColor(cAccent); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 8.dp())
            setOnClickListener { showAISubtitleSettingsDialog(ctx, d) } })

        // --- Watch Tracking ---
        layout.addView(sectionLabel(ctx, "WATCH TRACKING", d))
        layout.addView(toggleRow(ctx, "Watch Time Tracker", RaghavAnimeFeatures.isEnabled("watch_time"), d) { RaghavAnimeFeatures.setEnabled("watch_time", it) })
        layout.addView(descText(ctx, "Tracks watch time per anime, view in Watch Time Stats", d))
        layout.addView(sectionLabel(ctx, "DISCOVERY", d))
        layout.addView(toggleRow(ctx, "Anime Recommendations", RaghavAnimeFeatures.isEnabled("recommendations"), d) { RaghavAnimeFeatures.setEnabled("recommendations", it) })
        layout.addView(descText(ctx, "Shows recommended anime based on watch history", d))
        layout.addView(Button(ctx).apply { text = "RESET RECOMMENDATIONS"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 8.dp())
            setOnClickListener { RaghavAnimeFeatures.resetRecommendations(); Toast.makeText(ctx, "Recommendations reset. New ones will load next time.", Toast.LENGTH_SHORT).show() } })
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Save") { _, _ -> }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showAISubtitleSettingsDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "AI Subtitle Settings"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "Generates English subtitles for English dub streams\nRequires API key (free tier available)"; textSize = 11f; setTextColor(cTextSub); setPadding(0, 0, 0, 12.dp()) })

        // Provider selector
        layout.addView(TextView(ctx).apply { text = "Provider"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 2.dp()) })
        val providerSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, com.laddu100.onlytesting.AISubtitleHelper.providers.map { it.second }) }
        val currentProvider = com.laddu100.onlytesting.AISubtitleHelper.getProvider()
        val providerIndex = com.laddu100.onlytesting.AISubtitleHelper.providers.indexOfFirst { it.first == currentProvider }.let { if (it < 0) 0 else it }
        providerSpinner.setSelection(providerIndex)
        layout.addView(providerSpinner)

        // Model selector (updates based on provider)
        layout.addView(TextView(ctx).apply { text = "Model"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val modelSpinner = Spinner(ctx)
        layout.addView(modelSpinner)
        fun updateModelSpinner() {
            val prov = com.laddu100.onlytesting.AISubtitleHelper.providers[providerSpinner.selectedItemPosition].first
            val models = com.laddu100.onlytesting.AISubtitleHelper.models[prov] ?: emptyList()
            modelSpinner.adapter = darkAdapter(ctx, models.map { it.second })
            val currentModel = com.laddu100.onlytesting.AISubtitleHelper.getModel()
            val modelIndex = models.indexOfFirst { it.first == currentModel }.let { if (it < 0) 0 else it }
            modelSpinner.setSelection(modelIndex)
        }
        updateModelSpinner()
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateModelSpinner() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Groq API Key
        layout.addView(TextView(ctx).apply { text = "Groq API Key"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        layout.addView(TextView(ctx).apply { text = "Get free key at console.groq.com"; textSize = 10f; setTextColor(cTextDim); setPadding(0, 0, 0, 4.dp()) })
        val groqKeyInput = EditText(ctx).apply { hint = "gsk_..."; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()); textSize = 13f; inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(com.laddu100.onlytesting.AISubtitleHelper.getGroqKey()) }
        layout.addView(groqKeyInput)

        // OpenAI API Key
        layout.addView(TextView(ctx).apply { text = "OpenAI API Key"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        layout.addView(TextView(ctx).apply { text = "Get key at platform.openai.com"; textSize = 10f; setTextColor(cTextDim); setPadding(0, 0, 0, 4.dp()) })
        val openaiKeyInput = EditText(ctx).apply { hint = "sk-..."; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()); textSize = 13f; inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(com.laddu100.onlytesting.AISubtitleHelper.getOpenAIKey()) }
        layout.addView(openaiKeyInput)

        // Info text
        layout.addView(TextView(ctx).apply { text = "How it works:\n1. When you load an English dub episode, the plugin downloads the audio track\n2. Audio is sent to the selected AI provider for transcription\n3. Generated subtitles appear in the subtitle picker as \"AI English\"\n\nLimitations:\n- Max 24MB per request (long episodes may be truncated)\n- Adds 5-15s delay before subtitles appear\n- Only works for English dub streams\n- Requires internet connection"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 12.dp(), 0, 8.dp()) })

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Save") { _, _ ->
            val prov = com.laddu100.onlytesting.AISubtitleHelper.providers[providerSpinner.selectedItemPosition].first
            val models = com.laddu100.onlytesting.AISubtitleHelper.models[prov] ?: emptyList()
            val model = if (models.isNotEmpty()) models[modelSpinner.selectedItemPosition].first else com.laddu100.onlytesting.AISubtitleHelper.getModel()
            com.laddu100.onlytesting.AISubtitleHelper.setProvider(prov)
            com.laddu100.onlytesting.AISubtitleHelper.setModel(model)
            com.laddu100.onlytesting.AISubtitleHelper.setGroqKey(groqKeyInput.text?.toString()?.trim() ?: "")
            com.laddu100.onlytesting.AISubtitleHelper.setOpenAIKey(openaiKeyInput.text?.toString()?.trim() ?: "")
            Toast.makeText(ctx, "AI subtitle settings saved", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    // =================== WATCH TIME STATS ===================
    private fun showWatchTimeDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val history = RaghavAnimeFeatures.getWatchHistory().sortedByDescending { it.watchTimeMs }
        val totalTime = RaghavAnimeFeatures.getTotalWatchTime()
        val totalEps = RaghavAnimeFeatures.getTotalEpisodesWatched()
        val animeCount = RaghavAnimeFeatures.getAnimeWatchedCount()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Watch Time Statistics"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 12.dp()) })
        val summary = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp()); background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 12 * d; setColor(cCard) }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp() } }
        summary.addView(TextView(ctx).apply { text = "Total Watch Time: ${RaghavAnimeFeatures.formatWatchTime(totalTime)}"; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        summary.addView(TextView(ctx).apply { text = "Episodes Watched: $totalEps"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 2.dp(), 0, 0) })
        summary.addView(TextView(ctx).apply { text = "Anime Started: $animeCount"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 2.dp(), 0, 0) })
        layout.addView(summary)
        layout.addView(Button(ctx).apply { text = "RESET WATCH HISTORY"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 12.dp())
            setOnClickListener { AlertDialog.Builder(ctx).setTitle("Reset Watch History").setMessage("This will delete all watch time data. Are you sure?").setPositiveButton("Yes, Reset") { _, _ -> RaghavAnimeFeatures.resetWatchHistory(); Toast.makeText(ctx, "Watch history reset", Toast.LENGTH_SHORT).show(); dialog?.dismiss(); showWatchTimeDialog(ctx, d) }.setNegativeButton("Cancel", null).show() } })
        if (history.isEmpty()) {
            layout.addView(TextView(ctx).apply { text = "No watch history yet.\nStart watching anime to see stats here."; textSize = 14f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 24.dp(), 0, 24.dp()) })
        } else {
            layout.addView(sectionLabel(ctx, "TOP ANIME BY WATCH TIME", d))
            for ((index, entry) in history.take(20).withIndex()) {
                layout.addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp()); background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 8 * d; setColor(cCard) }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4.dp() }
                    addView(TextView(ctx).apply { text = "${index + 1}."; textSize = 14f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 8.dp(), 0) })
                    addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(ctx).apply { text = entry.title; textSize = 14f; setTextColor(cText); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(TextView(ctx).apply { text = "${entry.episodesWatched} eps - ${RaghavAnimeFeatures.formatWatchTime(entry.watchTimeMs)}"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) }) }) })
            }
        }
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Close") { _, _ -> }.create().apply { show(); styleButtons() }
    }

    // =================== DISCOVER ANIME ===================
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showDiscoverDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Discover New Anime"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 2.dp()) })
        layout.addView(TextView(ctx).apply { text = "Experimental feature (may not work)"; textSize = 11f; setTextColor(Color.parseColor("#FFD54F")); setPadding(0, 0, 0, 12.dp()) })
        layout.addView(TextView(ctx).apply { text = "Search"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 2.dp()) })
        val searchInput = EditText(ctx).apply { hint = "Type anime name for suggestions"; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()) }
        layout.addView(searchInput)
        val suggestionsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp(), 0, 0) }
        layout.addView(suggestionsContainer)
        var searchJob: kotlinx.coroutines.Job? = null
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                if (query.length < 3) { suggestionsContainer.removeAllViews(); return }
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    val suggestions = withContext(Dispatchers.IO) { RaghavAnimeFeatures.searchSuggestions(query) }
                    suggestionsContainer.removeAllViews()
                    for (anime in suggestions.take(5)) {
                        suggestionsContainer.addView(TextView(ctx).apply { text = anime.title; textSize = 13f; setTextColor(cTextSub); setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp()); isClickable = true
                            setOnClickListener { searchInput.setText(anime.title); suggestionsContainer.removeAllViews() } })
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        // Genre (multi-select)
        val selectedGenres = mutableSetOf<String>()
        layout.addView(TextView(ctx).apply { text = "Genres"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val genreBtn = multiSelectButton(ctx, "Genres", RaghavAnimeFeatures.availableGenres.filter { it != "Any" }, selectedGenres, d)
        layout.addView(genreBtn)
        layout.addView(TextView(ctx).apply { text = "Sort By"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val sortSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableSorts.map { it.second }) }
        layout.addView(sortSpinner)
        // Advanced Search button
        layout.addView(Button(ctx).apply { text = "ADVANCED SEARCH"; setTextColor(Color.parseColor("#FFD54F")); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 12.dp(), 0, 4.dp())
            setOnClickListener { dialog?.dismiss(); showAdvancedSearchDialog(ctx, d) } })
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Search") { _, _ ->
            val sortBy = RaghavAnimeFeatures.availableSorts[sortSpinner.selectedItemPosition].first
            val query = searchInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            showDiscoverResults(ctx, d, query, selectedGenres.toList(), sortBy, 1) }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    private fun showDiscoverResults(ctx: Context, d: Float, query: String?, genres: List<String>, sortBy: String, page: Int) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Searching..."; textSize = 14f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
        scroll.addView(layout)
        val dialog = AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Close") { _, _ -> }.create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pageData = withContext(Dispatchers.IO) { RaghavAnimeFeatures.discoverAnime(query, genres, sortBy, page) }
                layout.removeAllViews()
                if (pageData.results.isEmpty()) {
                    layout.addView(TextView(ctx).apply { text = "No results found"; textSize = 14f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 24.dp(), 0, 24.dp()) })
                    return@launch
                }
                // Page info bar with page navigation
                val pageBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8.dp()) }
                if (page > 1) {
                    pageBar.addView(Button(ctx).apply { text = "< Prev"; setTextColor(cAccent); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog.dismiss(); showDiscoverResults(ctx, d, query, genres, sortBy, page - 1) } })
                }
                pageBar.addView(TextView(ctx).apply { text = "  Page ${pageData.currentPage} / ${pageData.lastPage}  "; textSize = 13f; setTextColor(cTextSub); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                if (pageData.hasNextPage) {
                    pageBar.addView(Button(ctx).apply { text = "Next >"; setTextColor(cAccent); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog.dismiss(); showDiscoverResults(ctx, d, query, genres, sortBy, page + 1) } })
                }
                layout.addView(pageBar)
                layout.addView(TextView(ctx).apply { text = "${pageData.results.size} results"; textSize = 12f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp()) })

                for (anime in pageData.results) {
                    layout.addView(resultCard(ctx, anime, d, dialog, SearchContext.Discover(query, genres, sortBy, page)))
                }

                // Bottom navigation
                if (pageData.hasNextPage || page > 1) {
                    val bottomBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12.dp(), 0, 8.dp()) }
                    if (page > 1) bottomBar.addView(Button(ctx).apply { text = "< Previous"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss(); showDiscoverResults(ctx, d, query, genres, sortBy, page - 1) } })
                    if (pageData.hasNextPage) bottomBar.addView(Button(ctx).apply { text = "Next >"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss(); showDiscoverResults(ctx, d, query, genres, sortBy, page + 1) } })
                    layout.addView(bottomBar)
                }
            } catch (e: Exception) {
                layout.removeAllViews()
                layout.addView(TextView(ctx).apply { text = "Error: ${e.message}"; textSize = 14f; setTextColor(cWarning) })
            }
        }
    }

    private fun resultCard(ctx: Context, anime: RaghavAnimeFeatures.DiscoverResult, d: Float, parentDialog: AlertDialog, searchContext: SearchContext? = null): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 10 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp() }
            isClickable = true; isFocusable = true
            setOnClickListener { parentDialog.dismiss(); showAnimeDetailDialog(ctx, d, anime, searchContext) }

            // Poster thumbnail
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(), 64.dp())
                scaleType = ImageView.ScaleType.CENTER_CROP
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            android.graphics.BitmapFactory.decodeStream(java.net.URL(anime.posterUrl).openStream())
                        }
                        setImageBitmap(bitmap)
                    } catch (_: Exception) {}
                }
            })

            // Info column
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(8.dp(), 0, 0, 0) }
            col.addView(TextView(ctx).apply { text = anime.title; textSize = 14f; setTextColor(cText); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) })
            val infoParts = mutableListOf<String>()
            anime.year?.let { infoParts.add(it.toString()) }
            anime.format?.let { infoParts.add(it) }
            anime.episodes?.let { infoParts.add("${it} eps") }
            anime.score?.let { infoParts.add("%.1f \u2605".format(it / 10)) }
            val infoText = infoParts.joinToString(" - ")
            if (infoText.isNotEmpty()) col.addView(TextView(ctx).apply { text = infoText; textSize = 11f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) })
            anime.genres?.take(3)?.joinToString(", ")?.let { col.addView(TextView(ctx).apply { text = it; textSize = 10f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) }) }
            addView(col)
            addView(TextView(ctx).apply { text = ">"; textSize = 16f; setTextColor(cAccent); setPadding(8.dp(), 0, 0, 0) })
        }
    }

    private sealed class SearchContext {
        abstract val page: Int
        data class Discover(val query: String?, val genres: List<String>, val sortBy: String, override val page: Int) : SearchContext()
        data class Advanced(val search: String?, val genres: List<String>, val tags: List<String>, val year: Int?, val season: String, val formats: List<String>, val status: String, val sortBy: String, override val page: Int) : SearchContext()
    }
    private var lastSearchContext: SearchContext? = null

    private fun showAnimeDetailDialog(ctx: Context, d: Float, anime: RaghavAnimeFeatures.DiscoverResult, searchContext: SearchContext? = null) {
        if (searchContext != null) lastSearchContext = searchContext
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Loading..."; textSize = 14f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
        scroll.addView(layout)
        val dialog = AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Close") { _, _ -> }
            .setNeutralButton("Back to Results") { _, _ ->
                val sc = lastSearchContext
                when (sc) {
                    is SearchContext.Discover -> showDiscoverResults(ctx, d, sc.query, sc.genres, sc.sortBy, sc.page)
                    is SearchContext.Advanced -> showAdvancedResults(ctx, d, sc.search, sc.genres, sc.tags, sc.year, sc.season, sc.formats, sc.status, sc.sortBy, sc.page)
                    null -> {}
                }
            }
            .create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val detail = withContext(Dispatchers.IO) { RaghavAnimeFeatures.fetchAnimeDetail(anime.id) }
                layout.removeAllViews()
                if (detail == null) {
                    layout.addView(TextView(ctx).apply { text = "Failed to load details"; textSize = 14f; setTextColor(cWarning) })
                    return@launch
                }
                // Poster + title
                val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 12.dp()) }
                header.addView(ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(80.dp(), 112.dp())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    CoroutineScope(Dispatchers.Main).launch {
                        try { val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeStream(java.net.URL(detail.posterUrl).openStream()) }; setImageBitmap(bmp) } catch (_: Exception) {}
                    }
                })
                val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(12.dp(), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                titleCol.addView(TextView(ctx).apply { text = detail.title; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD); maxLines = 3 })
                detail.romajiTitle?.let { titleCol.addView(TextView(ctx).apply { text = it; textSize = 12f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0); maxLines = 1 }) }
                val metaParts = mutableListOf<String>()
                detail.year?.let { metaParts.add(it.toString()) }
                detail.format?.let { metaParts.add(it) }
                detail.status?.let { metaParts.add(it.replace("_", " ").lowercase()) }
                detail.episodes?.let { metaParts.add("${it} eps") }
                detail.score?.let { metaParts.add("%.1f/10".format(it / 10)) }
                val metaText = metaParts.joinToString(" - ")
                if (metaText.isNotEmpty()) titleCol.addView(TextView(ctx).apply { text = metaText; textSize = 12f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 0) })
                header.addView(titleCol)
                layout.addView(header)

                // Genres
                detail.genres?.let {
                    layout.addView(TextView(ctx).apply { text = "Genres: ${it.joinToString(", ")}"; textSize = 12f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 8.dp()) })
                }

                // Synopsis
                detail.synopsis?.let {
                    layout.addView(TextView(ctx).apply { text = "Synopsis"; textSize = 13f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 4.dp()) })
                    layout.addView(TextView(ctx).apply { text = it.take(500) + if (it.length > 500) "..." else ""; textSize = 12f; setTextColor(cTextSub); setPadding(0, 0, 0, 12.dp()) })
                }

                // Watch Now button — searches WITHIN the RaghavAnime plugin directly.
                // Calls RaghavAnime.search() in-process, shows results in a dialog, then
                // loads the selected result via loadSearchResult(). No global search, no
                // disabling other plugins.
                layout.addView(Button(ctx).apply {
                    text = "WATCH NOW"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(cAccent) }
                    setPadding(0, 16.dp(), 0, 16.dp())
                    setOnClickListener {
                        try { dialog.dismiss() } catch (_: Exception) {}
                        try { this@SettingsFragment.dismiss() } catch (_: Exception) {}
                        showRaghavAnimeSearchResults(ctx, d, detail.title)
                    }
                })

            } catch (e: Exception) {
                layout.removeAllViews()
                layout.addView(TextView(ctx).apply { text = "Error: ${e.message}"; textSize = 14f; setTextColor(cWarning) })
            }
        }
    }

    // =================== WATCH NOW (in-plugin search) ===================
    private fun showRaghavAnimeSearchResults(ctx: Context, d: Float, query: String) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Searching in RaghavAnime..."; textSize = 14f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
        scroll.addView(layout)
        val dialog = AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Close") { _, _ -> }.create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Call RaghavAnime's search() directly — no global search, no other plugins affected
                val results = withContext(Dispatchers.IO) { OnlyTesting().search(query) }
                layout.removeAllViews()
                if (results.isEmpty()) {
                    layout.addView(TextView(ctx).apply { text = "No results found in RaghavAnime for \"$query\""; textSize = 14f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 24.dp(), 0, 24.dp()) })
                    return@launch
                }
                layout.addView(TextView(ctx).apply { text = "Found ${results.size} results in RaghavAnime:"; textSize = 13f; setTextColor(cAccent); setPadding(0, 0, 0, 8.dp()) })
                for (result in results) {
                    layout.addView(searchResultCard(ctx, result, d, dialog))
                }
            } catch (e: Exception) {
                layout.removeAllViews()
                layout.addView(TextView(ctx).apply { text = "Error: ${e.message}"; textSize = 14f; setTextColor(cWarning) })
            }
        }
    }

    private fun searchResultCard(ctx: Context, result: SearchResponse, d: Float, parentDialog: AlertDialog): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 10 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                parentDialog.dismiss()
                // Load the result directly — navigates to the result page within CloudStream
                try { loadSearchResult(result) } catch (_: Exception) {}
            }
            // Poster thumbnail
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(), 64.dp())
                scaleType = ImageView.ScaleType.CENTER_CROP
                result.posterUrl?.let { url ->
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeStream(java.net.URL(url).openStream()) }
                            setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }
                }
            })
            // Title
            addView(TextView(ctx).apply { text = result.name; textSize = 14f; setTextColor(cText); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(8.dp(), 0, 0, 0); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(ctx).apply { text = ">"; textSize = 16f; setTextColor(cAccent); setPadding(8.dp(), 0, 0, 0) })
        }
    }

    // =================== ADVANCED SEARCH ===================
    private fun showAdvancedSearchDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Advanced Search"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 2.dp()) })
        layout.addView(TextView(ctx).apply { text = "Experimental feature (may not work)"; textSize = 11f; setTextColor(Color.parseColor("#FFD54F")); setPadding(0, 0, 0, 12.dp()) })

        // Search input + suggestions (same as Discover search)
        layout.addView(TextView(ctx).apply { text = "Search (optional)"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 2.dp()) })
        val searchInput = EditText(ctx).apply { hint = "Type anime name for suggestions"; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()) }
        layout.addView(searchInput)
        val suggestionsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp(), 0, 0) }
        layout.addView(suggestionsContainer)
        var searchJob: kotlinx.coroutines.Job? = null
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                if (query.length < 3) { suggestionsContainer.removeAllViews(); return }
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    val suggestions = withContext(Dispatchers.IO) { RaghavAnimeFeatures.searchSuggestions(query) }
                    suggestionsContainer.removeAllViews()
                    for (anime in suggestions.take(5)) {
                        suggestionsContainer.addView(TextView(ctx).apply { text = anime.title; textSize = 13f; setTextColor(cTextSub); setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp()); isClickable = true
                            setOnClickListener { searchInput.setText(anime.title); suggestionsContainer.removeAllViews() } })
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Genres (multi-select, loaded from AniList GenreCollection)
        val selectedGenres = mutableSetOf<String>()
        val allGenres = mutableListOf<String>()
        layout.addView(TextView(ctx).apply { text = "Genres"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val genreBtn = multiSelectButton(ctx, "Genres", allGenres, selectedGenres, d)
        layout.addView(genreBtn)
        CoroutineScope(Dispatchers.Main).launch {
            val genres = withContext(Dispatchers.IO) { RaghavAnimeFeatures.fetchGenres() }
            if (genres.isNotEmpty()) {
                allGenres.clear(); allGenres.addAll(genres)
                (genreBtn.getChildAt(0) as? TextView)?.text = formatMultiSelectLabel("Genres", selectedGenres)
            }
        }

        // Year spinner (dropdown from current year+1 down to 1940)
        layout.addView(TextView(ctx).apply { text = "Year"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val yearOptions = listOf("Any") + (currentYear + 1 downTo 1940).map { it.toString() }
        val yearSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, yearOptions) }
        layout.addView(yearSpinner)

        // Season
        layout.addView(TextView(ctx).apply { text = "Season"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val seasonSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableSeasons) }
        layout.addView(seasonSpinner)

        // Formats (multi-select)
        val selectedFormats = mutableSetOf<String>()
        val formatOptions = RaghavAnimeFeatures.availableFormats.filter { it != "Any" }
        layout.addView(TextView(ctx).apply { text = "Formats"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val formatBtn = multiSelectButton(ctx, "Formats", formatOptions, selectedFormats, d)
        layout.addView(formatBtn)

        // Tags (multi-select, loaded from AniList MediaTagCollection)
        val selectedTags = mutableSetOf<String>()
        val allTags = mutableListOf<String>()
        layout.addView(TextView(ctx).apply { text = "Tags"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val tagBtn = multiSelectButton(ctx, "Tags", allTags, selectedTags, d)
        layout.addView(tagBtn)
        CoroutineScope(Dispatchers.Main).launch {
            val tags = withContext(Dispatchers.IO) { RaghavAnimeFeatures.fetchTags() }
            if (tags.isNotEmpty()) {
                allTags.clear(); allTags.addAll(tags.map { it.name })
                (tagBtn.getChildAt(0) as? TextView)?.text = formatMultiSelectLabel("Tags", selectedTags)
            }
        }

        // Status
        layout.addView(TextView(ctx).apply { text = "Status"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val statusSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableStatus) }
        layout.addView(statusSpinner)

        // Sort
        layout.addView(TextView(ctx).apply { text = "Sort By"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp()) })
        val sortSpinner = Spinner(ctx).apply { adapter = darkAdapter(ctx, RaghavAnimeFeatures.availableSorts.map { it.second }) }
        layout.addView(sortSpinner)

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Search") { _, _ ->
            val search = searchInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val year = if (yearSpinner.selectedItemPosition > 0) yearSpinner.selectedItem.toString().toIntOrNull() else null
            val season = RaghavAnimeFeatures.availableSeasons[seasonSpinner.selectedItemPosition]
            val status = RaghavAnimeFeatures.availableStatus[statusSpinner.selectedItemPosition]
            val sortBy = RaghavAnimeFeatures.availableSorts[sortSpinner.selectedItemPosition].first
            showAdvancedResults(ctx, d, search, selectedGenres.toList(), selectedTags.toList(), year, season, selectedFormats.toList(), status, sortBy, 1)
        }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    private fun showAdvancedResults(ctx: Context, d: Float, search: String?, genres: List<String>, tags: List<String>, year: Int?, season: String, formats: List<String>, status: String, sortBy: String, page: Int) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Searching..."; textSize = 14f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
        scroll.addView(layout)
        val dialog = AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Close") { _, _ -> }.create()
        dialog.show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pageData = withContext(Dispatchers.IO) { RaghavAnimeFeatures.advancedSearch(search, genres, tags, year, season, formats, status, sortBy, page) }
                layout.removeAllViews()
                if (pageData.results.isEmpty()) {
                    layout.addView(TextView(ctx).apply { text = "No results found"; textSize = 14f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 24.dp(), 0, 24.dp()) })
                    return@launch
                }
                val pageBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8.dp()) }
                if (page > 1) pageBar.addView(Button(ctx).apply { text = "< Prev"; setTextColor(cAccent); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog.dismiss(); showAdvancedResults(ctx, d, search, genres, tags, year, season, formats, status, sortBy, page - 1) } })
                pageBar.addView(TextView(ctx).apply { text = "  Page ${pageData.currentPage} / ${pageData.lastPage}  "; textSize = 13f; setTextColor(cTextSub); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                if (pageData.hasNextPage) pageBar.addView(Button(ctx).apply { text = "Next >"; setTextColor(cAccent); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog.dismiss(); showAdvancedResults(ctx, d, search, genres, tags, year, season, formats, status, sortBy, page + 1) } })
                layout.addView(pageBar)
                layout.addView(TextView(ctx).apply { text = "${pageData.results.size} results"; textSize = 12f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp()) })
                for (anime in pageData.results) {
                    layout.addView(resultCard(ctx, anime, d, dialog, SearchContext.Advanced(search, genres, tags, year, season, formats, status, sortBy, page)))
                }
                if (pageData.hasNextPage || page > 1) {
                    val bottomBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12.dp(), 0, 8.dp()) }
                    if (page > 1) bottomBar.addView(Button(ctx).apply { text = "< Previous"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss(); showAdvancedResults(ctx, d, search, genres, tags, year, season, formats, status, sortBy, page - 1) } })
                    if (pageData.hasNextPage) bottomBar.addView(Button(ctx).apply { text = "Next >"; setTextColor(cAccent); textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss(); showAdvancedResults(ctx, d, search, genres, tags, year, season, formats, status, sortBy, page + 1) } })
                    layout.addView(bottomBar)
                }
            } catch (e: Exception) {
                layout.removeAllViews()
                layout.addView(TextView(ctx).apply { text = "Error: ${e.message}"; textSize = 14f; setTextColor(cWarning) })
            }
        }
    }

    // =================== HELPERS ===================
    /** Creates a multi-select button that opens a checkbox dialog with a search filter.
     *  Options list is read live so it works even when options are loaded asynchronously
     *  (e.g. from AniList). The search box lets users filter among hundreds of tags/genres. */
    private fun multiSelectButton(ctx: Context, label: String, options: List<String>, selected: MutableSet<String>, d: Float): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        val titleView = TextView(ctx).apply { text = formatMultiSelectLabel(label, selected); textSize = 14f; setTextColor(cText); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 8 * d; setColor(cCard) }
            isClickable = true; isFocusable = true
            addView(titleView)
            addView(TextView(ctx).apply { text = if (selected.isEmpty()) "+" else "Edit"; textSize = 13f; setTextColor(cAccent); setPadding(8.dp(), 0, 0, 0) })
            setOnClickListener {
                // Dialog layout: search bar + scrollable checkbox list
                val dialogLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
                // Search filter
                val searchInput = EditText(ctx).apply { hint = "Search $label..."; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()); textSize = 13f }
                dialogLayout.addView(searchInput)
                // Checkbox container
                val checksContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp(), 0, 0) }
                val checks = mutableMapOf<String, CheckBox>()
                // Show only options matching the search query
                fun rebuildList(filter: String) {
                    checksContainer.removeAllViews()
                    checks.clear()
                    val filtered = if (filter.isBlank()) options else options.filter { it.contains(filter, ignoreCase = true) }
                    for (opt in filtered) {
                        val cb = CheckBox(ctx).apply { text = opt; setTextColor(cText); textSize = 13f; isChecked = opt in selected }
                        checks[opt] = cb
                        checksContainer.addView(cb)
                    }
                    if (filtered.isEmpty()) {
                        checksContainer.addView(TextView(ctx).apply { text = "No matching $label"; textSize = 12f; setTextColor(cTextDim); setPadding(0, 8.dp(), 0, 8.dp()) })
                    }
                }
                rebuildList("")
                searchInput.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { rebuildList(s?.toString()?.trim() ?: "") }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
                val checksScroll = ScrollView(ctx).apply { addView(checksContainer); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300.dp()) }
                dialogLayout.addView(checksScroll)
                AlertDialog.Builder(ctx).setTitle("Select $label").setView(dialogLayout)
                    .setPositiveButton("OK") { _, _ ->
                        // Only update visible items — preserve selections that were filtered out
                        for ((opt, cb) in checks) {
                            if (cb.isChecked) selected.add(opt) else selected.remove(opt)
                        }
                        titleView.text = formatMultiSelectLabel(label, selected)
                    }
                    .setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
            }
        }
    }

    private fun formatMultiSelectLabel(label: String, selected: Set<String>): String {
        return if (selected.isEmpty()) "$label: Any" else "$label: ${selected.joinToString(", ")}"
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(ctx: Context, label: String, checked: Boolean, d: Float, onChange: (Boolean) -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10.dp(), 0, 10.dp())
            addView(TextView(ctx).apply { text = label; textSize = 15f; setTextColor(cText); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(SwitchCompat(ctx).apply { isChecked = checked
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) } }) }
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
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(cText); setBackgroundColor(cCard); setPadding(24, 20, 24, 20) } } }
    private fun AlertDialog.styleButtons() { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent); getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim) }
    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx).setTitle("Restart Required").setMessage("Restart the app to apply changes?").setPositiveButton("Yes") { _, _ -> restartApp() }.setNegativeButton("No") { _, _ -> try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} }.show() }
    private fun restartApp() { try { val context = requireContext().applicationContext; val pm = context.packageManager; val intent = pm.getLaunchIntentForPackage(context.packageName); val componentName = intent?.component; if (componentName != null) { val restartIntent = android.content.Intent.makeRestartActivityTask(componentName); context.startActivity(restartIntent); Runtime.getRuntime().exit(0) } } catch (_: Throwable) { try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} } }
}
