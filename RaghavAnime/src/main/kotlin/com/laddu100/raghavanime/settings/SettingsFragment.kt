package com.laddu100.raghavanime.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.laddu100.raghavanime.CustomPosters
import com.laddu100.raghavanime.RaghavAnimeFeatures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

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
        root.addView(TextView(ctx).apply { text = "Tap a section to configure"; textSize = 12f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 2.dp(), 0, 16.dp()) })
        root.addView(sectionBtn(ctx, "POSTERS", "Custom poster template, hide labels & live preview", d) { showPostersDialog(ctx, d) })
        root.addView(sectionBtn(ctx, "EXPERIMENTAL", "Watch time tracker & anime recommendations", d) { showExperimentalDialog(ctx, d) })
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

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showPostersDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Posters"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "Custom poster template, hide labels & live preview"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 12.dp()) })

        val posterField = EditText(ctx).apply {
            hint = "https://…/{imdb_id}.jpg"
            setText(CustomPosters.template)
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cTextDim)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        val posterCard = LinearLayout(ctx).apply {
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 12 * d; setColor(cCard) }
            setPadding(14.dp(), 4.dp(), 14.dp(), 4.dp())
        }
        posterCard.addView(posterField)
        val posterNote = descText(ctx, "Default poster is used when the custom one is missing. Applies to the home page, search, details and recommendations.", d)
        val placeholdersNote = descText(ctx, "Placeholders: {imdb_id} {tmdb_id} {anilist_id} {kitsu_id} {type}", d)
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6.dp(), 0, 2.dp()) }

        class Sample(val label: String, val anilistId: Int, val imdbId: String, val tmdbId: Int, val isMovie: Boolean, val defaultPoster: String)
        val samples = listOf(
            Sample("One Piece", 21, "tt0388629", 37854, false, "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-ELSYx3yMPcKM.jpg"),
            Sample("Your Name", 21519, "tt5311514", 372058, true, "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21519-SUo3ZQuCbYhJ.png"),
        )

        class Slot(val image: ImageView, val note: TextView)
        val slots = samples.map {
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(cCard); cornerRadius = 12 * d }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 12 * d)
                }
            }
            Slot(image, TextView(ctx).apply { textSize = 11f; setTextColor(cTextDim); gravity = Gravity.CENTER })
        }
        val sampleLabels = samples.map { s ->
            TextView(ctx).apply { text = s.label; textSize = 13f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER }
        }
        val previewRow = LinearLayout(ctx).apply {
            weightSum = samples.size.toFloat()
            samples.forEachIndexed { i, _ ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(slots[i].image, LinearLayout.LayoutParams(110.dp(), 165.dp()))
                    addView(sampleLabels[i], LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp() })
                    addView(slots[i].note, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp() })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        var generation = 0
        fun reloadPreview() {
            val gen = ++generation
            val tpl = if (CustomPosters.enabled) posterField.text.toString().trim()
                .replace("%7B", "{", ignoreCase = true).replace("%7D", "}", ignoreCase = true)
                .takeIf { it.startsWith("http://") || it.startsWith("https://") } else null
            sampleLabels.forEach { it.visibility = if (CustomPosters.hideLabels) View.GONE else View.VISIBLE }
            samples.forEachIndexed { i, s ->
                slots[i].image.setImageDrawable(null)
                slots[i].note.text = "Loading…"
                scope.launch {
                    val url = tpl?.let { t ->
                        t.replace("{imdb_id}", s.imdbId)
                            .replace("{tmdb_id}", s.tmdbId.toString())
                            .replace("{anilist_id}", s.anilistId.toString())
                            .replace("{type}", if (s.isMovie) "movie" else "series")
                    } ?: s.defaultPoster
                    val bmp = try {
                        withContext(Dispatchers.IO) { downloadPoster(url) }
                    } catch (_: Exception) { null }
                    if (gen != generation) return@launch
                    if (bmp != null) {
                        slots[i].image.setImageBitmap(bmp)
                        slots[i].note.text = if (tpl != null) "Custom poster" else "Default poster"
                    } else {
                        slots[i].note.text = if (tpl != null) "Custom poster failed to load" else "Could not load poster"
                    }
                }
            }
        }

        val posterToggle = toggleRow(ctx, "Custom Poster", CustomPosters.enabled, d) { checked ->
            CustomPosters.enabled = checked
            val v = if (checked) View.VISIBLE else View.GONE
            posterCard.visibility = v; posterNote.visibility = v; placeholdersNote.visibility = v; chips.visibility = v
            reloadPreview()
        }
        CustomPosters.presets.forEach { (label, url) ->
            chips.addView(TextView(ctx).apply {
                text = label; textSize = 13f; setTextColor(cAccent)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 16 * d; setColor(cCard) }
                setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8.dp() }
                setOnClickListener { posterField.setText(url); reloadPreview() }
            })
        }

        layout.addView(sectionLabel(ctx, "CUSTOM POSTER", d))
        layout.addView(posterToggle)
        val posterVisibility = if (CustomPosters.enabled) View.VISIBLE else View.GONE
        posterCard.visibility = posterVisibility
        posterNote.visibility = posterVisibility
        placeholdersNote.visibility = posterVisibility
        chips.visibility = posterVisibility
        layout.addView(posterCard)
        layout.addView(posterNote)
        layout.addView(placeholdersNote)
        layout.addView(chips)

        layout.addView(sectionLabel(ctx, "LABELS", d))
        layout.addView(toggleRow(ctx, "Hide Labels", CustomPosters.hideLabels, d) { checked ->
            CustomPosters.hideLabels = checked
            reloadPreview()
        })
        layout.addView(descText(ctx, "No label below the posters on home rows and recommendations", d))

        layout.addView(sectionLabel(ctx, "PREVIEW", d))
        layout.addView(previewRow)
        reloadPreview()

        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll)
            .setPositiveButton("Save") { _, _ -> CustomPosters.template = posterField.text.toString() }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun downloadPoster(url: String): Bitmap? = try {
        app.baseClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) {
        null
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showExperimentalDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Experimental Features"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "May not work properly"; textSize = 11f; setTextColor(cWarning); setPadding(0, 0, 0, 12.dp()) })

        layout.addView(sectionLabel(ctx, "WATCH TRACKING", d))
        val watchTimeToggle = toggleRow(ctx, "Watch Time Tracker", RaghavAnimeFeatures.isEnabled("watch_time"), d) { checked ->
            if (!checked) {
                AlertDialog.Builder(ctx).setTitle("WARNING").setMessage("Anime Recommendations depends on Watch Time Tracker.\n\nIf you turn off Watch Time Tracker, recommendations will no longer update.\n\nAre you sure you want to turn it off?")
                    .setPositiveButton("Yes, turn off") { _, _ ->
                        RaghavAnimeFeatures.setEnabled("watch_time", false)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        (layout.findViewWithTag<SwitchCompat>("watch_time_toggle"))?.isChecked = true
                    }.show()
            } else {
                RaghavAnimeFeatures.setEnabled("watch_time", true)
            }
        }
        watchTimeToggle.tag = "watch_time_row"
        (watchTimeToggle.getChildAt(1) as? SwitchCompat)?.tag = "watch_time_toggle"
        layout.addView(watchTimeToggle)
        layout.addView(descText(ctx, "Tracks watch time per anime. Required for recommendations.", d))

        layout.addView(sectionLabel(ctx, "RECOMMENDATIONS", d))
        val recToggle = toggleRow(ctx, "Anime Recommendations", RaghavAnimeFeatures.isEnabled("recommendations"), d) { checked ->
            RaghavAnimeFeatures.setEnabled("recommendations", checked)
            if (!checked) {
                Toast.makeText(ctx, "Recommendations turned off. Homepage will not show recommendations.", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(recToggle)
        layout.addView(descText(ctx, "Shows recommended anime on homepage based on your watch history", d))

        layout.addView(Button(ctx).apply { text = "RESET RECOMMENDATIONS"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 8.dp())
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Reset Recommendations").setMessage("This will clear all recommendations from the homepage.\n\nNew recommendations will appear after you watch a new anime.\n\nContinue?")
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        RaghavAnimeFeatures.resetRecommendations()
                        Toast.makeText(ctx, "Recommendations cleared. Watch a new anime to generate new ones.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            } })

        layout.addView(Button(ctx).apply { text = "RESET WATCH HISTORY"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 8.dp())
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Reset Watch History").setMessage("This will delete ALL watch time data AND reset recommendations.\n\nAre you sure?")
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        RaghavAnimeFeatures.resetWatchHistory()
                        Toast.makeText(ctx, "Watch history reset", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            } })
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Save") { _, _ -> }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
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
    private fun AlertDialog.styleButtons() { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent); getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim) }
    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx).setTitle("Restart Required").setMessage("Restart the app to apply changes?").setPositiveButton("Yes") { _, _ -> restartApp() }.setNegativeButton("No") { _, _ -> try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} }.show() }
    private fun restartApp() { try { val context = requireContext().applicationContext; val pm = context.packageManager; val intent = pm.getLaunchIntentForPackage(context.packageName); val componentName = intent?.component; if (componentName != null) { val restartIntent = android.content.Intent.makeRestartActivityTask(componentName); context.startActivity(restartIntent); Runtime.getRuntime().exit(0) } } catch (_: Throwable) { try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} } }
}
