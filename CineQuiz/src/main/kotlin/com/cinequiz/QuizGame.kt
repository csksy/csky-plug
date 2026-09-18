package com.cinequiz

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import com.lagradost.cloudstream3.CommonActivity
import org.json.JSONObject
import java.util.UUID

// Fullscreen quiz screen plus the game wiring. One controller covers solo
// play (no socket), hosting (engine plus relay) and joining (relay only).
class QuizGame(
    private val activity: Activity,
    private val baseUrl: String,
    private val myName: String,
    private val onFinished: () -> Unit,
) {
    private var webView: WebView? = null
    private var dialog: Dialog? = null
    private var engine: QuizEngine? = null
    private var socket: QuizSocket? = null
    private val myId = UUID.randomUUID().toString()
    private var isHost = false
    private var joinedPin: String? = null
    private val roster = LinkedHashMap<String, String>()
    private var rounds = 10

    fun startSolo(rounds: Int) {
        this.rounds = rounds
        isHost = false
        showDialog()
        createEngine()
        engine?.configure(listOf(myName), rounds, myName)
        engine?.start()
    }

    fun hostRoom(pin: String, rounds: Int, onStart: (Int) -> Unit) {
        this.rounds = rounds
        isHost = true
        joinedPin = pin
        connect(pin)
        // the sheet stays visible while waiting for players, the game view
        // opens when the host presses start
        onStartCallback = onStart
    }

    fun beginHostedGame() {
        showDialog()
        createEngine()
        val names = listOf(myName) + roster.values.filter { it != myName }
        engine?.configure(names, rounds, myName)
        engine?.start()
    }

    fun joinRoom(pin: String) {
        isHost = false
        joinedPin = pin
        showDialog()
        pushState(
            JSONObject().put("phase", "lobby").put("message", "Room $pin joined, waiting for the host to start")
        )
        connect(pin)
    }

    private var onStartCallback: ((Int) -> Unit)? = null

    fun playerCount(): Int = roster.size + 1

    private fun connect(pin: String) {
        val wsBase = baseUrl.trim().trimEnd('/')
        socket = QuizSocket(
            baseWsUrl = wsBase,
            clientId = myId,
            onOpen = {
                activity.runOnUiThread {
                    socket?.send(JSONObject().put("type", "HELLO").put("name", myName))
                    onStartCallback?.invoke(playerCount())
                }
            },
            onMessage = { msg -> activity.runOnUiThread { handleRelay(msg) } },
            onClosed = { _, _ ->
                activity.runOnUiThread {
                    if (dialog?.isShowing == true) {
                        pushState(JSONObject().put("phase", "lobby").put("message", "Disconnected from the room"))
                    }
                }
            },
            onFailure = { detail ->
                activity.runOnUiThread {
                    if (dialog?.isShowing == true) {
                        pushState(JSONObject().put("phase", "lobby").put("message", "Connection problem: $detail"))
                    } else {
                        onStartCallback?.let { CommonActivity.showToast("Quiz room: $detail", 0) }
                    }
                }
            },
        ).also { it.connect(pin) }
    }

    private fun handleRelay(msg: JSONObject) {
        val type = msg.optString("type")
        when (type) {
            "ROOM_STATE" -> {
                val peers = msg.optJSONArray("roster") ?: return
                for (i in 0 until peers.length()) {
                    val peer = peers.optJSONObject(i) ?: continue
                    val cid = peer.optString("cid")
                    if (cid != myId) roster[cid] = "Player"
                }
                onStartCallback?.invoke(playerCount())
            }

            "HELLO" -> {
                val cid = msg.optString("cid")
                val name = msg.optString("name").ifBlank { "Player" }
                if (cid != myId) roster[cid] = name
                onStartCallback?.invoke(playerCount())
            }

            "PEER_LEFT" -> {
                roster.remove(msg.optString("cid"))
                onStartCallback?.invoke(playerCount())
            }

            "QUIZ_ANSWER" -> {
                if (!isHost) return
                val name = roster[msg.optString("cid")] ?: return
                engine?.onRemoteAnswer(name, msg.optInt("round", -1), msg.optInt("choice", -1))
            }

            "QUIZ_QUESTION", "QUIZ_REVEAL", "QUIZ_END" -> {
                if (isHost) return
                val copy = JSONObject(msg.toString()).apply { remove("type") }
                if (type == "QUIZ_END") {
                    pushState(JSONObject().put("phase", "end").put("scores", msg.optJSONObject("scores")))
                } else {
                    pushState(copy)
                }
            }
        }
    }

    private fun createEngine() {
        engine?.release()
        engine = QuizEngine(
            onState = { state -> pushState(state) },
            onSend = { payload ->
                if (isHost) socket?.send(payload)
            },
        )
    }

    private fun pushState(state: JSONObject) {
        val view = webView ?: return
        val escaped = state.toString().replace("\\", "\\\\").replace("'", "\\'")
        view.post {
            view.evaluateJavascript("window.onState('$escaped');", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog() {
        if (dialog?.isShowing == true) return

        val fullscreen = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        fullscreen.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(activity)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.setBackgroundColor(Color.rgb(11, 13, 20))

        val view = WebView(activity)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(Color.rgb(11, 13, 20))
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true

        view.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onAnswer(choice: Int) {
                    activity.runOnUiThread {
                        if (isHost || engine != null) {
                            engine?.onLocalAnswer(choice)
                        } else {
                            socket?.send(
                                JSONObject()
                                    .put("type", "QUIZ_ANSWER")
                                    .put("round", currentGuestRound)
                                    .put("choice", choice)
                            )
                        }
                    }
                }

                @JavascriptInterface
                fun onRestart() {
                    activity.runOnUiThread {
                        if (isHost || engine != null) {
                            createEngine()
                            engine?.configure(listOf(myName) + roster.values.filter { it != myName }, rounds, myName)
                            engine?.start()
                        }
                    }
                }

                @JavascriptInterface
                fun onClose() {
                    activity.runOnUiThread { dialog?.dismiss() }
                }
            },
            "AndroidApp",
        )

        view.loadDataWithBaseURL("https://cinequiz.local/", QuizHtml.html, "text/html", "utf-8", null)

        root.addView(view)
        fullscreen.setContentView(root)
        fullscreen.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                fullscreen.dismiss()
                true
            } else {
                false
            }
        }
        fullscreen.setOnDismissListener {
            release()
            onFinished()
        }

        dialog = fullscreen
        webView = view
        fullscreen.show()

        if (Build.VERSION.SDK_INT >= 30) {
            fullscreen.window?.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            fullscreen.window?.decorView?.systemUiVisibility = 5894
        }
    }

    private var currentGuestRound = -1

    fun release() {
        engine?.release()
        engine = null
        socket?.close()
        socket = null
        roster.clear()
        webView?.destroy()
        webView = null
        dialog = null
    }

    companion object {
        fun withActivity(action: (Activity) -> Unit) {
            val activity = CommonActivity.activity ?: return
            action(activity)
        }
    }
}
