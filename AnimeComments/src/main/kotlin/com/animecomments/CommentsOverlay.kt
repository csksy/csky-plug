package com.animecomments

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope

private const val POLL_MS = 700L

// A tidy comment button pinned over the player's bottom right corner while a
// player is open. It stays visible the whole time so it is easy to find, and
// opens the thread for the exact episode that is playing. Native views only,
// no resources, so the whole plugin stays a plain dex.
class CommentsOverlay(
    private val settings: CommentsSettings,
    private val scope: CoroutineScope,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var iconHost: FrameLayout? = null
    private var attachedActivity: Activity? = null
    private var sheetOpen = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sync()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        detach()
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detach()
            return
        }

        if (!PlayerProbe.isPlayerActive()) {
            detach()
            return
        }

        if (attachedActivity !== activity) {
            detach()
            attach(activity)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return

        val icon = ImageView(activity).apply {
            setImageDrawable(Glyphs.icon(Glyphs.CHAT_BUBBLE, Color.rgb(240, 245, 255)))
        }

        val size = dp(activity, 48)
        val host = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(activity, 18)
                bottomMargin = dp(activity, 96)
            }
            background = Glyphs.backing(ringWidthPx = dp(activity, 1))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(activity, 5).toFloat()
            isClickable = true
            setOnClickListener { openSheet(activity) }
        }
        host.addView(icon, FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24), Gravity.CENTER))

        decor.addView(host)
        iconHost = host
        attachedActivity = activity
    }

    private fun detach() {
        val decor = attachedActivity?.window?.decorView as? ViewGroup
        iconHost?.let { decor?.removeView(it) }
        iconHost = null
        attachedActivity = null
    }

    private fun openSheet(activity: Activity) {
        if (sheetOpen) return
        val meta = PlayerProbe.currentEpisode()
        val ref = meta?.let { CommentsApi.episodeOf(it) }
        if (ref == null) {
            CommonActivity.showToast("Play an episode first, comments follow what is playing", 0)
            return
        }
        sheetOpen = true
        try {
            CommentsSheet(activity, ref, settings, scope) {
                sheetOpen = false
            }.show()
        } catch (t: Throwable) {
            t.printStackTrace()
            sheetOpen = false
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
