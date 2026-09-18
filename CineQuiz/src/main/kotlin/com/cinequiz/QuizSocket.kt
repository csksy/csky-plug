package com.cinequiz

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Thin websocket wrapper for the cs-social-hub room relay. The relay never
// interprets payloads, it just fans them out to everyone else in the room.
class QuizSocket(
    private val baseWsUrl: String,
    private val clientId: String,
    private val onOpen: () -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (Int, String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null

    val isOpen: Boolean get() = socket != null

    fun connect(pin: String) {
        val request = Request.Builder()
            .url("$baseWsUrl/room/$pin?cid=$clientId")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return
                onMessage(parsed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = when (response?.code) {
                    409 -> "room full"
                    403 -> "room locked"
                    else -> t.message ?: "connection error"
                }
                onFailure(detail)
            }
        })
    }

    fun send(json: JSONObject) {
        val ws = socket ?: return
        runCatching {
            json.put("cid", clientId)
            ws.send(json.toString())
        }
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}
