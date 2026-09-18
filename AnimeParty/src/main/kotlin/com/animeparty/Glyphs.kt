package com.animeparty

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape

// Real Material Design icon paths (24x24 viewport) plus a tiny SVG path-data
// parser, so the player button ships a proper vector icon without bundling any
// image resources - the cs3 stays a plain dex.
object Glyphs {

    // material "chat_bubble" (filled)
    const val CHAT_BUBBLE =
        "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"

    // material "groups" (filled)
    const val GROUPS =
        "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0" +
            "c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2" +
            "c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05" +
            " 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"

    fun icon(pathData: String, color: Int): ShapeDrawable =
        ShapeDrawable(PathShape(parse(pathData), 24f, 24f)).apply {
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.isAntiAlias = true
        }

    // translucent dark circle with a faint ring, identical for every player
    // button so the set looks like one family
    fun backing(ringWidthPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(216, 15, 17, 24))
            setStroke(ringWidthPx, Color.argb(64, 255, 255, 255))
        }

    fun parse(d: String): Path = SvgPath(d).parse()

    // Supports the subset of SVG path data Material icons use: M m L l H h V v
    // C c S s Q q T t Z z, with implicit command repetition.
    private class SvgPath(private val d: String) {
        private var i = 0
        private var cmd = ' '
        private var cx = 0f
        private var cy = 0f
        private var sx = 0f
        private var sy = 0f
        private var kx = 0f
        private var ky = 0f
        private var qx = 0f
        private var qy = 0f
        private var cubicPrev = false
        private var quadPrev = false
        val path = Path()

        private fun skipSep() {
            while (i < d.length && (d[i] == ' ' || d[i] == ',' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t')) i++
        }

        private fun numberStarts(): Boolean {
            skipSep()
            if (i >= d.length) return false
            val c = d[i]
            return c.isDigit() || c == '.' || c == '+' || c == '-'
        }

        private fun num(): Float {
            skipSep()
            val start = i
            if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
            var dot = false
            while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) {
                if (d[i] == '.') dot = true
                i++
            }
            if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                i++
                if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
                while (i < d.length && d[i].isDigit()) i++
            }
            if (i == start) throw IllegalArgumentException("bad path data at $i")
            return d.substring(start, i).toFloat()
        }

        fun parse(): Path {
            while (true) {
                skipSep()
                if (i >= d.length) break
                val c = d[i]
                if (!c.isLetter()) throw IllegalArgumentException("expected command at $i")
                i++
                cmd = c
                when (cmd) {
                    'M', 'm' -> {
                        var first = true
                        while (numberStarts()) {
                            var x = num(); var y = num()
                            if (cmd == 'm') { x += cx; y += cy }
                            if (first) { path.moveTo(x, y); sx = x; sy = y; first = false }
                            else path.lineTo(x, y)
                            cx = x; cy = y
                        }
                        cubicPrev = false; quadPrev = false
                    }
                    'L', 'l' -> {
                        while (numberStarts()) {
                            var x = num(); var y = num()
                            if (cmd == 'l') { x += cx; y += cy }
                            path.lineTo(x, y)
                            cx = x; cy = y
                        }
                        cubicPrev = false; quadPrev = false
                    }
                    'H', 'h' -> {
                        while (numberStarts()) {
                            var x = num()
                            if (cmd == 'h') x += cx
                            path.lineTo(x, cy)
                            cx = x
                        }
                        cubicPrev = false; quadPrev = false
                    }
                    'V', 'v' -> {
                        while (numberStarts()) {
                            var y = num()
                            if (cmd == 'v') y += cy
                            path.lineTo(cx, y)
                            cy = y
                        }
                        cubicPrev = false; quadPrev = false
                    }
                    'C', 'c' -> {
                        while (numberStarts()) {
                            var x1 = num(); var y1 = num()
                            var x2 = num(); var y2 = num()
                            var x = num(); var y = num()
                            if (cmd == 'c') {
                                x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy
                            }
                            path.cubicTo(x1, y1, x2, y2, x, y)
                            kx = x2; ky = y2
                            cx = x; cy = y
                        }
                        cubicPrev = true; quadPrev = false
                    }
                    'S', 's' -> {
                        while (numberStarts()) {
                            var x2 = num(); var y2 = num()
                            var x = num(); var y = num()
                            if (cmd == 's') { x2 += cx; y2 += cy; x += cx; y += cy }
                            val x1 = if (cubicPrev) 2 * cx - kx else cx
                            val y1 = if (cubicPrev) 2 * cy - ky else cy
                            path.cubicTo(x1, y1, x2, y2, x, y)
                            kx = x2; ky = y2
                            cx = x; cy = y
                        }
                        cubicPrev = true; quadPrev = false
                    }
                    'Q', 'q' -> {
                        while (numberStarts()) {
                            var x1 = num(); var y1 = num()
                            var x = num(); var y = num()
                            if (cmd == 'q') { x1 += cx; y1 += cy; x += cx; y += cy }
                            path.quadTo(x1, y1, x, y)
                            qx = x1; qy = y1
                            cx = x; cy = y
                        }
                        cubicPrev = false; quadPrev = true
                    }
                    'T', 't' -> {
                        while (numberStarts()) {
                            var x = num(); var y = num()
                            if (cmd == 't') { x += cx; y += cy }
                            val x1 = if (quadPrev) 2 * cx - qx else cx
                            val y1 = if (quadPrev) 2 * cy - qy else cy
                            path.quadTo(x1, y1, x, y)
                            qx = x1; qy = y1
                            cx = x; cy = y
                        }
                        cubicPrev = false; quadPrev = true
                    }
                    'Z', 'z' -> {
                        path.close()
                        cx = sx; cy = sy
                        cubicPrev = false; quadPrev = false
                    }
                    else -> throw IllegalArgumentException("unsupported command $cmd")
                }
            }
            return path
        }
    }
}
