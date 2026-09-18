package com.animeparty

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@CloudstreamPlugin
class AnimePartyPlugin : Plugin() {
    private val urlKey = "animeparty_worker_url"
    private val nameKey = "animeparty_username"

    private val mapper = ObjectMapper()
    private val scope = CoroutineScope(Dispatchers.Main)

    private val manager = PartyManager(
        onUiState = { refreshStatusLine() },
        onChat = { sender, text -> overlay.appendChat(sender, text) },
        onSystem = { text -> overlay.systemBubble(text) },
    )

    private lateinit var overlay: PartyOverlay
    private var statusLine: TextView? = null
    private var currentDialog: AlertDialog? = null

    override fun load(context: Context) {
        overlay = PartyOverlay(manager) { openControls() }
        manager.nameProvider = { displayName() }
        overlay.start()
        openSettings = { openControls() }
    }

    override fun beforeUnload() {
        manager.release()
        overlay.stop()
    }

    private fun workerUrl(): String =
        getKey<String>(urlKey).orEmpty().trim().trimEnd('/')

    private fun displayName(): String {
        val stored = getKey<String>(nameKey).orEmpty()
        if (stored.isNotBlank()) return stored
        val generated = "Guest%04d".format(Random.nextInt(1000, 9999))
        setKey(nameKey, generated)
        return generated
    }

    private fun refreshStatusLine() {
        val line = statusLine ?: return
        line.post {
            val role = when (manager.role) {
                PartyManager.Role.HOST -> "Host"
                PartyManager.Role.GUEST -> "Guest"
                else -> "Not in a room"
            }
            val pin = manager.currentPin ?: "-"
            val others = manager.participants.size
            line.text = "Status: ${manager.statusText.ifBlank { role }}\n" +
                "Room: $pin   Role: $role   Others: $others" +
                if (manager.roomLocked) "   Locked" else ""
        }
    }

    private fun openControls() {
        val activity = CommonActivity.activity ?: return
        currentDialog?.dismiss()

        val pad = dp(activity, 18)
        val status = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(activity, 10))
        }
        statusLine = status

        fun action(label: String): Button = Button(activity).apply {
            text = label
            setAllCaps(false)
        }

        val create = action("Create a room")
        val join = action("Join by code")
        val browse = action("Browse public rooms")
        val resync = action("Resync everyone")
        val hint = action("Share what I'm playing")
        val lock = action("Lock room")
        val leave = action("Leave room")
        val settings = action("Settings")

        val inRoom = manager.role != PartyManager.Role.IDLE

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
            addView(create)
            addView(join)
            addView(browse)
            if (inRoom) {
                addView(resync)
                addView(hint)
                addView(lock)
                addView(leave)
            }
            addView(settings)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("AnimeParty")
            .setView(container)
            .setOnDismissListener { if (statusLine === status) statusLine = null }
            .setNegativeButton("Close", null)
            .create()
        currentDialog = dialog

        create.setOnClickListener { dialog.dismiss(); createFlow(activity) }
        join.setOnClickListener { dialog.dismiss(); joinFlow(activity) }
        browse.setOnClickListener { dialog.dismiss(); browseFlow(activity) }
        resync.setOnClickListener { manager.requestResync(); refreshStatusLine() }
        hint.setOnClickListener {
            manager.sendEpisodeHint()
            CommonActivity.showToast("Told the room what you are playing", 0)
        }
        lock.setOnClickListener {
            manager.setLock(!manager.roomLocked)
        }
        leave.setOnClickListener { dialog.dismiss(); manager.leaveRoom() }
        settings.setOnClickListener { dialog.dismiss(); showSettings(activity) }

        dialog.show()
        refreshStatusLine()
    }

    private fun createFlow(activity: Activity) {
        scope.launch {
            val base = WorkerEndpoint.resolve(workerUrl())
            if (base.isBlank()) {
                CommonActivity.showToast("No party server yet, check Settings", 0)
                showSettings(activity)
                return@launch
            }
            val pin = (100000..999999).random(Random(System.nanoTime())).toString()
            manager.createRoom(base, pin) { displayName() }
            CommonActivity.showToast("Room $pin created, share the code", 1)
            openControls()
        }
    }

    private fun joinFlow(activity: Activity) {
        scope.launch {
            val base = WorkerEndpoint.resolve(workerUrl())
            if (base.isBlank()) {
                CommonActivity.showToast("No party server yet, check Settings", 0)
                showSettings(activity)
                return@launch
            }
            showJoinDialog(activity, base)
        }
    }

    private fun showJoinDialog(activity: Activity, base: String) {
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
            .setTitle("Join a watch party")
            .setView(container)
            .setPositiveButton("Join") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 6) {
                    CommonActivity.showToast("Room codes are 6 digits", 0)
                    return@setPositiveButton
                }
                manager.joinRoom(base, pin) { displayName() }
                openControls()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun browseFlow(activity: Activity) {
        val pad = dp(activity, 18)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
        }
        val loading = TextView(activity).apply {
            text = "Loading rooms..."
            setTextColor(Color.LTGRAY)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        list.addView(loading)

        val scroll = android.widget.ScrollView(activity).apply { addView(list) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Public watch parties")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        scope.launch {
            val base = WorkerEndpoint.resolve(workerUrl())
            val rooms = if (base.isBlank()) emptyList() else fetchRooms(base)
            withContext(Dispatchers.Main) {
                list.removeAllViews()
                if (base.isBlank()) {
                    list.addView(TextView(activity).apply {
                        text = "No party server yet, check back later."
                        setTextColor(Color.LTGRAY)
                        setPadding(pad, pad / 2, pad, pad / 2)
                    })
                } else if (rooms.isEmpty()) {
                    list.addView(TextView(activity).apply {
                        text = "No active rooms right now. Create one and it shows up here for everyone."
                        setTextColor(Color.LTGRAY)
                        setPadding(pad, pad / 2, pad, pad / 2)
                    })
                }
                for (room in rooms) {
                    val entry = TextView(activity).apply {
                        text = "${room.title}\n${room.count}/5 players   code ${room.pin}" +
                            (if (room.host.isNotBlank()) "   by ${room.host}" else "")
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(pad, pad / 3, pad, pad / 3)
                        setOnClickListener {
                            dialog.dismiss()
                            manager.joinRoom(base, room.pin) { displayName() }
                            openControls()
                        }
                    }
                    list.addView(entry)
                    list.addView(ViewDivider(activity))
                }
            }
        }

        dialog.show()
    }

    private fun ViewDivider(activity: Activity): android.view.View =
        android.view.View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1)
            ).apply { setMargins(dp(activity, 18), 0, dp(activity, 18), 0) }
            setBackgroundColor(Color.rgb(35, 40, 58))
        }

    private data class PublicRoom(
        val pin: String = "",
        val title: String = "",
        val count: Int = 0,
        val host: String = "",
    )

    private suspend fun fetchRooms(base: String): List<PublicRoom> {
        val body = runCatching { app.get("$base/rooms").text }.getOrNull() ?: return emptyList()
        return runCatching {
            mapper.readValue(body, object : TypeReference<List<PublicRoom>>() {})
        }.getOrNull() ?: emptyList()
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
        val intro = TextView(activity).apply {
            text = "Rooms run through one free Cloudflare worker shared by everyone on " +
                "this repo. Leave the URL empty to use the shared server, set your own " +
                "only if you self host. Media never passes through it, only play, pause, " +
                "seek and chat messages."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(activity, 10))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(intro)
            addView(urlInput)
            addView(nameInput)
        }

        AlertDialog.Builder(activity)
            .setTitle("AnimeParty settings")
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
