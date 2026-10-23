package com.justplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

// The player falls back to its bundled software decoder when the chipset has no
// hardware decoder for a file, and on several devices switching away from a
// software decoded video leaves the player's video surface in a broken state
// where every later source fails with a decoder error until the title is
// reopened. This probe mirrors the player's own codec selection so files the
// device cannot decode in hardware are kept out of the stream list before they
// can poison it. Anything the probe cannot read fails open, links stay visible.
internal object DeviceCodecs {

    @Volatile
    private var probed = false

    private val lock = Any()

    private var hevcMain10 = false
    private var av1 = false
    private var vp9 = false
    private var vp9Profile2 = false
    private var avc4k = false
    private var hevc4k = false

    // labels rarely admit the real bit depth, hvc encodes are ten bit far more
    // often than sites mark them, so the whole family rides on main10 support
    private val hevcHints = Regex(
        """(?i)x265|h\.?265|\bhevc\b|hvc1|hev1|dvhe|dvh1|dolby\s*vision|\bdovi\b|\bdv\b"""
    )
    private val av1Hints = Regex("""(?i)\bav01?\b""")
    private val vp9Hints = Regex("""(?i)\bvp9\b|v_vp9""")
    private val tenBitHints = Regex(
        """(?i)10.?bit|main10|hdr10|\bhdr\b|dolby\s*vision|\bdovi\b|\bdv\b"""
    )
    private val uhdHints = Regex("""(?i)2160p|\b4k\b|\buhd\b|3840x""")
    private val urlEscape = Regex("%[0-9A-Fa-f]{2}")

    private fun probe() {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                        if (info.isEncoder) continue
                        for (type in info.supportedTypes) {
                            val caps = try {
                                info.getCapabilitiesForType(type)
                            } catch (_: Exception) {
                                null
                            } ?: continue
                            when (type) {
                                "video/hevc" -> {
                                    for (pl in caps.profileLevels) {
                                        when (pl.profile) {
                                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> hevcMain10 = true
                                        }
                                    }
                                    val vc = caps.videoCapabilities ?: continue
                                    if (vc.isSizeSupported(3840, 2160)) hevc4k = true
                                }
                                "video/av01" -> av1 = true
                                "video/x-vnd.on2.vp9" -> {
                                    vp9 = true
                                    for (pl in caps.profileLevels) {
                                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                                            pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                                            pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus
                                        ) vp9Profile2 = true
                                    }
                                }
                                "video/avc" -> {
                                    val vc = caps.videoCapabilities ?: continue
                                    if (vc.isSizeSupported(3840, 2160)) avc4k = true
                                }
                            }
                        }
                    }
                } else {
                    failOpen()
                }
            } catch (_: Exception) {
                // a failed probe must never hide a playable link from anyone
                failOpen()
            }
            probed = true
        }
    }

    private fun failOpen() {
        hevcMain10 = true
        av1 = true
        vp9 = true
        vp9Profile2 = true
        avc4k = true
        hevc4k = true
    }

    fun canHardwareStream(name: String, url: String, quality: Int): Boolean {
        if (!JustPlay.deviceGuardEnabled()) return true
        probe()

        // the share url often carries the real file name while the label lost it
        val text = (name + " " + url.replace(urlEscape, " ")).lowercase()
        val uhd = quality >= 2160 || uhdHints.containsMatchIn(text)

        return when {
            hevcHints.containsMatchIn(text) -> {
                // no hevc hardware at all, or a chipset without main10 that would
                // software decode the file and break the surface on the next switch
                if (!hevcMain10) return false
                if (uhd && !hevc4k) return false
                true
            }
            av1Hints.containsMatchIn(text) -> av1
            vp9Hints.containsMatchIn(text) -> {
                if (!vp9) return false
                if (tenBitHints.containsMatchIn(text) && !vp9Profile2) return false
                true
            }
            else -> {
                // unmarked files are avc or hls in practice, only their size can rule them out
                if (uhd && !avc4k) return false
                true
            }
        }
    }
}
