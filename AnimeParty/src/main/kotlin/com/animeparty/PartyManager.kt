package com.animeparty

import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

// Room state and playback sync for up to 5 people. Local player changes are
// detected by polling and pushed to the room, remote commands are applied
// through the same player api. A seek gate pauses everyone until each side
// reports ready so nobody races ahead after a jump.
class PartyManager(
    private val onUiState: () -> Unit,
    private val onChat: (String, String) -> Unit,
    private val onSystem: (String) -> Unit,
) {
    enum class Role { IDLE, HOST, GUEST }

    companion object {
        const val MAX_PARTICIPANTS = 5
        private const val POLL_MS = 200L
        private const val ECHO_WINDOW_MS = 500L
        private const val SEEK_JUMP_MS = 1200L
        private const val RESYNC_THRESHOLD_MS = 1500L
        private const val HEARTBEAT_MS = 6000L
        private const val SEEK_DEBOUNCE_MS = 220L
        private const val GATE_TIMEOUT_MS = 9000L
        private const val LOCAL_READY_MS = 900L
    }

    val myClientId = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var role = Role.IDLE
        private set
    var currentPin: String? = null
        private set
    val participants = LinkedHashMap<String, String>()
    var hostCid: String? = null
        private set
    var roomLocked = false
        private set

    var statusText: String = ""
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var socket: PartySocket? = null
    private var relayBase = ""
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wantConnected = false
    private var reconnectAttempt = 0

    @Volatile private var lastRemoteCommandMs = 0L
    private var lastKnownPlaying: Boolean? = null
    private var lastKnownPosition = 0L
    private var lastRoomLabel: String? = null

    private var gateActive = false
    private var gateGeneration = 0
    private var gateExpectedPlaying = true
    private var gateReadyCount = 0
    private var gatePeers = 0

    val isConnected: Boolean get() = socket?.isOpen == true

    fun displayName(stored: () -> String): String = stored().ifBlank { "Guest" }

    fun createRoom(base: String, pin: String, name: () -> String) {
        relayBase = base.trim().trimEnd('/')
        role = Role.HOST
        currentPin = pin
        wantConnected = true
        reconnectAttempt = 0
        connect(pin, name)
        startPolling()
        startHeartbeat()
    }

    fun joinRoom(base: String, pin: String, name: () -> String) {
        relayBase = base.trim().trimEnd('/')
        role = Role.GUEST
        currentPin = pin
        wantConnected = true
        reconnectAttempt = 0
        connect(pin, name)
        startPolling()
        startHeartbeat()
    }

    fun leaveRoom() {
        if (isConnected) socket?.send(JSONObject().put("type", "LEAVE_ROOM"))
        release()
    }

    fun release() {
        wantConnected = false
        pollJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close()
        socket = null
        role = Role.IDLE
        currentPin = null
        participants.clear()
        hostCid = null
        roomLocked = false
        lastKnownPlaying = null
        lastKnownPosition = 0L
        lastRoomLabel = null
        gateActive = false
        gateGeneration++
        updateStatus("")
        onUiState()
    }

    private fun updateStatus(text: String) {
        statusText = text
        onUiState()
    }

    private fun connect(pin: String, name: () -> String) {
        updateStatus("Connecting to room $pin...")
        socket = PartySocket(
            baseWsUrl = relayBase,
            clientId = myClientId,
            onOpen = {
                handler.post {
                    reconnectAttempt = 0
                    updateStatus("Connected, waiting for others")
                    socket?.send(JSONObject().put("type", "HELLO").put("name", displayName(name)))
                    if (role == Role.GUEST) socket?.send(JSONObject().put("type", "SYNC_REQUEST"))
                }
            },
            onMessage = { msg -> handler.post { handle(msg) } },
            onClosed = { _, reason ->
                handler.post {
                    if (reason == "kicked") {
                        onSystem("The host removed you from the room")
                        wantConnected = false
                        release()
                        return@post
                    }
                    participants.clear()
                    updateStatus("Connection lost, retrying")
                    scheduleReconnect(name)
                }
            },
            onFailure = { detail ->
                handler.post {
                    participants.clear()
                    updateStatus("Connection problem: $detail")
                    if (detail == "room full" || detail == "room locked") {
                        wantConnected = false
                        return@post
                    }
                    scheduleReconnect(name)
                }
            },
        ).also { it.connect(pin) }
    }

    private fun scheduleReconnect(name: () -> String) {
        if (!wantConnected) return
        val pin = currentPin ?: return
        reconnectAttempt++
        val delayMs = (1000L shl (reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(15000L)
        scope.launch {
            delay(delayMs)
            if (!wantConnected) return@launch
            withContext(Dispatchers.Main) { connect(pin, name) }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                withContext(Dispatchers.Main) { pollLocal() }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                withContext(Dispatchers.Main) {
                    if (role == Role.HOST) sendSyncState()
                    publishRoomLabel()
                }
            }
        }
    }

    // pushes what is playing as the room title so the public room list shows it
    private fun publishRoomLabel() {
        val label = PartyProbe.currentLabel() ?: return
        if (label == lastRoomLabel) return
        lastRoomLabel = label
        if (role == Role.HOST) {
            socket?.send(JSONObject().put("type", "ROOM_META").put("title", label))
        }
    }

    private fun pollLocal() {
        if (role == Role.IDLE || !isConnected) return
        val player = PartyProbe.currentPlayer() ?: return
        val playing = player.getIsPlaying()
        val position = player.getPosition() ?: return
        val withinEcho = System.currentTimeMillis() - lastRemoteCommandMs < ECHO_WINDOW_MS

        val prevPlaying = lastKnownPlaying
        val prevPosition = lastKnownPosition
        lastKnownPlaying = playing
        lastKnownPosition = position

        if (withinEcho) return
        if (prevPlaying == null) return

        if (abs(position - prevPosition) > SEEK_JUMP_MS) {
            scope.launch {
                delay(SEEK_DEBOUNCE_MS)
                withContext(Dispatchers.Main) {
                    val current = PartyProbe.currentPlayer() ?: return@withContext
                    val finalPos = current.getPosition() ?: position
                    val finalPlaying = current.getIsPlaying()
                    lastKnownPosition = finalPos
                    lastKnownPlaying = finalPlaying
                    socket?.send(
                        JSONObject()
                            .put("type", "SEEK")
                            .put("position", finalPos)
                            .put("playing", finalPlaying)
                    )
                    beginGate(finalPos, finalPlaying)
                }
            }
            return
        }

        if (playing != prevPlaying) {
            socket?.send(JSONObject().put("type", if (playing) "PLAY" else "PAUSE"))
        }
    }

    private fun send(msg: JSONObject) {
        socket?.send(msg)
    }

    fun sendSyncState() {
        val player = PartyProbe.currentPlayer() ?: return
        send(
            JSONObject()
                .put("type", "SYNC_STATE")
                .put("position", player.getPosition() ?: 0L)
                .put("playing", player.getIsPlaying())
        )
    }

    fun requestResync() {
        val player = PartyProbe.currentPlayer() ?: return
        send(
            JSONObject()
                .put("type", "FORCE_SYNC")
                .put("position", player.getPosition() ?: 0L)
                .put("playing", player.getIsPlaying())
        )
        updateStatus("Resync sent")
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        send(JSONObject().put("type", "CHAT").put("text", trimmed.take(300)))
    }

    fun setLock(locked: Boolean) {
        if (role != Role.HOST) return
        send(JSONObject().put("type", "LOCK").put("locked", locked))
    }

    fun kick(cid: String) {
        if (role != Role.HOST) return
        send(JSONObject().put("type", "KICK").put("targetCid", cid))
    }

    fun sendEpisodeHint() {
        val label = PartyProbe.currentLabel() ?: return
        send(JSONObject().put("type", "EPISODE_HINT").put("title", label))
        lastRoomLabel = label
        if (role == Role.HOST) {
            send(JSONObject().put("type", "ROOM_META").put("title", label))
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "ROOM_STATE" -> {
                val roster = msg.optJSONArray("roster")
                if (roster != null) {
                    for (i in 0 until roster.length()) {
                        val peer = roster.optJSONObject(i) ?: continue
                        val cid = peer.optString("cid")
                        if (cid != myClientId) participants.putIfAbsent(cid, "Participant")
                    }
                }
                hostCid = msg.optString("hostCid").takeIf { it.isNotBlank() }
                roomLocked = msg.optBoolean("locked", false)
                alignRole()
                updateStatus(if (participants.isEmpty()) "Connected, alone in the room" else "Connected, ${participants.size} other(s) watching")
                onUiState()
            }

            "PEER_JOINED" -> {
                msg.optString("cid").takeIf { it.isNotBlank() && it != myClientId }?.let {
                    participants.putIfAbsent(it, "Participant")
                }
                hostCid = msg.optString("hostCid").takeIf { it.isNotBlank() }
                alignRole()
                send(JSONObject().put("type", "HELLO").put("name", partyName()))
                if (role == Role.HOST) sendSyncState()
                onUiState()
            }

            "PEER_LEFT" -> {
                val who = participants.remove(msg.optString("cid"))
                hostCid = msg.optString("hostCid").takeIf { it.isNotBlank() }
                alignRole()
                onSystem("${who ?: "Someone"} left the room")
                if (role == Role.HOST) sendSyncState()
                onUiState()
            }

            "HELLO" -> {
                val cid = msg.optString("cid")
                val name = msg.optString("name").ifBlank { "Participant" }
                if (cid != myClientId) participants[cid] = name
                onUiState()
            }

            "LOCK_STATE" -> {
                roomLocked = msg.optBoolean("locked", false)
                onSystem(if (roomLocked) "Room locked" else "Room unlocked")
                onUiState()
            }

            "SYNC_REQUEST" -> if (role == Role.HOST) sendSyncState()

            "SYNC_STATE" -> applyRemote {
                val player = PartyProbe.currentPlayer() ?: return@applyRemote
                val target = msg.optLong("position", -1L)
                if (target >= 0 && abs((player.getPosition() ?: 0L) - target) > RESYNC_THRESHOLD_MS) {
                    player.seekTo(target, PlayerEventSource.Sync)
                }
                applyPlayState(player, msg.optBoolean("playing", true))
            }

            "FORCE_SYNC" -> {
                val pos = msg.optLong("position", 0L)
                lastRemoteCommandMs = System.currentTimeMillis()
                beginGate(pos, msg.optBoolean("playing", true))
            }

            "PLAY" -> applyRemote {
                PartyProbe.currentPlayer()?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
            }

            "PAUSE" -> applyRemote {
                PartyProbe.currentPlayer()?.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
            }

            "SEEK" -> {
                lastRemoteCommandMs = System.currentTimeMillis()
                beginGate(msg.optLong("position", 0L), msg.optBoolean("playing", true))
            }

            "READY" -> {
                gateReadyCount++
                maybeResolveGate()
            }

            "CHAT" -> {
                val text = msg.optString("text").trim()
                if (text.isNotEmpty()) onChat(msg.optString("name").ifBlank { "Participant" }, text)
            }

            "EPISODE_HINT" -> {
                val title = msg.optString("title")
                if (title.isNotBlank()) onSystem("$title is playing on the other side, switch to it manually")
            }
        }
    }

    // the room name needs to be known when HELLO goes out on reconnect, so the
    // plugin injects it through this hook before connect completes
    var nameProvider: () -> String = { "Guest" }

    private fun partyName(): String = try {
        nameProvider()
    } catch (t: Throwable) {
        "Guest"
    }

    private fun alignRole() {
        if (role == Role.IDLE) return
        val host = hostCid ?: return
        val shouldBeHost = host == myClientId
        if (shouldBeHost && role != Role.HOST) {
            role = Role.HOST
            onSystem("You are the host now")
        } else if (!shouldBeHost && role == Role.HOST) {
            role = Role.GUEST
        }
    }

    private fun applyRemote(block: () -> Unit) {
        lastRemoteCommandMs = System.currentTimeMillis()
        runCatching(block)
    }

    private fun applyPlayState(player: com.lagradost.cloudstream3.ui.player.IPlayer, playing: Boolean) {
        player.handleEvent(
            if (playing) CSPlayerEvent.Play else CSPlayerEvent.Pause,
            PlayerEventSource.Sync,
        )
    }

    private fun beginGate(targetPos: Long, expectedPlaying: Boolean) {
        val player = PartyProbe.currentPlayer() ?: return
        gateActive = true
        gateGeneration++
        val gen = gateGeneration
        gateExpectedPlaying = expectedPlaying
        gateReadyCount = 0
        gatePeers = participants.size

        lastRemoteCommandMs = System.currentTimeMillis()
        player.seekTo(targetPos, PlayerEventSource.Sync)
        player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
        onSystem("Syncing everyone...")

        // once the local seek has settled, announce readiness and count it, the
        // gate resolves when everyone else in the room has announced too
        handler.postDelayed({
            if (gateGeneration == gen && gateActive) {
                send(JSONObject().put("type", "READY"))
                gateReadyCount++
                maybeResolveGate()
            }
        }, LOCAL_READY_MS)

        handler.postDelayed({
            if (gateGeneration == gen && gateActive) resolveGate()
        }, GATE_TIMEOUT_MS)
    }

    private fun maybeResolveGate() {
        if (!gateActive) return
        if (gateReadyCount >= gatePeers + 1) resolveGate()
    }

    private fun resolveGate() {
        gateActive = false
        val player = PartyProbe.currentPlayer() ?: return
        if (gateExpectedPlaying) {
            lastRemoteCommandMs = System.currentTimeMillis()
            player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
        }
        onSystem("Back in sync")
    }
}
