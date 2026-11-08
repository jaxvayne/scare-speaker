package com.scarespeaker

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Pcm(val data: FloatArray, val rate: Int) {
    val seconds: Double get() = data.size.toDouble() / rate
}

object Wav {
    fun read(f: File): Pcm {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 12) { "wav too short" }
        b.position(12)
        var rate = 22050
        var channels = 1
        var bits = 16
        var data: FloatArray? = null
        while (b.remaining() >= 8) {
            val id = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
            var size = b.int
            if (size < 0 || size > b.remaining()) size = b.remaining()
            val start = b.position()
            when (id) {
                "fmt " -> {
                    b.short // format
                    channels = b.short.toInt()
                    rate = b.int
                    b.int; b.short
                    bits = b.short.toInt()
                }
                "data" -> {
                    val frames = size / (bits / 8) / channels
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var s = 0f
                        for (ch in 0 until channels) {
                            s += if (bits == 16) b.short / 32768f
                            else ((b.get().toInt() and 0xff) - 128) / 128f
                        }
                        out[i] = s / channels
                    }
                    data = out
                }
            }
            b.position(min(b.limit(), start + size + (size and 1)))
        }
        return Pcm(data ?: FloatArray(0), rate)
    }

    fun write(f: File, p: Pcm) {
        val n = p.data.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(p.rate); bb.putInt(p.rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (v in p.data) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bb.array())
        tmp.renameTo(f)
    }

    /** 파일 길이(초) */
    fun durationSec(f: File): Double {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 44) return 0.0
            val h = ByteArray(44); r.readFully(h)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val byteRate = bb.getInt(28)
            return if (byteRate > 0) (r.length() - 44).toDouble() / byteRate else 0.0
        }
    }
}

/** 효과음/음악 합성과 음성 효과 */
object Dsp {
    fun silence(rate: Int, sec: Double) = FloatArray((rate * sec).toInt())

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** 뚜벅뚜벅 발소리. approach=true면 점점 커짐(다가옴), false면 작아짐(멀어짐) */
    fun footsteps(rate: Int, approach: Boolean, steps: Int = 6, interval: Double = 0.58): FloatArray {
        val out = FloatArray((rate * (steps * interval + 0.4)).toInt())
        val rnd = java.util.Random(7)
        for (i in 0 until steps) {
            val frac = i.toDouble() / (steps - 1)
            val g = if (approach) 0.22 + 0.78 * frac else 1.0 - 0.8 * frac
            val t0 = (i * interval * rate).toInt()
            thump(out, t0, g, rate, rnd)
            thump(out, t0 + (0.075 * rate).toInt(), g * 0.4, rate, rnd)
        }
        return out
    }

    private fun thump(out: FloatArray, start: Int, gain: Double, rate: Int, rnd: java.util.Random) {
        val len = (0.22 * rate).toInt()
        var phase = 0.0
        var lp = 0.0
        for (k in 0 until len) {
            val i = start + k
            if (i >= out.size) break
            val t = k.toDouble() / rate
            val f = 100.0 - 45.0 * min(t / 0.12, 1.0)
            phase += 2 * PI * f / rate
            val body = sin(phase) * exp(-t / 0.05)
            lp += 0.25 * ((rnd.nextDouble() * 2 - 1) - lp)
            val click = lp * exp(-t / 0.012)
            out[i] += ((body * 0.85 + click * 0.5) * gain).toFloat()
        }
    }

    /** 울림(리버브) */
    fun reverb(x: FloatArray, rate: Int, mix: Double = 0.32, tailSec: Double = 0.9, size: Double = 1.0): FloatArray {
        val n = x.size + (tailSec * size * rate).toInt()
        val wet = DoubleArray(n)
        val delays = doubleArrayOf(0.067, 0.089, 0.113, 0.149).map { it * size }
        for (d in delays) {
            val dl = (d * rate).toInt()
            val buf = DoubleArray(n)
            var lp = 0.0
            for (i in 0 until n) {
                val input = if (i < x.size) x[i].toDouble() else 0.0
                val fb = if (i >= dl) buf[i - dl] else 0.0
                lp += 0.45 * (fb - lp)
                buf[i] = input + lp * 0.5
                wet[i] += buf[i] - input
            }
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val dry = if (i < x.size) x[i].toDouble() else 0.0
            out[i] = (dry + wet[i] / delays.size * mix * 2).toFloat()
        }
        return out
    }

    fun normalize(x: FloatArray, peak: Float = 0.92f): FloatArray {
        var m = 0f
        for (v in x) m = max(m, abs(v))
        if (m < 1e-6f) return x
        val g = peak / m
        for (i in x.indices) x[i] *= g
        return x
    }

    fun mixAt(dst: FloatArray, src: FloatArray, at: Int, gain: Float = 1f) {
        for (i in src.indices) {
            val j = at + i
            if (j in dst.indices) dst[j] += src[i] * gain
        }
    }

    fun tick(rate: Int): FloatArray {
        val len = (0.035 * rate).toInt()
        return FloatArray(len) { k ->
            val t = k.toDouble() / rate
            (sin(2 * PI * 1000 * t) * exp(-t / 0.008) * 0.6).toFloat()
        }
    }

    /** 음높이와 목소리 울림을 함께 낮춤 (느려짐). factor<1 이면 낮아짐 */
    fun resample(x: FloatArray, factor: Double): FloatArray {
        if (factor >= 0.999 && factor <= 1.001) return x
        val n = (x.size / factor).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i * factor
            val k = pos.toInt()
            val f = (pos - k).toFloat()
            val a = x.getOrElse(k) { 0f }
            val b = x.getOrElse(k + 1) { 0f }
            out[i] = a + (b - a) * f
        }
        return out
    }

    /** 저음 보강 */
    fun bassBoost(x: FloatArray, rate: Int, amount: Double, cutoffHz: Double = 200.0): FloatArray {
        if (amount <= 0) return x
        val a = 1 - exp(-2 * PI * cutoffHz / rate)
        var lp = 0.0
        return FloatArray(x.size) { i ->
            lp += a * (x[i] - lp)
            (x[i] + lp * amount).toFloat()
        }
    }

    /** 거친 질감 (부드러운 찌그러뜨림) */
    fun drive(x: FloatArray, k: Double): FloatArray {
        if (k <= 1.0) return x
        val norm = kotlin.math.tanh(k)
        return FloatArray(x.size) { i -> (kotlin.math.tanh(x[i] * k) / norm).toFloat() }
    }

    /** 으르렁거리는 떨림 */
    fun growl(x: FloatArray, rate: Int, depth: Double, hz: Double = 32.0): FloatArray {
        if (depth <= 0) return x
        return FloatArray(x.size) { i ->
            val m = 1 - depth + depth * abs(sin(2 * PI * hz * i / rate))
            (x[i] * m).toFloat()
        }
    }

    /** 고음 깎기 (멀리서 들리는 느낌) */
    fun lowpass(x: FloatArray, rate: Int, hz: Double): FloatArray {
        if (hz <= 0) return x
        val a = 1 - exp(-2 * PI * hz / rate)
        var y = 0.0
        return FloatArray(x.size) { i -> y += a * (x[i] - y); y.toFloat() }
    }

    /** 메아리 */
    fun echo(x: FloatArray, rate: Int, amount: Double, delaySec: Double = 0.28): FloatArray {
        if (amount <= 0) return x
        val d = (delaySec * rate).toInt()
        val out = FloatArray(x.size + d * 2)
        for (i in x.indices) {
            out[i] += x[i]
            out[i + d] += (x[i] * amount).toFloat()
            out[i + 2 * d] += (x[i] * amount * amount).toFloat()
        }
        return out
    }

    fun gain(x: FloatArray, g: Double): FloatArray { for (i in x.indices) x[i] = (x[i] * g).toFloat(); return x }

    /** 잔잔한 음악 (약 20초) */
    fun music(rate: Int): FloatArray {
        val chords = arrayOf(
            doubleArrayOf(261.63, 329.63, 392.00),
            doubleArrayOf(220.00, 261.63, 329.63),
            doubleArrayOf(174.61, 220.00, 261.63),
            doubleArrayOf(196.00, 246.94, 293.66),
        )
        val chordSec = 2.5
        val total = 8
        val out = FloatArray((rate * (chordSec * total + 1.5)).toInt())
        for (c in 0 until total) {
            val ch = chords[c % 4]
            val start = (c * chordSec * rate).toInt()
            val len = ((chordSec + 0.8) * rate).toInt()
            // 패드
            for (k in 0 until len) {
                val i = start + k
                if (i >= out.size) break
                val t = k.toDouble() / rate
                val env = min(t / 0.6, 1.0) * min(max((chordSec + 0.8 - t) / 0.8, 0.0), 1.0)
                var s = 0.0
                for (f in ch) s += sin(2 * PI * f * t)
                out[i] += (s * 0.07 * env).toFloat()
            }
            // 아르페지오
            for (a in 0 until 8) {
                val f = ch[a % 3] * 2
                val st = start + (a * chordSec / 8 * rate).toInt()
                val pl = (0.9 * rate).toInt()
                for (k in 0 until pl) {
                    val i = st + k
                    if (i >= out.size) break
                    val t = k.toDouble() / rate
                    out[i] += (sin(2 * PI * f * t) * exp(-t / 0.35) * 0.09).toFloat()
                }
            }
        }
        return out
    }
}

