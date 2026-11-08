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
    fun reverb(x: FloatArray, rate: Int, mix: Double = 0.32, tailSec: Double = 0.9): FloatArray {
        val n = x.size + (tailSec * rate).toInt()
        val wet = DoubleArray(n)
        val delays = doubleArrayOf(0.067, 0.089, 0.113, 0.149)
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

/** 폰 TTS로 음성 파일을 만든다. 외부 서버 없이 폰 안에서 처리. */
class ClipGenerator(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private val waits = ConcurrentHashMap<String, CountDownLatch>()

    private fun initTts(): TextToSpeech {
        val latch = CountDownLatch(1)
        var ok = false
        val t = TextToSpeech(ctx.applicationContext) { status ->
            ok = status == TextToSpeech.SUCCESS
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        if (!ok) throw IllegalStateException("TTS를 시작하지 못했어요")
        val lr = t.setLanguage(Locale.KOREAN)
        if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("한국어 TTS 음성이 없어요 (설정 > 텍스트 음성 변환에서 한국어 음성 설치)")
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { waits.remove(it)?.countDown() } }
            @Deprecated("deprecated") override fun onError(id: String?) { id?.let { waits.remove(it)?.countDown() } }
            override fun onError(id: String?, errorCode: Int) { id?.let { waits.remove(it)?.countDown() } }
        })
        return t
    }

    private fun synth(text: String, pitch: Float, rate: Float): Pcm {
        val t = tts ?: initTts().also { tts = it }
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        val f = File(ctx.cacheDir, "tts_${System.nanoTime()}.wav")
        val id = "u${System.nanoTime()}"
        val latch = CountDownLatch(1)
        waits[id] = latch
        val r = t.synthesizeToFile(text, Bundle(), f, id)
        if (r != TextToSpeech.SUCCESS) throw IllegalStateException("TTS 변환 실패")
        latch.await(30, TimeUnit.SECONDS)
        if (!f.exists() || f.length() < 100) throw IllegalStateException("TTS 결과가 비어 있어요: $text")
        val p = Wav.read(f)
        f.delete()
        return p
    }

    private fun scary(text: String): Pcm = synth(text, 0.55f, 0.82f)

    fun generateAll(progress: (String) -> Unit) {
        try {
            val dir = Scenario.clipsDir(ctx)
            val effects = Prefs.effects
            val jobs = mutableListOf<Triple<String, String, Cmd>>()
            for (role in Role.values()) {
                for (cmd in listOf(Cmd.COME, Cmd.GOAWAY, Cmd.END))
                    jobs += Triple(Scenario.clipKey(cmd, role, NameOpt.NONE), Scenario.ttsText(cmd, role, NameOpt.NONE), cmd)
            }
            for (opt in NameOpt.values()) {
                for (cmd in listOf(Cmd.TAKE, Cmd.NOSLEEP))
                    jobs += Triple(Scenario.clipKey(cmd, Role.DAD, opt), Scenario.ttsText(cmd, Role.DAD, opt), cmd)
            }
            val total = jobs.size + 2
            var done = 0
            for ((key, text, cmd) in jobs) {
                progress("음성 만드는 중 ${++done}/$total")
                val v = scary(text)
                val r = v.rate
                val body = when {
                    effects && cmd == Cmd.COME ->
                        Dsp.concat(Dsp.footsteps(r, true), Dsp.silence(r, 0.15), v.data)
                    effects && (cmd == Cmd.GOAWAY || cmd == Cmd.END) ->
                        Dsp.concat(v.data, Dsp.silence(r, 0.35), Dsp.footsteps(r, false))
                    else -> v.data
                }
                val out = Dsp.normalize(Dsp.concat(Dsp.silence(r, 0.35), Dsp.reverb(body, r)))
                Wav.write(File(dir, "$key.wav"), Pcm(out, r))
            }

            // 테스트: 숫자 세기 (1초마다 '똑' + 숫자)
            progress("테스트 소리 만드는 중 ${++done}/$total")
            val nums = listOf("하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉", "열")
            val first = synth(nums[0], 1.0f, 1.0f)
            val rate = first.rate
            val count = FloatArray((rate * 12.0).toInt())
            val tick = Dsp.tick(rate)
            for ((i, w) in nums.withIndex()) {
                val at = (rate * (0.6 + i)).toInt()
                Dsp.mixAt(count, tick, at)
                val v = if (i == 0) first else synth(w, 1.0f, 1.1f)
                Dsp.mixAt(count, v.data, at + (0.06 * rate).toInt(), 0.9f)
            }
            Wav.write(File(dir, "test_count.wav"), Pcm(Dsp.normalize(count, 0.85f), rate))

            progress("테스트 소리 만드는 중 ${++done}/$total")
            Wav.write(File(dir, "test_music.wav"), Pcm(Dsp.normalize(Dsp.music(22050), 0.7f), 22050))

            Prefs.clipsSignature = Prefs.currentSignature()
            AppLog.d("음성 파일 생성 완료 ($total)")
        } finally {
            tts?.shutdown()
            tts = null
        }
    }
}
