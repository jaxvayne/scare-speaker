package com.scarespeaker

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 목소리 스타일 프리셋 */
enum class Style(
    val label: String,
    val ttsPitch: Float, val ttsRate: Float,
    val shift: Double,          // 음높이+울림 낮추기 (1=그대로)
    val bass: Double, val drive: Double, val growl: Double,
    val reverbMix: Double, val reverbSize: Double,
    val lowpassHz: Double, val echo: Double,
) {
    GIANT("덩치 큰 거인 (추천)", 0.80f, 1.20f, 0.72, 1.2, 2.2, 0.12, 0.40, 1.4, 0.0, 0.0),
    FAR("거인 + 복도 끝 울림", 0.80f, 1.20f, 0.72, 1.0, 1.8, 0.0, 0.55, 1.8, 3000.0, 0.30),
    GROWL("거인 + 으르렁", 0.80f, 1.20f, 0.70, 1.2, 3.5, 0.32, 0.35, 1.2, 0.0, 0.0),
}

/** 추천 목소리가 이 폰에 없을 때 (구글 TTS 설치/업데이트 안내용) */
class VoiceMissing(msg: String) : Exception(msg)

const val GOOGLE_TTS = "com.google.android.tts"

/** 폰 TTS로 음성 파일을 만든다. 외부 서버 없이 폰 안에서 처리. */
class ClipGenerator(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private val waits = ConcurrentHashMap<String, CountDownLatch>()
    private val cache = HashMap<String, Pcm>()

    private fun initTts(): TextToSpeech {
        val latch = CountDownLatch(1)
        var ok = false
        val listener = TextToSpeech.OnInitListener { status ->
            ok = status == TextToSpeech.SUCCESS
            latch.countDown()
        }
        // 구글 음성(ko-kr-x-…)을 쓰면 기본 엔진이 삼성이라도 구글 엔진으로 만듦 → 어느 폰에서나 같은 목소리
        val engine = Prefs.ttsEngine.ifEmpty { if (Prefs.ttsVoice.startsWith("ko-kr-x-")) GOOGLE_TTS else "" }
        val t = if (engine.isNotEmpty()) TextToSpeech(ctx.applicationContext, listener, engine)
        else TextToSpeech(ctx.applicationContext, listener)
        latch.await(15, TimeUnit.SECONDS)
        if (!ok) throw IllegalStateException("TTS를 시작하지 못했어요")
        val lr = t.setLanguage(Locale.KOREAN)
        if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("한국어 TTS 음성이 없어요 (설정 > 텍스트 음성 변환에서 한국어 음성 설치)")
        }
        if (Prefs.ttsVoice.isNotEmpty()) {
            val v = try { t.voices?.firstOrNull { it.name == Prefs.ttsVoice } } catch (_: Exception) { null }
            if (v == null) {
                t.shutdown()
                throw VoiceMissing("이 폰에 추천 목소리(${Prefs.ttsVoice})가 없어요")
            }
            t.voice = v
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
        val ck = "$text|$pitch|$rate"
        cache[ck]?.let { return it }
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
        val p = trim(Wav.read(f))
        f.delete()
        cache[ck] = p
        return p
    }

    /** 앞뒤 무음 자르기 (문장 사이 간격을 직접 맞추려고) */
    private fun trim(p: Pcm): Pcm {
        val th = 0.01f
        var a = 0; var b = p.data.size - 1
        while (a < b && kotlin.math.abs(p.data[a]) < th) a++
        while (b > a && kotlin.math.abs(p.data[b]) < th) b--
        val pad = (0.03 * p.rate).toInt()
        a = (a - pad).coerceAtLeast(0); b = (b + pad).coerceAtMost(p.data.size - 1)
        return Pcm(p.data.copyOfRange(a, b + 1), p.rate)
    }

    /** 첫 마디는 길게 끌고, 문장 사이엔 뜸을 두어 이어 붙인 뒤 스타일 효과 */
    fun voiceLine(call: String, parts: List<String>, style: Style, steps: String?): Pcm {
        val pieces = mutableListOf<FloatArray>()
        var rate = 22050
        if (call.isNotBlank()) {
            val c = synth(call, style.ttsPitch, style.ttsRate * 0.6f)
            rate = c.rate
            pieces += c.data
            pieces += Dsp.silence(rate, 0.45)
        }
        for ((i, p) in parts.withIndex()) {
            val v = synth(p, style.ttsPitch, style.ttsRate)
            rate = v.rate
            pieces += v.data
            if (i < parts.size - 1) pieces += Dsp.silence(rate, 0.3)
        }
        var x = Dsp.concat(*pieces.toTypedArray())
        // 굵은 목소리 처리
        x = Dsp.resample(x, style.shift)
        x = Dsp.bassBoost(x, rate, style.bass)
        x = Dsp.growl(x, rate, style.growl)
        x = Dsp.normalize(x, 0.9f)
        x = Dsp.drive(x, style.drive)
        x = Dsp.lowpass(x, rate, style.lowpassHz)
        // 발소리
        if (Prefs.effects && steps == "approach") x = Dsp.concat(Dsp.footsteps(rate, true), Dsp.silence(rate, 0.15), x)
        if (Prefs.effects && steps == "recede") x = Dsp.concat(x, Dsp.silence(rate, 0.35), Dsp.footsteps(rate, false))
        x = Dsp.echo(x, rate, style.echo)
        x = Dsp.reverb(x, rate, style.reverbMix, 0.9, style.reverbSize)
        return Pcm(Dsp.normalize(Dsp.concat(Dsp.silence(rate, 0.35), x)), rate)
    }

    /** 미리듣기용 한 문장 */
    fun sample(style: Style, out: File) {
        try {
            Wav.write(out, voiceLine("이노오오옴", listOf("누가 안 자고 장난치니!"), style, null))
        } finally { shutdown() }
    }

    fun generateAll(progress: (String) -> Unit) {
        try {
            val dir = Scenario.clipsDir(ctx)
            val style = Prefs.style
            data class Job(val key: String, val c: Command, val role: Role, val a: Who, val t: Who)
            val jobs = mutableListOf<Job>()
            for (c in Scenario.commands) for (role in Scenario.roles(c)) for ((a, t) in Scenario.variants(c)) {
                jobs += Job(Scenario.clipKey(c, role, a, t), c, role, a, t)
            }
            val total = jobs.size + 2
            var done = 0
            for (j in jobs) {
                progress("음성 만드는 중 ${++done}/$total")
                val pcm = voiceLine(j.c.call, Scenario.ttsParts(j.c, j.role, j.a, j.t), style, j.c.steps)
                Wav.write(File(dir, "${j.key}.wav"), pcm)
            }

            // 테스트: 숫자 세기 (1초마다 '똑' + 숫자) — 평범한 목소리
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

            // 예전 버전 파일 정리
            val keep = jobs.map { "${it.key}.wav" }.toSet() + setOf("test_count.wav", "test_music.wav")
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

            Prefs.clipsSignature = Prefs.currentSignature()
            AppLog.d("음성 파일 생성 완료 ($total)")
        } finally {
            shutdown()
        }
    }

    private fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        /** 설치된 TTS 엔진과 한국어 음성 목록 */
        fun listVoices(ctx: Context, engine: String): Pair<List<Pair<String, String>>, List<String>> {
            val latch = CountDownLatch(1)
            val l = TextToSpeech.OnInitListener { latch.countDown() }
            val t = if (engine.isNotEmpty()) TextToSpeech(ctx.applicationContext, l, engine) else TextToSpeech(ctx.applicationContext, l)
            latch.await(10, TimeUnit.SECONDS)
            return try {
                val engines = t.engines.map { it.name to it.label }
                val voices = try {
                    // 얇은(여성) 음성은 목록에서 뺌
                    t.voices?.filter { it.locale.language == "ko" && listOf("-ism", "-kob", "-koa").none { f -> f in it.name.lowercase() } }
                        ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                        ?.map { it.name } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                engines to voices
            } finally { t.shutdown() }
        }
    }
}
