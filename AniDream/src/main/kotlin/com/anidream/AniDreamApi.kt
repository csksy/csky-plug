package com.anidream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay
import java.net.URLEncoder

object AniDreamApi {

    private const val DEFAULT_URL = "https://anidream.to"

    private const val FN_CATALOG =
        "106b82f7a2b5783e3a3aed32e330e18e28a5006cded7804c38ebc41464230772"
    private const val FN_DETAIL =
        "505b30ac55cdb92c6321f682548d2934b45f693f37b62f9b91007a836ebda9f9"
    private const val FN_EPISODES =
        "33c4f1e3d3bb451ddccfbb87aeb0d7e52f86a1c8954c61f38d31ea4527593190"
    private const val FN_STREAM =
        "5ebed94ee2be52c6280fe2b832298a7f8a78c1dc67d7f28b42231743b2ebde1a"

    private val factory = JsonNodeFactory.instance

    @Volatile
    private var mainUrl = DEFAULT_URL

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("anidream")?.let { mainUrl = it }
    }

    fun url(): String = mainUrl

    // the api refuses plain json gets, it wants the framed accept plus a
    // same-origin fetch marker
    val requestHeaders: Map<String, String> = mapOf(
        "x-tsr-serverfn" to "true",
        "Accept" to "application/x-tss-framed, application/x-ndjson, application/json",
        "Sec-Fetch-Site" to "same-origin"
    )

    // the vault hosts reject requests without a browser referer
    val cdnHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "https://anidream.to/"
    )

    private class Ids {
        var n = 0
        fun next(): Int {
            n++
            return n
        }
    }

    private fun encodeValue(value: Any?, ids: Ids): ObjectNode {
        val node = factory.objectNode()
        when (value) {
            is Boolean -> {
                node.put("t", 2)
                node.put("s", if (value) 2 else 3)
            }
            is Int -> {
                node.put("t", 0)
                node.put("s", value)
            }
            is Long -> {
                node.put("t", 0)
                node.put("s", value)
            }
            is Double -> {
                node.put("t", 0)
                node.put("s", value)
            }
            is String -> {
                node.put("t", 1)
                node.put("s", value)
            }
            is Map<*, *> -> {
                node.put("t", 10)
                node.put("i", ids.next())
                val p = factory.objectNode()
                val keys = factory.arrayNode()
                val vals = factory.arrayNode()
                for (k in value.keys) {
                    keys.add(k.toString())
                    vals.add(encodeValue(value[k], ids))
                }
                p.set<JsonNode>("k", keys)
                p.set<JsonNode>("v", vals)
                node.set<JsonNode>("p", p)
                node.put("o", 0)
            }
            is List<*> -> {
                node.put("t", 9)
                node.put("i", ids.next())
                val arr = factory.arrayNode()
                for (item in value) {
                    arr.add(encodeValue(item, ids))
                }
                node.set<JsonNode>("a", arr)
            }
            else -> throw IllegalArgumentException("unsupported payload type ${value?.javaClass}")
        }
        return node
    }

    private fun buildPayload(data: Any?): String {
        val ids = Ids()
        val root = factory.objectNode()
        root.put("t", 10)
        root.put("i", 0)
        val p = factory.objectNode()
        val keys = factory.arrayNode()
        keys.add("data")
        val vals = factory.arrayNode()
        vals.add(encodeValue(data, ids))
        p.set<JsonNode>("k", keys)
        p.set<JsonNode>("v", vals)
        root.set<JsonNode>("p", p)
        root.put("o", 0)
        val envelope = factory.objectNode()
        envelope.set<JsonNode>("t", root)
        envelope.put("f", 127)
        envelope.set<JsonNode>("m", factory.arrayNode())
        return envelope.toString()
    }

    private fun decodeNode(node: JsonNode): JsonNode {
        if (!node.isObject) return node
        val type = node.path("t").asInt(-1)
        return when (type) {
            0 -> {
                val s = node["s"]
                if (s.isNumber && s.canConvertToLong()) LongNode(s.longValue()) else DoubleNode(s.asDouble())
            }
            1 -> TextNode(node.path("s").asText())
            2 -> when (node.path("s").asInt(-1)) {
                2 -> BooleanNode.TRUE
                3 -> BooleanNode.FALSE
                else -> NullNode.getInstance()
            }
            3, 5 -> TextNode(node.path("s").asText())
            6 -> TextNode(node.path("s").asText("error"))
            9, 10, 11 -> {
                if (node.has("a")) {
                    val out = factory.arrayNode()
                    for (item in node["a"]) {
                        out.add(decodeNode(item))
                    }
                    out
                } else {
                    val out = factory.objectNode()
                    val ks = node.path("p").path("k")
                    val vs = node.path("p").path("v")
                    if (ks.isArray) {
                        ks.forEachIndexed { i, k ->
                            out.set<JsonNode>(k.asText(), if (i < vs.size()) decodeNode(vs[i]) else NullNode.getInstance())
                        }
                    }
                    out
                }
            }
            else -> NullNode.getInstance()
        }
    }

    private fun decodeEnvelope(text: String): JsonNode? {
        val root = try {
            parseJson<ObjectNode>(text)
        } catch (_: Exception) {
            null
        } ?: return null
        if (!root.has("p")) return null
        val ks = root["p"].path("k")
        val vs = root["p"].path("v")
        val out = factory.objectNode()
        ks.forEachIndexed { i, k ->
            out.set<JsonNode>(k.asText(), if (i < vs.size()) decodeNode(vs[i]) else NullNode.getInstance())
        }
        return out
    }

    private suspend fun call(hash: String, data: Any?): JsonNode? {
        val payload = try {
            buildPayload(data)
        } catch (_: Exception) {
            return null
        }
        repeat(3) { attempt ->
            try {
                val response = app.get(
                    "$mainUrl/_serverFn/$hash",
                    params = mapOf("payload" to payload),
                    headers = requestHeaders
                )
                if (response.isSuccessful) {
                    val envelope = decodeEnvelope(response.text) ?: return null
                    val error = envelope.get("error")
                    if (error != null && !error.isNull) return null
                    val result = envelope.get("result") ?: return null
                    return if (result.isNull) null else result
                }
                if (response.code != 429 && response.code != 500) return null
            } catch (e: Exception) {
                Log.d("AniDream", "call failed: ${e.message}")
            }
            delay(600L + attempt * 400L)
        }
        return null
    }

    class MediaItem(node: JsonNode) {
        val id: Int = node.path("id").asInt()
        val malId: Int = node.path("mal_id").asInt(0)
        val format: String = node.path("format").asText("")
        val status: String = node.path("status").asText("")
        val english: String? = node.path("title").path("english").asText(null)
        val romaji: String? = node.path("title").path("romaji").asText(null)
        val native: String? = node.path("title").path("native").asText(null)
        val poster: String? = bestCover(node.path("cover_image"))
        val banner: String? = node.path("banner_image").asText(null)
        val year: Int? = node.path("season_year").takeIf { it.isNumber }?.asInt()
            ?: node.path("start_date").path("year").takeIf { it.isNumber }?.asInt()
        val score: Int? = node.path("average_score").takeIf { it.isNumber }?.asInt()
        val duration: Int? = node.path("duration").takeIf { it.isNumber }?.asInt()
        val description: String? = node.path("description").asText(null)
        val genres: List<String> = node.path("genres").mapNotNull { it.asText(null) }
        val subCount: Int = node.path("sub_count").asInt(0)
        val dubCount: Int = node.path("dub_count").asInt(0)
        val canWatch: Boolean = node.path("can_watch").asBoolean(false)

        private fun bestCover(cover: JsonNode): String? {
            for (key in listOf("large", "medium", "small")) {
                cover.path(key).asText(null)?.let { return it }
            }
            return null
        }

        fun displayTitle(): String? = english ?: romaji ?: native
    }

    class EpisodeItem(node: JsonNode) {
        val number: Int = node.path("number").asInt()
        val english: String? = node.path("title").path("english").asText(null)
        val romaji: String? = node.path("title").path("romaji").asText(null)
        val native: String? = node.path("title").path("native").asText(null)
        val thumbnail: String? = node.path("thumbnail").asText(null)
        val hasSub: Boolean = node.path("has_sub").asBoolean(false)
        val hasDub: Boolean = node.path("has_dub").asBoolean(false)
        val isFiller: Boolean = node.path("is_filler").asBoolean(false)
        val airedAt: String? = node.path("aired_at").asText(null)

        fun displayTitle(): String? =
            (english ?: romaji ?: native)?.takeIf { it.isNotBlank() }
    }

    class SubtitleTrack(node: JsonNode) {
        val label: String = node.path("label").asText("Subtitle")
        val url: String = node.path("url").asText("")
        val type: String = node.path("type").asText("srt")
        val language: String = node.path("language").asText("en")
        val isDefault: Boolean = node.path("default").asBoolean(false)
    }

    class StreamData(node: JsonNode) {
        val fileCode: String = node.path("fileCode").asText("")
        val streamUrl: String = node.path("streamUrl").asText("")
        val audio: String = node.path("audio").asText("")
        val subtitles: List<SubtitleTrack> = node.path("subtitles")
            .filter { it.path("url").asText("").startsWith("http") }
            .map { SubtitleTrack(it) }
    }

    // the dedicated search endpoint caps its limit at ten, the catalog
    // endpoint takes the same query string and pages properly
    suspend fun search(query: String, limit: Int = 30): List<MediaItem> {
        val res = call(
            FN_CATALOG,
            mapOf(
                "type" to "ANIME",
                "q" to query,
                "page" to 1,
                "limit" to limit,
                "isAdult" to false
            )
        ) ?: return emptyList()
        return res.path("items").mapNotNull { runCatching { MediaItem(it) }.getOrNull() }
    }

    // sort values the api accepts, page is one based
    suspend fun catalog(
        sort: String,
        page: Int,
        limit: Int = 28,
        format: String? = null
    ): Pair<List<MediaItem>, Boolean> {
        val data = buildMap<String, Any?> {
            put("type", "ANIME")
            put("sort", sort)
            put("page", page)
            put("limit", limit)
            put("isAdult", false)
            if (format != null) put("format", format)
        }
        val res = call(FN_CATALOG, data) ?: return Pair(emptyList(), false)
        val items = res.path("items").mapNotNull { runCatching { MediaItem(it) }.getOrNull() }
        val hasNext = res.path("pageInfo").path("has_next").asBoolean(items.isNotEmpty())
        return Pair(items, hasNext)
    }

    suspend fun detail(id: Int): MediaItem? {
        val res = call(FN_DETAIL, mapOf("id" to id, "type" to "ANIME", "watchOrder" to false)) ?: return null
        return runCatching { MediaItem(res) }.getOrNull()
    }

    suspend fun episodes(id: Int): List<EpisodeItem> {
        val res = call(FN_EPISODES, mapOf("id" to id)) ?: return emptyList()
        return res.mapNotNull { runCatching { EpisodeItem(it) }.getOrNull() }
    }

    // the stream payload is sealed to a fresh ecdh keypair per request, the
    // response can only be opened with the private half that never leaves us
    suspend fun stream(anilistId: Int, episode: Int, dub: Boolean): StreamData? {
        val keyPair = AniDreamCrypto.newKeyPair()
        val publicKey = AniDreamCrypto.publicKeyString(keyPair.public)
        val res = call(
            FN_STREAM,
            mapOf(
                "anilistId" to anilistId,
                "episode" to episode,
                "dub" to dub,
                "key" to publicKey
            )
        ) ?: return null
        if (!res.path("ok").asBoolean(false)) return null
        val sealed = res.path("sealed")
        if (!sealed.isObject || sealed.path("v").asInt(0) != 1) return null
        val plain = AniDreamCrypto.open(
            keyPair.private,
            sealed.path("epk").asText(""),
            sealed.path("iv").asText(""),
            sealed.path("ct").asText(""),
            "$anilistId:$episode:${if (dub) "dub" else "sub"}"
        ) ?: return null
        val node = try {
            parseJson<ObjectNode>(plain)
        } catch (_: Exception) {
            null
        } ?: return null
        return runCatching { StreamData(node) }.getOrNull()
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
