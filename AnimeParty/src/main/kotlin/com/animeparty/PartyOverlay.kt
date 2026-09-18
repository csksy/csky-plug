package com.animeparty

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity

private const val POLL_MS = 700L

// A small party button over the player that opens the room sheet while a room
// is active, plus a side chat panel so talking never covers the whole video.
class PartyOverlay(
    private val manager: PartyManager,
    private val onOpenControls: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var fabHost: FrameLayout? = null
    private var chatHost: FrameLayout? = null
    private var attachedActivity: Activity? = null
    private var chatOpen = false
    private var running = false

    private val chatLog = mutableListOf<Pair<String, String>>()
    private var chatList: LinearLayout? = null
    private var chatScroller: ScrollView? = null

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

    fun appendChat(sender: String, text: String) {
        chatLog.add(sender to text)
        if (chatLog.size > 150) chatLog.removeAt(0)
        if (chatOpen) renderChatTail()
    }

    fun systemBubble(text: String) {
        appendChat("", text)
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detach()
            return
        }
        if (!PartyProbe.isPlayerActive()) {
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

        val glyph = ImageView(activity).apply {
            setImageDrawable(Glyphs.icon(Glyphs.GROUPS, Color.rgb(255, 193, 94)))
        }

        val size = dp(activity, 48)
        val host = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(activity, 18)
                bottomMargin = dp(activity, 152)
            }
            background = Glyphs.backing(ringWidthPx = dp(activity, 1))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(activity, 5).toFloat()
            isClickable = true
            setOnClickListener {
                if (manager.role != PartyManager.Role.IDLE) toggleChat(activity) else onOpenControls()
            }
            setOnLongClickListener {
                onOpenControls()
                true
            }
        }
        host.addView(glyph, FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24), Gravity.CENTER))

        decor.addView(host)
        fabHost = host
        attachedActivity = activity
    }

    private fun toggleChat(activity: Activity) {
        if (chatOpen) {
            closeChat()
        } else {
            openChat(activity)
        }
    }

    private fun openChat(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
        }
        val scroller = ScrollView(activity).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val input = EditText(activity).apply {
            hint = "Message"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
        }
        val send = TextView(activity).apply {
            text = "Send"
            setTextColor(Color.rgb(255, 183, 77))
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setOnClickListener
                input.setText("")
                manager.sendChat(text)
            }
        }
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(230, 14, 16, 24))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 10, 11, 17))
        }
        panel.addView(scroller)
        panel.addView(inputRow)

        val width = (activity.resources.displayMetrics.widthPixels * 0.44f).toInt()
        val host = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        decor.addView(host)
        chatHost = host
        chatList = list
        chatScroller = scroller
        chatOpen = true
        renderChatTail()
    }

    fun closeChat() {
        val decor = attachedActivity?.window?.decorView as? ViewGroup
        chatHost?.let { decor?.removeView(it) }
        chatHost = null
        chatList = null
        chatScroller = null
        chatOpen = false
    }

    private fun renderChatTail() {
        val list = chatList ?: return
        list.removeAllViews()
        for ((sender, text) in chatLog.takeLast(40)) {
            val bubble = TextView(list.context).apply {
                textSize = 13f
                setPadding(dp(activityOf(list), 12), dp(activityOf(list), 5), dp(activityOf(list), 12), dp(activityOf(list), 5))
                if (sender.isBlank()) {
                    this.text = text
                    setTextColor(Color.rgb(150, 160, 180))
                    textSize = 12f
                } else {
                    this.text = "$sender\n$text"
                    setTextColor(Color.WHITE)
                }
            }
            list.addView(bubble)
        }
        chatScroller?.post { chatScroller?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun activityOf(view: View): Activity {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return attachedActivity ?: CommonActivity.activity!!
    }

    private fun detach() {
        closeChat()
        val decor = attachedActivity?.window?.decorView as? ViewGroup
        fabHost?.let { decor?.removeView(it) }
        fabHost = null
        attachedActivity = null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
