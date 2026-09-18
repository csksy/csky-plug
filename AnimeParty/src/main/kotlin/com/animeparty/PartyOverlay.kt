package com.animeparty

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.drawable.shapes.PathShape
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

        val size = dp(activity, 46)
        val host = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(activity, 18)
                bottomMargin = dp(activity, 150)
            }
        }

        val button = ImageView(activity).apply {
            setImageDrawable(partyGlyph(activity))
            background = ShapeDrawable(OvalShape()).apply { paint.color = Color.argb(200, 24, 18, 43) }
            setPadding(6, 6, 6, 6)
            setOnClickListener {
                if (manager.role != PartyManager.Role.IDLE) toggleChat(activity) else onOpenControls()
            }
            setOnLongClickListener {
                onOpenControls()
                true
            }
        }
        host.addView(button, FrameLayout.LayoutParams(size - 8, size - 8, Gravity.CENTER))

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

    // two silhouettes in front of a play triangle, drawn by hand so the
    // plugin needs no image resources
    private fun partyGlyph(activity: Activity): ShapeDrawable {
        val path = Path().apply {
            moveTo(14f, 16f)
            quadTo(20f, 8f, 26f, 16f)
            lineTo(24f, 22f)
            lineTo(16f, 22f)
            close()
            moveTo(28f, 16f)
            quadTo(34f, 8f, 40f, 16f)
            lineTo(38f, 22f)
            lineTo(30f, 22f)
            close()
            moveTo(12f, 28f)
            lineTo(12f, 44f)
            lineTo(24f, 36f)
            lineTo(12f, 28f)
            close()
            moveTo(28f, 28f)
            lineTo(28f, 46f)
            lineTo(44f, 46f)
            lineTo(44f, 28f)
            close()
        }
        return ShapeDrawable(PathShape(path, 56f, 50f)).apply {
            paint.color = Color.rgb(255, 183, 77)
            paint.style = Paint.Style.FILL
        }
    }
}
