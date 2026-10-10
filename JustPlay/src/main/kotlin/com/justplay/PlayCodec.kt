package com.justplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.lagradost.cloudstream3.utils.Qualities

// Ranks links by what this device can actually decode. Budget chipsets have no
// HEVC decoder above 1080p and no Dolby Vision at all, so UHD HEVC links die in
// MediaCodec configure. Those failures also leave the app player surface in a
// broken state where every other source opened in the same session fails until
// the player is closed and reopened. Links the hardware cannot decode stay in
// the list, external players and downloads can still use them, but they sort
// near the bottom instead of sitting on top erroring out.
internal object PlayCodec {

    private const val HEVC_TYPE = "video/hevc"
    private const val DV_TYPE = "video/dolby-vision"

    // hevc profile level flags with the high tier bit stripped
    private const val LEVEL_4 = 0x20
    private const val LEVEL_41 = 0x40
    private const val LEVEL_5 = 0x80
    private const val LEVEL_51 = 0x100
    private const val TIER_MASK = 0x1FFF

    private val hevcRegex = Regex("(?i)\\b(x265|h\\.?265|hevc)\\b")
    private val tenbitRegex = Regex("(?i)\\b(10[ -]?bit|dv|hdr10\\+?|hdr|dolby[ -]?vision)\\b")
    private val uhdRegex = Regex("(?i)\\b(2160p|uhd)\\b")
    private val qhdRegex = Regex("(?i)\\b(1440p|2k)\\b")

    @Volatile
    private var probed = false
    private val lock = Any()

    // frame height the hevc decoders reach, 8-bit and 10-bit, and dv support
    private var height8 = 0
    private var height10 = 0
    private var dolbyVision = false
    private var probeOk = false

    private fun probe() {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            var best8 = 0
            var best10 = 0
            var dv = false
            try {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                for (info in infos) {
                    if (info.isEncoder) continue
                    val types = try {
                        info.supportedTypes
                    } catch (_: Exception) {
                        continue
                    }
                    if (types.contains(DV_TYPE)) dv = true
                    if (!types.contains(HEVC_TYPE)) continue
                    val caps = try {
                        info.getCapabilitiesForType(HEVC_TYPE)
                    } catch (_: Exception) {
                        continue
                    }
                    val levels = try {
                        caps.profileLevels.toList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    best8 = maxOf(best8, decoderHeight(levels, false, caps.videoCapabilities))
                    best10 = maxOf(best10, decoderHeight(levels, true, caps.videoCapabilities))
                }
                probeOk = true
            } catch (_: Exception) {
            }
            height8 = best8
            height10 = best10
            dolbyVision = dv
            probed = true
        }
    }

    // how tall a frame this decoder handles. profile levels are the source of
    // truth, the declared size range only steps in when no levels are listed.
    // one level above the minimum is required so 60fps and high tier encodes,
    // which are the common uhd release, are covered
    private fun decoderHeight(
        levels: List<MediaCodecInfo.CodecProfileLevel>,
        tenBit: Boolean,
        vc: MediaCodecInfo.VideoCapabilities
    ): Int {
        if (levels.isEmpty()) return sizeFallback(vc)
        val main = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        val main10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        // a main10 entry also covers 8-bit main streams
        val matching = levels.filter { it.profile == main10 || (!tenBit && it.profile == main) }
        if (matching.isEmpty()) return 0
        var best = 0
        for (pl in matching) {
            val level = pl.level and TIER_MASK
            val capHeight = if (tenBit) {
                when {
                    level >= LEVEL_51 -> 2160
                    level >= LEVEL_41 -> 1440
                    else -> 1080
                }
            } else {
                when {
                    level >= LEVEL_5 -> 2160
                    level >= LEVEL_4 -> 1440
                    else -> 1080
                }
            }
            best = maxOf(best, capHeight)
        }
        // a level claim still needs the size range to agree
        if (best > 1080 && !sizeOk(vc, best)) {
            best = if (sizeOk(vc, 1440)) 1440 else if (sizeOk(vc, 1080)) 1080 else 0
        }
        return best
    }

    private fun sizeFallback(vc: MediaCodecInfo.VideoCapabilities): Int = when {
        sizeOk(vc, 2160) -> 2160
        sizeOk(vc, 1440) -> 1440
        sizeOk(vc, 1080) -> 1080
        else -> 0
    }

    private fun sizeOk(vc: MediaCodecInfo.VideoCapabilities, height: Int): Boolean = try {
        when {
            height >= 2160 -> vc.isSizeSupported(3840, 2160)
            height >= 1440 -> vc.isSizeSupported(2560, 1440)
            else -> vc.isSizeSupported(1920, 1080)
        }
    } catch (_: Exception) {
        false
    }

    private fun labelHeight(name: String, quality: Int): Int = when {
        quality >= 2160 || uhdRegex.containsMatchIn(name) -> 2160
        quality >= 1440 || qhdRegex.containsMatchIn(name) -> 1440
        else -> 0
    }

    // returns the quality value used for app side sorting, demoting links whose
    // codec and frame size this device cannot decode so they sort near the bottom
    fun rankQuality(name: String, quality: Int): Int {
        probe()
        if (!probeOk) return quality
        val height = labelHeight(name, quality)
        if (height == 0) return quality

        val hevc = hevcRegex.containsMatchIn(name)
        val limit = if (tenbitRegex.containsMatchIn(name)) height10 else height8

        val playable = if (hevc) {
            limit >= height
        } else {
            // dolby vision falls back to hevc on devices without a dv decoder
            dolbyVision || limit >= height
        }
        return if (playable) quality else Qualities.Unknown.value
    }
}
