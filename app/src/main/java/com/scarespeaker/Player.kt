package com.scarespeaker

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 스피커/스탠바이미 재생, 음량 맞추기, 원래 음량으로 되돌리기 */
object Player {
    private val exec = Executors.newCachedThreadPool()
    private val generation = AtomicInteger(0)

    @Volatile var playing = false; private set
    @Volatile var playingTitle = ""; private set

    @Volatile private var activeCast: CastClient? = null
    @Volatile private var activeDlna: DlnaClient.Desc? = null

    // 재생 전 원래 음량 (연속 재생이면 처음 것을 유지)
    @Volatile private var savedCast: Double? = null
    @Volatile private var savedDlna: Int? = null

    init {
        Dedup.onRemoteTakeover = {
            // 다른 폰이 이어받았으면 그쪽이 되돌린다
            savedCast = null; savedDlna = null
        }
    }

    private fun useCast() = Prefs.target != Target.STANBYME
    private fun useDlna() = Prefs.target != Target.SPEAKER

    /**
     * 재생 시작. 기기 연결까지 기다린 뒤 돌아온다. 끝나면 onFinished가 불린다.
     * @return 한 기기라도 재생을 시작했으면 true
     */
    fun start(ctx: Context, file: File, title: String, key: String, onFinished: () -> Unit): Boolean {
        SoundServer.ensure(Scenario.clipsDir(ctx))
        val url = SoundServer.urlFor(file) ?: run {
            AppLog.d("Wi-Fi 주소를 찾지 못함")
            return false
        }
        val gen = generation.incrementAndGet()
        val durMs = (Wav.durationSec(file) * 1000).toLong()
        val remote = Dedup.remoteOriginal()
        val delay = Prefs.dlnaDelayMs
        val both = Prefs.target == Target.BOTH
        AppLog.d("재생 시작: $title → ${Prefs.target.label} ($url, ${durMs}ms)")

        val castJob: Future<Boolean>? = if (useCast()) exec.submit<Boolean> {
            if (both && delay < 0) Thread.sleep(-delay.toLong())
            startCast(ctx, url, title, remote?.first)
        } else null
        val dlnaJob: Future<Boolean>? = if (useDlna()) exec.submit<Boolean> {
            if (both && delay > 0) Thread.sleep(delay.toLong())
            startDlna(ctx, url, title, remote?.second)
        } else null

        val castOk = try { castJob?.get(25, TimeUnit.SECONDS) ?: false } catch (e: Exception) { AppLog.d("스피커 재생 실패: ${e.cause?.message ?: e.message}"); false }
        val dlnaOk = try { dlnaJob?.get(25, TimeUnit.SECONDS) ?: false } catch (e: Exception) { AppLog.d("스탠바이미 재생 실패: ${e.cause?.message ?: e.message}"); false }
        if (castJob != null) AppLog.d("스피커: ${if (castOk) "재생됨" else "실패"}")
        if (dlnaJob != null) AppLog.d("스탠바이미: ${if (dlnaOk) "재생됨" else "실패"}")
        if (!castOk && !dlnaOk) {
            if (generation.get() == gen) playing = false
            return false
        }
        if (generation.get() != gen) return true

        playing = true
        playingTitle = title
        Dedup.announcePlay(key, durMs + 500, savedCast, savedDlna)

        exec.execute {
            val end = System.currentTimeMillis() + durMs + 1200
            while (System.currentTimeMillis() < end && generation.get() == gen) Thread.sleep(100)
            if (generation.get() == gen) {
                finish(restore = true)
                onFinished()
            }
        }
        return true
    }

    /** 바로 멈춤 */
    fun stop(restore: Boolean) {
        generation.incrementAndGet()
        stopDevices()
        finish(restore)
    }

    /** 1초 동안 소리를 줄이며 멈춤 (오지 마세요/안녕히 가세요 앞에서 사용) */
    fun fadeOutAndStop() {
        generation.incrementAndGet()
        val c = activeCast
        val tv = activeTvCast
        val startCast = Prefs.castVolume
        val startDlna = Prefs.dlnaVolume
        for (i in 1..5) {
            val k = 1.0 - i / 5.0
            try { c?.setVolume(startCast * k) } catch (_: Exception) {}
            try { tv?.setVolume(startDlna * k / 100.0) } catch (_: Exception) {}
            Thread.sleep(200)
        }
        stopDevices()
        // 다음 대사를 위해 음량은 되돌리지 않음 (원래 음량은 계속 기억)
        try { activeCast?.close() } catch (_: Exception) {}
        try { activeTvCast?.close() } catch (_: Exception) {}
        activeCast = null
        activeTvCast = null
        playing = false
    }

    private fun stopDevices() {
        val c = activeCast
        if (useCast()) {
            try {
                if (c != null) c.stopApp()
                else connectCast(App.instance)?.let { it.stopApp(); it.close() }
            } catch (e: Exception) { AppLog.d("스피커 정지 실패: ${e.message}") }
        }
        if (useDlna()) {
            try { activeDlna?.let { DlnaClient.stop(it) } } catch (_: Exception) {}
            try {
                val tv = activeTvCast
                if (tv != null) tv.stopApp()
                else connectTvCast(App.instance)?.let { it.stopApp(); it.close() }
            } catch (e: Exception) { AppLog.d("스탠바이미 정지 실패: ${e.message}") }
            if (activeDlna == null && !preferTvCast) {
                try { dlnaDesc(App.instance)?.let { DlnaClient.stop(it) } } catch (_: Exception) {}
            }
        }
    }

    private fun finish(restore: Boolean) {
        if (restore && Prefs.restoreVolume) {
            savedCast?.let { v ->
                try { (activeCast ?: connectCast(App.instance))?.setVolume(v) } catch (_: Exception) {}
                AppLog.d("스피커 음량 되돌림 → ${"%.2f".format(v)}")
            }
            savedDlna?.let { v ->
                try {
                    val tv = activeTvCast
                    if (tv != null) tv.setVolume(v / 100.0)
                    else connectTvCast(App.instance)?.let { it.setVolume(v / 100.0); it.close() }
                } catch (_: Exception) {}
                AppLog.d("스탠바이미 음량 되돌림 → $v")
            }
        }
        if (restore) { savedCast = null; savedDlna = null }
        try { activeCast?.close() } catch (_: Exception) {}
        try { activeTvCast?.close() } catch (_: Exception) {}
        activeCast = null
        activeTvCast = null
        activeDlna = null
        playing = false
        playingTitle = ""
    }

    private fun startCast(ctx: Context, url: String, title: String, remoteOrig: Double?): Boolean {
        val c = connectCast(ctx) ?: return false
        AppLog.d("스피커 연결됨: ${Prefs.castName} (${Prefs.castHost})")
        try { activeCast?.close() } catch (_: Exception) {}
        activeCast = c
        val v = c.getVolume()
        AppLog.d("스피커 음량 읽기: ${v?.level ?: "응답 없음"} (단위 ${v?.step})")
        if (v != null) {
            if (v.step > 0) Prefs.castStep = v.step
            if (savedCast == null) savedCast = remoteOrig?.takeIf { it >= 0 } ?: v.level
        }
        c.setVolume(Prefs.castVolume)
        return c.load(url, title)
    }

    /** 스탠바이미: DLNA로 먼저 시도하고, 안 되면 TV의 캐스트 기능으로 재생 */
    private fun startDlna(ctx: Context, url: String, title: String, remoteOrig: Int?): Boolean {
        // 음량은 TV 캐스트 연결로 맞춤 (DLNA 음량 조절을 막아 둔 TV가 있음)
        val tv = connectTvCast(ctx)
        try {
            val v = tv?.getVolume()
            AppLog.d("스탠바이미 음량 읽기(캐스트): ${v?.level ?: "응답 없음"}")
            if (v != null) {
                if (savedDlna == null) savedDlna = remoteOrig?.takeIf { it >= 0 } ?: Math.round(v.level * 100).toInt()
                tv?.setVolume(Prefs.dlnaVolume / 100.0)
            }
        } catch (e: Exception) { AppLog.d("스탠바이미 음량 조절 실패: ${e.message}") }
        try { activeTvCast?.close() } catch (_: Exception) {}
        activeTvCast = tv

        if (!preferTvCast) {
            val d = dlnaDesc(ctx)
            if (d != null) {
                activeDlna = d
                try {
                    if (DlnaClient.play(d, url, title)) { AppLog.d("스탠바이미: DLNA로 재생"); return true }
                } catch (e: Exception) { AppLog.d("스탠바이미 DLNA 재생 실패: ${e.message} → 캐스트로 시도") }
                activeDlna = null
            }
        }
        val c = tv ?: return false
        val ok = c.load(url, title)
        if (ok) { preferTvCast = true; AppLog.d("스탠바이미: 캐스트로 재생") }
        return ok
    }

    /** DLNA가 한 번 실패하면 이후엔 바로 캐스트로 (앱을 다시 켜면 초기화) */
    @Volatile private var preferTvCast = false
    @Volatile private var activeTvCast: CastClient? = null

    fun connectTvCast(ctx: Context): CastClient? {
        if (Prefs.tvCastHost.isNotEmpty()) {
            try { return CastClient.connect(Prefs.tvCastHost, Prefs.tvCastPort) } catch (e: Exception) {
                AppLog.d("저장된 TV 캐스트 주소 연결 실패, 다시 찾는 중: ${e.message}")
            }
        }
        val list = Discovery.findCast(ctx, 4000)
        val pick = list.firstOrNull { it.name.contains("webOS", true) || it.name.contains("TV", true) }
            ?: run { AppLog.d("TV 캐스트를 찾지 못함"); return null }
        Prefs.tvCastHost = pick.host; Prefs.tvCastPort = pick.port
        return try { CastClient.connect(pick.host, pick.port) } catch (e: Exception) {
            AppLog.d("TV 캐스트 연결 실패: ${e.message}"); null
        }
    }

    // ---------- 기기 연결 ----------

    fun connectCast(ctx: Context): CastClient? {
        // 1.0.1에서 TV가 스피커로 잘못 저장된 경우 지우기
        if (Prefs.castName.contains("webOS", true)) { Prefs.castName = ""; Prefs.castHost = "" }
        if (Prefs.castHost.isNotEmpty()) {
            try { return CastClient.connect(Prefs.castHost, Prefs.castPort) } catch (e: Exception) {
                AppLog.d("저장된 스피커 주소 연결 실패, 다시 찾는 중: ${e.message}")
            }
        }
        val list = Discovery.findCast(ctx)
        val pick = list.firstOrNull { Prefs.castName.isNotEmpty() && it.name == Prefs.castName }
            ?: list.firstOrNull { it.name.contains("S95", true) || it.name.contains("Speaker", true) || it.name.contains("사운드바") }
            ?: list.firstOrNull { !it.name.contains("webOS", true) && !it.name.contains("TV", true) }
            ?: run {
                AppLog.d("스피커를 찾지 못함 (찾은 기기: ${list.joinToString { it.name }.ifEmpty { "없음" }})")
                return null
            }
        Prefs.castName = pick.name; Prefs.castHost = pick.host; Prefs.castPort = pick.port
        return try { CastClient.connect(pick.host, pick.port) } catch (e: Exception) {
            AppLog.d("스피커 연결 실패: ${e.message}"); null
        }
    }

    fun dlnaDesc(ctx: Context): DlnaClient.Desc? {
        if (Prefs.dlnaLocation.isNotEmpty()) {
            DlnaClient.describe(Prefs.dlnaLocation)?.let { return it }
        }
        val list = Discovery.findDlna(ctx)
        val pick = list.firstOrNull { Prefs.dlnaName.isNotEmpty() && it.name == Prefs.dlnaName }
            ?: list.firstOrNull { it.name.contains("webOS", true) || it.name.contains("LG", true) }
            ?: list.firstOrNull()
            ?: run { AppLog.d("스탠바이미를 찾지 못함 (TV가 켜져 있는지 확인)"); return null }
        Prefs.dlnaName = pick.name; Prefs.dlnaLocation = pick.location
        return DlnaClient.describe(pick.location)
    }

    // ---------- 설정 화면용 ----------

    /** 재생 중이면 바뀐 음량을 바로 적용 */
    fun applyLiveVolume() {
        if (!playing) return
        exec.execute {
            try { activeCast?.setVolume(Prefs.castVolume) } catch (_: Exception) {}
            try { activeTvCast?.setVolume(Prefs.dlnaVolume / 100.0) } catch (_: Exception) {}
        }
    }

    /** 기기들의 지금 음량 읽기: (스피커 음량, 스탠바이미 음량) */
    fun readDeviceVolumes(ctx: Context): Pair<CastClient.Volume?, Int?> {
        val cv = try { connectCast(ctx)?.let { c -> c.getVolume().also { c.close() } } } catch (_: Exception) { null }
        cv?.let { if (it.step > 0) Prefs.castStep = it.step }
        val dv = try { connectTvCast(ctx)?.let { c -> c.getVolume().also { c.close() } }?.let { Math.round(it.level * 100).toInt() } } catch (_: Exception) { null }
        return cv to dv
    }
}
