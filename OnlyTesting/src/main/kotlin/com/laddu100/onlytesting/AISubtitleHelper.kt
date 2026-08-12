package com.laddu100.onlytesting

import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * AI Subtitle Generator for RaghavAnime.
 *
 * Generates English subtitles for English-dub anime using:
 * - Groq Whisper API (fastest, free tier: 25MB/request, 172x real-time)
 * - OpenAI Whisper API (official, $0.006/min)
 *
 * Flow:
 * 1. Download audio from the video stream URL (m3u8 audio segments or direct file)
 * 2. Send to ASR API (Groq or OpenAI) with verbose_json response format
 * 3. Parse segments with word-level timestamps
 * 4. Generate WebVTT subtitle file
 * 5. Serve via local HTTP server (same pattern as SubDLHelper)
 * 6. Pass subtitle URL to subtitleCallback
 *
 * Limitations:
 * - Max 24MB audio per API request (free tier limit)
 * - For episodes > ~25 min, audio is truncated to fit limit
 * - Only processes English dub streams
 * - Requires API key (user enters in settings)
 */
object AISubtitleHelper {

    private const val TAG = "AISub"
    private const val PREFIX = "raghavanime_feat_"

    // ===== Settings =====
    fun isEnabled(): Boolean = getKey<Boolean>(PREFIX + "ai_subtitles") ?: false
    fun setEnabled(enabled: Boolean) { setKey(PREFIX + "ai_subtitles", enabled) }

    fun getProvider(): String = getKey<String>(PREFIX + "ai_provider") ?: "groq"
    fun setProvider(provider: String) { setKey(PREFIX + "ai_provider", provider) }

    fun getGroqKey(): String = getKey<String>(PREFIX + "groq_key") ?: ""
    fun setGroqKey(key: String) { setKey(PREFIX + "groq_key", key) }

    fun getOpenAIKey(): String = getKey<String>(PREFIX + "openai_key") ?: ""
    fun setOpenAIKey(key: String) { setKey(PREFIX + "openai_key", key) }

    fun getModel(): String = getKey<String>(PREFIX + "ai_model") ?: "whisper-large-v3-turbo"
    fun setModel(model: String) { setKey(PREFIX + "ai_model", model) }

    val providers = listOf(
        "groq" to "Groq (Fast — Free Tier)",
        "openai" to "OpenAI (Official — $0.006/min)"
    )

    val models = mapOf(
        "groq" to listOf(
            "whisper-large-v3-turbo" to "Whisper Large v3 Turbo (Fastest)",
            "whisper-large-v3" to "Whisper Large v3 (Most Accurate)"
        ),
        "openai" to listOf(
            "whisper-1" to "Whisper-1"
        )
    )

    // ===== Local HTTP Server (serves generated VTT) =====
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile private var serverRunning = false
    private val subtitleStore = mutableMapOf<String, ByteArray>()

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Log.d(TAG, "AI subtitle server started on port $serverPort")

            Thread {
                while (serverRunning) {
                    try {
                        val client = socket.accept()
                        handleRequest(client)
                    } catch (e: Exception) {
                        if (serverRunning) {
                            Log.d(TAG, "server accept error: ${e.message}")
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.d(TAG, "server start error: ${e.message}")
        }
        return serverPort
    }

    private fun handleRequest(client: java.net.Socket) {
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
            val headerLine = reader.readLine() ?: return
            val path = headerLine.split(" ").getOrNull(1) ?: return

            // Consume remaining headers
            while (reader.readLine()?.isNotEmpty() == true) {}

            val subId = path.removePrefix("/aisub/").substringBefore("?")
            val content = subtitleStore[subId]

            val writer = java.io.OutputStreamWriter(client.getOutputStream())
            if (content != null) {
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: text/vtt; charset=utf-8\r\n")
                writer.write("Content-Length: ${content.size}\r\n")
                writer.write("Access-Control-Allow-Origin: *\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
                client.getOutputStream().write(content)
                client.getOutputStream().flush()
            } else {
                writer.write("HTTP/1.1 404 Not Found\r\n")
                writer.write("Content-Length: 0\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "handleRequest error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun cacheSubtitle(content: String): String {
        val id = MessageDigest.getInstance("MD5")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        subtitleStore[id] = content.toByteArray(Charsets.UTF_8)
        return id
    }

    // ===== Main Entry Point =====
    /**
     * Generates AI subtitles for an English dub stream.
     * Called from loadLinks() — runs in background, adds subtitle via callback.
     *
     * @param streamUrl The video/audio stream URL (m3u8 or direct file)
     * @param headers HTTP headers needed to access the stream
     * @param isDub Whether this is a dubbed stream (only processes dub)
     * @param animeTitle Title for logging
     * @param episode Episode number for logging
     * @param subtitleCallback Callback to receive the generated SubtitleFile
     */
    suspend fun generateAISubtitles(
        streamUrl: String,
        headers: Map<String, String>,
        isDub: Boolean,
        animeTitle: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!isEnabled()) return
        if (!isDub) {
            Log.d(TAG, "Skipping — not a dub stream")
            return
        }

        val provider = getProvider()
        val apiKey = when (provider) {
            "groq" -> getGroqKey()
            "openai" -> getOpenAIKey()
            else -> ""
        }
        if (apiKey.isBlank()) {
            Log.e(TAG, "[$animeTitle ep$episode] No API key for provider '$provider', skipping AI subtitles")
            return
        }

        try {
            Log.d(TAG, "[$animeTitle ep$episode] Starting AI subtitle generation via $provider (model=${getModel()})")
            Log.d(TAG, "[$animeTitle ep$episode] Stream URL: $streamUrl")

            // Step 1: Download audio from stream
            val audioData = downloadAudio(streamUrl, headers)
            if (audioData == null || audioData.isEmpty()) {
                Log.d(TAG, "[$animeTitle ep$episode] Failed to download audio")
                return
            }
            Log.d(TAG, "[$animeTitle ep$episode] Downloaded audio: ${audioData.size} bytes")

            // Step 2: Send to ASR API
            val model = getModel()
            val transcription = when (provider) {
                "groq" -> transcribeWithGroq(audioData, apiKey, model)
                "openai" -> transcribeWithOpenAI(audioData, apiKey, model)
                else -> null
            }
            if (transcription == null) {
                Log.d(TAG, "[$animeTitle ep$episode] Transcription failed")
                return
            }
            Log.d(TAG, "[$animeTitle ep$episode] Transcription received, generating VTT")

            // Step 3: Generate WebVTT
            val vtt = generateVTT(transcription)

            // Step 4: Cache and serve via local server
            val port = ensureServerRunning()
            if (port == 0) {
                Log.d(TAG, "[$animeTitle ep$episode] Failed to start subtitle server")
                return
            }
            val cacheId = cacheSubtitle(vtt)
            val subtitleUrl = "http://127.0.0.1:$port/aisub/$cacheId"
            val label = "AI English (${provider.uppercase()})"
            subtitleCallback.invoke(newSubtitleFile(label, subtitleUrl))
            Log.d(TAG, "[$animeTitle ep$episode] AI subtitle added: $label -> $subtitleUrl")
        } catch (e: Exception) {
            Log.d(TAG, "[$animeTitle ep$episode] generateAISubtitles error: ${e.message}")
        }
    }

    // ===== Audio Download =====
    private suspend fun downloadAudio(url: String, headers: Map<String, String>): ByteArray? {
        return try {
            if (url.contains(".m3u8", ignoreCase = true)) {
                downloadAudioFromM3u8(url, headers)
            } else {
                // Direct file (mp4, mkv, webm, etc.) — APIs accept these directly
                downloadDirectFile(url, headers)
            }
        } catch (e: Exception) {
            Log.d(TAG, "downloadAudio error: ${e.message}")
            null
        }
    }

    /**
     * Downloads audio from an HLS (m3u8) stream.
     * 1. Fetches master playlist
     * 2. Looks for a separate audio track (EXT-X-MEDIA TYPE=AUDIO)
     * 3. If found: downloads audio segments and concatenates them
     * 4. If not found: falls back to downloading video segments (TS format)
     */
    private suspend fun downloadAudioFromM3u8(url: String, headers: Map<String, String>): ByteArray? {
        val masterText = app.get(url, headers = headers).text
        Log.d(TAG, "Fetched m3u8 master playlist (${masterText.length} chars)")

        // Try to find a separate audio track
        val audioPlaylistUrl = findAudioTrackUrl(masterText, url)
        if (audioPlaylistUrl != null) {
            Log.d(TAG, "Found separate audio track, downloading segments")
            return downloadSegments(audioPlaylistUrl, headers, isAudioTrack = true)
        }

        // No separate audio — find first variant stream
        val variantUrl = findFirstVariantUrl(masterText, url)
        if (variantUrl != null && variantUrl != url) {
            Log.d(TAG, "Using variant stream: $variantUrl")
            // Recursively fetch the variant playlist
            val variantText = app.get(variantUrl, headers = headers).text
            // Check if variant itself has audio track
            val variantAudioUrl = findAudioTrackUrl(variantText, variantUrl)
            if (variantAudioUrl != null) {
                Log.d(TAG, "Found audio track in variant, downloading segments")
                return downloadSegments(variantAudioUrl, headers, isAudioTrack = true)
            }
            // No separate audio in variant — download TS segments
            Log.d(TAG, "No separate audio track, downloading video segments")
            return downloadSegmentsFromPlaylist(variantText, variantUrl, headers)
        }

        // This is already a media playlist — download segments
        Log.d(TAG, "Direct media playlist, downloading segments")
        return downloadSegmentsFromPlaylist(masterText, url, headers)
    }

    /** Finds the URI of the first AUDIO EXT-X-MEDIA entry in a master playlist. */
    private fun findAudioTrackUrl(playlist: String, baseUrl: String): String? {
        for (line in playlist.lines()) {
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                if (uriMatch != null) {
                    return resolveUrl(uriMatch.groupValues[1], baseUrl)
                }
            }
        }
        return null
    }

    /** Finds the first variant stream URL (non-comment line) in a master playlist. */
    private fun findFirstVariantUrl(playlist: String, baseUrl: String): String? {
        for (line in playlist.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                return resolveUrl(trimmed, baseUrl)
            }
        }
        return null
    }

    /** Downloads and concatenates all segments from a media playlist. */
    private suspend fun downloadSegments(playlistUrl: String, headers: Map<String, String>, isAudioTrack: Boolean): ByteArray? {
        val playlistText = app.get(playlistUrl, headers = headers).text
        return downloadSegmentsFromPlaylist(playlistText, playlistUrl, headers)
    }

    /** Parses a media playlist and downloads all segments, concatenating them. */
    private suspend fun downloadSegmentsFromPlaylist(playlistText: String, playlistUrl: String, headers: Map<String, String>): ByteArray? {
        val segmentUrls = mutableListOf<String>()
        for (line in playlistText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segmentUrls.add(resolveUrl(trimmed, playlistUrl))
            }
        }

        if (segmentUrls.isEmpty()) {
            Log.d(TAG, "No segments found in playlist")
            return null
        }

        Log.d(TAG, "Found ${segmentUrls.size} segments")

        // Download and concatenate segments
        // Groq/OpenAI limit: 25MB per request — we cap at 24MB to be safe
        val maxBytes = 24 * 1024 * 1024
        val output = ByteArrayOutputStream()
        var totalBytes = 0
        var downloadedCount = 0

        for ((index, segUrl) in segmentUrls.withIndex()) {
            if (totalBytes >= maxBytes) {
                Log.d(TAG, "Reached 24MB limit at segment $index/${segmentUrls.size} (downloaded $downloadedCount)")
                break
            }
            try {
                val segData = app.get(segUrl, headers = headers).body.bytes()
                output.write(segData)
                totalBytes += segData.size
                downloadedCount++
            } catch (e: Exception) {
                Log.d(TAG, "Failed to download segment $index: ${e.message}")
            }
        }

        Log.d(TAG, "Downloaded $totalBytes bytes ($downloadedCount segments)")
        val result = output.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    /** Downloads a direct file (mp4, webm, etc.) with a 24MB size cap. */
    private suspend fun downloadDirectFile(url: String, headers: Map<String, String>): ByteArray? {
        val maxBytes = 24 * 1024 * 1024
        val response = app.get(url, headers = headers)
        val data = response.body.bytes()
        return if (data.size <= maxBytes) {
            data
        } else {
            Log.d(TAG, "File too large (${data.size} bytes), truncating to $maxBytes")
            data.copyOfRange(0, maxBytes)
        }
    }

    /** Resolves a relative URL against a base URL. */
    private fun resolveUrl(url: String, baseUrl: String): String {
        return try {
            if (url.startsWith("http")) url
            else URL(URL(baseUrl), url).toString()
        } catch (_: Exception) { url }
    }

    // ===== ASR API Calls =====

    /** Sends audio to Groq's Whisper API for transcription. */
    private suspend fun transcribeWithGroq(audioData: ByteArray, apiKey: String, model: String): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            val audioMediaType = guessMediaType(audioData)
            val ext = getExtension(audioMediaType)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.$ext", audioData.toRequestBody(audioMediaType.toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("language", "en")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "Groq API error ${response.code}: ${responseText?.take(500)}")
                response.close()
                return null
            }
            response.close()
            Log.d(TAG, "Groq transcription success (${responseText?.length ?: 0} chars)")
            responseText
        } catch (e: Exception) {
            Log.e(TAG, "Groq transcription error: ${e.message}")
            null
        }
    }

    /** Sends audio to OpenAI's Whisper API for transcription. */
    private suspend fun transcribeWithOpenAI(audioData: ByteArray, apiKey: String, model: String): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            val audioMediaType = guessMediaType(audioData)
            val ext = getExtension(audioMediaType)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.$ext", audioData.toRequestBody(audioMediaType.toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("language", "en")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string()
            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI API error ${response.code}: ${responseText?.take(500)}")
                response.close()
                return null
            }
            response.close()
            Log.d(TAG, "OpenAI transcription success (${responseText?.length ?: 0} chars)")
            responseText
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI transcription error: ${e.message}")
            null
        }
    }

    /** Guesses the audio media type based on file header bytes. */
    private fun guessMediaType(data: ByteArray): String {
        if (data.size < 4) return "audio/mpeg"
        // ADTS AAC: starts with 0xFF 0xF1 or 0xFF 0xF9
        if ((data[0].toInt() and 0xFF) == 0xFF && ((data[1].toInt() and 0xF0) == 0xF0)) {
            return "audio/aac"
        }
        // MP3: starts with 0x49 0x44 0x33 (ID3) or 0xFF 0xFB
        if (data[0].toInt() == 0x49 && data[1].toInt() == 0x44 && data[2].toInt() == 0x33) {
            return "audio/mpeg"
        }
        if ((data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xFB) {
            return "audio/mpeg"
        }
        // MP4/M4A: starts with "ftyp" at offset 4
        if (data.size > 8 && data[4].toInt() == 0x66 && data[5].toInt() == 0x74 && data[6].toInt() == 0x79 && data[7].toInt() == 0x70) {
            return "audio/mp4"
        }
        // RIFF/WAV
        if (data[0].toInt() == 0x52 && data[1].toInt() == 0x49 && data[2].toInt() == 0x46 && data[3].toInt() == 0x46) {
            return "audio/wav"
        }
        // OGG
        if (data[0].toInt() == 0x4F && data[1].toInt() == 0x67 && data[2].toInt() == 0x67 && data[3].toInt() == 0x53) {
            return "audio/ogg"
        }
        // MPEG-TS: starts with 0x47
        if (data[0].toInt() == 0x47) {
            return "video/mp2t"
        }
        return "audio/mpeg"
    }

    /** Returns the file extension for a given media type. */
    private fun getExtension(mediaType: String): String {
        return when (mediaType) {
            "audio/aac" -> "aac"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "mp4"
            "audio/wav" -> "wav"
            "audio/ogg" -> "ogg"
            "video/mp2t" -> "ts"
            else -> "mp3"
        }
    }

    // ===== VTT Generation =====

    /** Converts Whisper verbose_json response to WebVTT format. */
    private fun generateVTT(responseJson: String): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")

        try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(responseJson)

            // Try segments first (best for subtitles)
            val segments = json.get("segments")
            if (segments != null && segments.isArray && segments.size() > 0) {
                for (seg in segments) {
                    val start = seg.get("start")?.asDouble() ?: continue
                    val end = seg.get("end")?.asDouble() ?: continue
                    val text = seg.get("text")?.asText()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        sb.append(formatVTTTime(start))
                        sb.append(" --> ")
                        sb.append(formatVTTTime(end))
                        sb.append("\n")
                        sb.append(text)
                        sb.append("\n\n")
                    }
                }
                return sb.toString()
            }

            // Fallback: try words for finer granularity
            val words = json.get("words")
            if (words != null && words.isArray && words.size() > 0) {
                // Group words into ~7-word chunks for readable subtitles
                val chunkSize = 7
                var chunkStart = -1.0
                val chunkText = StringBuilder()
                var wordCount = 0

                for (word in words) {
                    val start = word.get("start")?.asDouble() ?: continue
                    val end = word.get("end")?.asDouble() ?: continue
                    val text = word.get("word")?.asText()?.trim() ?: ""

                    if (chunkStart < 0) chunkStart = start
                    if (chunkText.isNotEmpty()) chunkText.append(" ")
                    chunkText.append(text)
                    wordCount++

                    if (wordCount >= chunkSize) {
                        sb.append(formatVTTTime(chunkStart))
                        sb.append(" --> ")
                        sb.append(formatVTTTime(end))
                        sb.append("\n")
                        sb.append(chunkText.toString().trim())
                        sb.append("\n\n")
                        chunkStart = -1.0
                        chunkText.clear()
                        wordCount = 0
                    }
                }
                // Write remaining chunk
                if (chunkText.isNotEmpty() && chunkStart >= 0) {
                    sb.append(formatVTTTime(chunkStart))
                    sb.append(" --> ")
                    // Add 2 seconds to last end time
                    val lastEnd = words.last().get("end")?.asDouble() ?: (chunkStart + 2)
                    sb.append(formatVTTTime(lastEnd))
                    sb.append("\n")
                    sb.append(chunkText.toString().trim())
                    sb.append("\n\n")
                }
                return sb.toString()
            }

            // Last resort: use full text with estimated timing
            val fullText = json.get("text")?.asText()?.trim() ?: ""
            val duration = json.get("duration")?.asDouble() ?: 10.0
            if (fullText.isNotEmpty()) {
                sb.append("00:00:00.000 --> ")
                sb.append(formatVTTTime(duration))
                sb.append("\n")
                sb.append(fullText)
                sb.append("\n\n")
            }
        } catch (e: Exception) {
            Log.d(TAG, "generateVTT error: ${e.message}")
        }

        return sb.toString()
    }

    /** Formats seconds as HH:MM:SS.mmm (WebVTT timestamp format). */
    private fun formatVTTTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong()
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val totalMin = totalSec / 60
        val m = totalMin % 60
        val h = totalMin / 60
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }
}
