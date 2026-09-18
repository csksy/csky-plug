package com.animecomments

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredSettings(
    var baseUrl: String,
    var userName: String,
)

class CommentsSettings {
    private val urlKey = "animecomments_worker_url"
    private val nameKey = "animecomments_username"

    fun load(): StoredSettings {
        val url = getKey<String>(urlKey).orEmpty()
        val name = getKey<String>(nameKey).orEmpty().ifBlank {
            val generated = "Guest%04d".format((1000..9999).random())
            setKey(nameKey, generated)
            generated
        }
        return StoredSettings(url, name)
    }

    fun save(settings: StoredSettings) {
        setKey(urlKey, settings.baseUrl.trim())
        setKey(nameKey, settings.userName.trim().take(32))
    }
}

// The comments list for the episode currently playing. Native views only, no
// resources, so the whole plugin stays a plain dex.
class CommentsSheet(
    activity: Activity,
    private val ref: EpisodeRef,
    private val settings: CommentsSettings,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val listContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
    }

    private val statusLine = TextView(activity).apply {
        text = "Loading..."
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    fun show() {
        val stored = settings.load()

        val title = TextView(dialog.context).apply {
            text = CommentsApi.labelOf(ref)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(2))
        }
        val subtitle = TextView(dialog.context).apply {
            text = "${ref.apiName} - episode comment thread"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(16), 0, dp(16), dp(6))
        }

        val scroll = android.widget.ScrollView(dialog.context).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val input = EditText(dialog.context).apply {
            hint = if (stored.baseUrl.isBlank()) "Set the worker URL in extension settings first" else "Write a comment..."
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val send = android.widget.Button(dialog.context).apply {
            text = "Send"
        }
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (stored.baseUrl.isBlank()) {
                CommonActivity.showToast("Open extension settings and set the worker URL", 0)
                return@setOnClickListener
            }
            input.setText("")
            send.isEnabled = false
            scope.launch {
                val result = CommentsApi.postComment(
                    CommentsApi.normalizeBase(stored.baseUrl),
                    CommentsApi.keyOf(ref),
                    stored.userName,
                    text,
                )
                withContext(Dispatchers.Main) {
                    send.isEnabled = true
                    if (result != null) {
                        render(result)
                    } else {
                        CommonActivity.showToast("Send failed, try again in a moment", 0)
                    }
                }
            }
        }

        val inputRow = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }

        val root = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            addView(scroll)
            addView(statusLine)
            addView(inputRow)
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()

        refresh(stored)
    }

    private fun refresh(stored: StoredSettings) {
        if (stored.baseUrl.isBlank()) {
            render(emptyList())
            statusLine.text = "No worker configured. Open AnimeComments settings to add one."
            return
        }
        scope.launch {
            val comments = CommentsApi.loadComments(
                CommentsApi.normalizeBase(stored.baseUrl),
                CommentsApi.keyOf(ref),
            )
            withContext(Dispatchers.Main) { render(comments) }
        }
    }

    private fun render(comments: List<CommentEntry>) {
        listContainer.removeAllViews()
        if (comments.isEmpty()) {
            statusLine.text = "No comments yet. Be the first."
            return
        }
        statusLine.text = "${comments.size} comment${if (comments.size == 1) "" else "s"}"
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        for (comment in comments.asReversed()) {
            val head = TextView(listContainer.context).apply {
                text = "${comment.u}  -  ${fmt.format(Date(comment.ts))}"
                textSize = 11f
                setTextColor(Color.rgb(140, 150, 170))
                setPadding(dp(16), dp(8), dp(16), 0)
            }
            val body = TextView(listContainer.context).apply {
                text = comment.t
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(16), 0, dp(16), dp(6))
            }
            listContainer.addView(head)
            listContainer.addView(body)
        }
    }

    private fun dp(value: Int): Int =
        (value * listContainer.resources.displayMetrics.density).toInt()
}
