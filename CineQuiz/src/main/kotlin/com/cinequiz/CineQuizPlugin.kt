package com.cinequiz

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlin.random.Random

@CloudstreamPlugin
class CineQuizPlugin : Plugin() {
    private val urlKey = "cinequiz_worker_url"
    private val nameKey = "cinequiz_username"

    private var game: QuizGame? = null

    override fun load(context: Context) {
        openSettings = { openLauncher() }
    }

    override fun beforeUnload() {
        game?.release()
    }

    private fun workerUrl(): String =
        getKey<String>(urlKey).orEmpty().trim().trimEnd('/')

    private fun displayName(): String {
        val stored = getKey<String>(nameKey).orEmpty()
        if (stored.isNotBlank()) return stored
        val generated = "Player%04d".format(Random.nextInt(1000, 9999))
        setKey(nameKey, generated)
        return generated
    }

    private fun openLauncher() {
        val activity = CommonActivity.activity ?: return

        val pad = dp(activity, 18)
        val title = TextView(activity).apply {
            text = "CineQuiz"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(activity, 4))
        }
        val intro = TextView(activity).apply {
            text = "Movie, anime and general entertainment trivia. Solo works offline of any " +
                "server, multiplayer rooms (up to 5) need the cs-social-hub worker URL."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(activity, 10))
        }

        fun styledButton(label: String): Button = Button(activity).apply {
            text = label
            setAllCaps(false)
        }

        val solo = styledButton("Play solo")
        val host = styledButton("Host a room")
        val join = styledButton("Join a room")
        val settings = styledButton("Settings")

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(title)
            addView(intro)
            addView(solo)
            addView(host)
            addView(join)
            addView(settings)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        solo.setOnClickListener { dialog.dismiss(); pickRounds(activity) }
        host.setOnClickListener { dialog.dismiss(); hostFlow(activity) }
        join.setOnClickListener { dialog.dismiss(); joinFlow(activity) }
        settings.setOnClickListener { dialog.dismiss(); showSettings(activity) }

        dialog.show()
    }

    private fun pickRounds(activity: Activity) {
        val options = arrayOf("5 questions", "10 questions", "20 questions")
        AlertDialog.Builder(activity)
            .setTitle("Solo game length")
            .setItems(options) { _, which ->
                val rounds = listOf(5, 10, 20)[which]
                startSolo(activity, rounds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSolo(activity: Activity, rounds: Int) {
        game?.release()
        game = QuizGame(activity, "", displayName()) { game = null }
        game?.startSolo(rounds)
    }

    private fun hostFlow(activity: Activity) {
        val base = workerUrl()
        if (base.isBlank()) {
            CommonActivity.showToast("Set the worker URL in Settings first", 0)
            showSettings(activity)
            return
        }

        val pin = (100000..999999).random(Random(System.nanoTime())).toString()
        val pad = dp(activity, 18)

        val status = TextView(activity).apply {
            text = "Room $pin\nShare this code, waiting for players..."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(activity, 8))
        }
        val roundChoices = arrayOf("5 questions", "10 questions", "20 questions")
        var rounds = 10

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Host a quiz room")
            .setSingleChoiceItems(roundChoices, 1) { _, which -> rounds = listOf(5, 10, 20)[which] }
            .setView(container)
            .setPositiveButton("Start game") { _, _ ->
                game?.release()
                game = QuizGame(activity, base, displayName()) { game = null }
                game?.hostRoom(pin, rounds) { }
                game?.beginHostedGame()
            }
            .setNegativeButton("Cancel") { _, _ -> game?.release() }
            .create()

        game?.release()
        game = QuizGame(activity, base, displayName()) { game = null }
        game?.hostRoom(pin, rounds) { count ->
            status.text = "Room $pin\n$count player(s) connected"
        }
        dialog.show()
    }

    private fun joinFlow(activity: Activity) {
        val base = workerUrl()
        if (base.isBlank()) {
            CommonActivity.showToast("Set the worker URL in Settings first", 0)
            showSettings(activity)
            return
        }

        val pad = dp(activity, 18)
        val input = EditText(activity).apply {
            hint = "6 digit room code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        val container = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle("Join a quiz room")
            .setView(container)
            .setPositiveButton("Join") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 6) {
                    CommonActivity.showToast("Room codes are 6 digits", 0)
                    return@setPositiveButton
                }
                game?.release()
                game = QuizGame(activity, base, displayName()) { game = null }
                game?.joinRoom(pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings(activity: Activity) {
        val pad = dp(activity, 18)
        val urlInput = EditText(activity).apply {
            hint = "https://cs-social-hub.yourname.workers.dev"
            setText(workerUrl())
            isSingleLine = true
        }
        val nameInput = EditText(activity).apply {
            hint = "Display name"
            setText(displayName())
            isSingleLine = true
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlInput)
            addView(nameInput)
        }

        AlertDialog.Builder(activity)
            .setTitle("CineQuiz settings")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                setKey(urlKey, urlInput.text.toString().trim().trimEnd('/'))
                setKey(nameKey, nameInput.text.toString().trim().take(32).ifBlank { displayName() })
                CommonActivity.showToast("Saved", 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
