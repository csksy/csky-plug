package com.animecomments

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ICON_VISIBLE_MS = 5000L
private const val POLL_MS = 700L

// A small bubble over the player that hides after a few seconds and comes
// back on any tap, plus the comments sheet it opens. The tap observer is a
// plain transparent decorView child that never consumes events, so the player
// below it keeps working exactly as before.
class CommentsOverlay(
    private val settings: CommentsSettings,
    private val scope: CoroutineScope,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var iconHost: FrameLayout? = null
    private var tapObserver: View? = null
    private var attachedActivity: Activity? = null
    private var sheetOpen = false
    private var running = false

    private val showIcon = Runnable {
        iconHost?.visibility = View.VISIBLE
    }

    private val hideIcon = Runnable {
        if (!sheetOpen) iconHost?.visibility = View.GONE
    }

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
        handler.removeCallbacks(showIcon)
        handler.removeCallbacks(hideIcon)
        detach()
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detach()
            return
        }

        val playerActive = PlayerProbe.isPlayerActive()
        if (!playerActive) {
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
            setImageDrawable(bubbleDrawable(activity))
            background = circleBacking(activity)
            setPadding(8, 8, 8, 8)
            setOnClickListener { openSheet(activity) }
        }

        val size = dp(activity, 46)
        val host = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(activity, 18)
                bottomMargin = dp(activity, 96)
            }
            addView(icon, FrameLayout.LayoutParams(size - 8, size - 8, Gravity.CENTER))
        }

        val observer = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    pokeIcon()
                }
                false
            }
        }

        decor.addView(host)
        decor.addView(observer)
        iconHost = host
        tapObserver = observer
        attachedActivity = activity
        pokeIcon()
    }

    private fun detach() {
        val decor = attachedActivity?.window?.decorView as? ViewGroup
        iconHost?.let { decor?.removeView(it) }
        tapObserver?.let { decor?.removeView(it) }
        iconHost = null
        tapObserver = null
        attachedActivity = null
    }

    private fun pokeIcon() {
        handler.removeCallbacks(showIcon)
        handler.removeCallbacks(hideIcon)
        iconHost?.visibility = View.VISIBLE
        handler.postDelayed(hideIcon, ICON_VISIBLE_MS)
    }

    private fun openSheet(activity: Activity) {
        if (sheetOpen) return
        val meta = PlayerProbe.currentEpisode()
        val ref = meta?.let { CommentsApi.episodeOf(it) }
        if (ref == null) {
            CommonActivity.showToast("Nothing playing to comment on", 0)
            return
        }
        sheetOpen = true
        handler.removeCallbacks(hideIcon)
        try {
            CommentsSheet(activity, ref, settings, scope) {
                sheetOpen = false
                pokeIcon()
            }.show()
        } catch (t: Throwable) {
            t.printStackTrace()
            sheetOpen = false
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun circleBacking(activity: Activity): ShapeDrawable =
        ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = Color.argb(200, 20, 21, 27)
        }

    // a simple hand drawn speech bubble so the plugin ships no image assets
    private fun bubbleDrawable(activity: Activity): ShapeDrawable {
        val path = Path().apply {
            moveTo(6f, 4f)
            lineTo(54f, 4f)
            quadTo(58f, 4f, 58f, 8f)
            lineTo(58f, 32f)
            quadTo(58f, 36f, 54f, 36f)
            lineTo(24f, 36f)
            lineTo(14f, 46f)
            lineTo(16f, 36f)
            lineTo(6f, 36f)
            quadTo(2f, 36f, 2f, 32f)
            lineTo(2f, 8f)
            quadTo(2f, 4f, 6f, 4f)
            close()
        }
        return ShapeDrawable(PathShape(path, 60f, 50f)).apply {
            paint.color = Color.rgb(235, 240, 255)
            paint.style = Paint.Style.FILL
        }
    }
}
