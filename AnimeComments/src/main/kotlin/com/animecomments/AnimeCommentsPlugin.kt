package com.animecomments

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@CloudstreamPlugin
class AnimeCommentsPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = CommentsSettings()
    private lateinit var overlay: CommentsOverlay

    override fun load(context: Context) {
        overlay = CommentsOverlay(settings, scope)
        overlay.start()
        openSettings = { openSettingsSheet() }
    }

    override fun beforeUnload() {
        overlay.stop()
    }

    private fun openSettingsSheet() {
        val activity = CommonActivity.activity ?: return
        val stored = settings.load()

        val pad = dp(activity, 18)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val intro = TextView(activity).apply {
            text = "Comments are stored on your own free Cloudflare worker. " +
                "Deploy cs-social-hub from the repo, then paste its URL here."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(activity, 12))
        }

        val urlInput = EditText(activity).apply {
            hint = "https://cs-social-hub.yourname.workers.dev"
            setText(stored.baseUrl)
            isSingleLine = true
        }
        val nameInput = EditText(activity).apply {
            hint = "Display name"
            setText(stored.userName)
            isSingleLine = true
        }

        container.addView(intro)
        container.addView(urlInput)
        container.addView(nameInput)

        AlertDialog.Builder(activity)
            .setTitle("AnimeComments")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.save(
                    StoredSettings(
                        baseUrl = urlInput.text.toString(),
                        userName = nameInput.text.toString(),
                    )
                )
                CommonActivity.showToast("Saved", 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
