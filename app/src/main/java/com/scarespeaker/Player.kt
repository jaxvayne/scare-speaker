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

        // 1단계: 두 기기 모두 소리를 받아 준비 → 2단계: 같은 순간에 재생 (싱크 맞추기)
        val jobs = (if (useCast()) 1 else 0) + (if (useDlna()) 1 else 0)
        val ready = java.util.concurrent.CountDownLatch(jobs)
        val castJob: Future<Boolean>? = if (useCast()) exec.submit<Boolean> {
            val ok = try { prepareCast(ctx, url, title, remote?.first) } catch (e: Exception) {
                AppLog.d("스피커 준비 실패: ${e.message}"); false
            } finally { ready.countDown() }
            if (!ok) return@submit false
            ready.await(12, TimeUnit.SECONDS)
            if (generation.get() != gen) return@submit false
            if (both && delay < 0) Thread.sleep(-delay.toLong())
            AppLog.d("스피커 ▶ 재생 명령")
            activeCast?.play() ?: false
        } else null
        val dlnaJob: Future<Boolean>? = if (useDlna()) exec.submit<Boolean> {
            val d = try { prepareDlna(ctx, url, title, remote?.second) } catch (e: Exception) {
                AppLog.d("스탠바이미 준비 실패: ${e.message}"); null
            } finally { ready.countDown() }
            if (d == null) return@submit false
            ready.await(12, TimeUnit.SECONDS)
            if (generation.get() != gen) return@submit false
            if (both && delay > 0) Thread.sleep(delay.toLong())
            AppLog.d("스탠바이미 ▶ 재생 명령")
            DlnaClient.start(d)
            true
        } else null

        val castOk = try { castJob?.get(60, TimeUnit.SECONDS) ?: false } catch (e: Exception) { AppLog.d("스피커 재생 실패: ${e.cause?.message ?: e.message}"); false }
        val dlnaOk = try { dlnaJob?.get(60, TimeUnit.SECONDS) ?: false } catch (e: Exception) { AppLog.d("스탠바이미 재생 실패: ${e.cause?.message ?: e.message}"); false }
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
                if (c != null && !c.isClosed) c.stopApp()
                else if (Prefs.castHost.isNotEmpty()) CastClient.connect(Prefs.castHost, Prefs.castPort, 3000).let { it.stopApp(); it.close() }
            } catch (e: Exception) { AppLog.d("스피커 정지 실패: ${e.message}") }
        }
        if (useDlna()) {
            try { activeDlna?.let { DlnaClient.stop(it) } } catch (_: Exception) {}
            try {
                val tv = activeTvCast
                if (tv != null) tv.stopApp()
                else connectTvCast(App.instance)?.let { it.stopApp(); it.close() }
            } catch (e: Exception) { AppLog.d("스탠바이미 정지 실패: ${e.message}") }
            if (activeDlna == null) {
                try { dlnaDesc(App.instance)?.let { DlnaClient.stop(it) } } catch (_: Exception) {}
            }
        }
    }

    private fun finish(restore: Boolean) {
        if (restore && Prefs.restoreVolume) {
            savedCast?.takeIf { it > 0.001 }?.let { v ->
                try {
                    val c = activeCast
                    if (c != null && !c.isClosed) c.setVolume(v, 3)
                    else CastClient.connect(Prefs.castHost, Prefs.castPort, 3000).let { it.setVolume(v, 3); it.close() }
                } catch (_: Exception) { AppLog.d("스피커 음량 되돌리기 실패") }
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

    /** 스피커: 연결, 음량 맞추고, 일시정지 상태로 소리를 받아 둠 */
    private fun prepareCast(ctx: Context, url: String, title: String, remoteOrig: Double?): Boolean {
        val c = connectCast(ctx) ?: return false
        AppLog.d("스피커 연결됨: ${Prefs.castName} (${Prefs.castHost})")
        try { activeCast?.close() } catch (_: Exception) {}
        activeCast = c
        try {
            val st = c.getState()
            if (st != null && st.standby == true && !st.appRunning) { wokeSpeaker = true; AppLog.d("스피커가 대기 상태라 깨워서 재생") }
        } catch (_: Exception) {}
        val v = c.getVolume()
        AppLog.d("스피커 음량 읽기: ${v?.level ?: "응답 없음"} (단위 ${v?.step})")
        if (v != null) {
            if (v.step > 0) Prefs.castStep = v.step
            if (savedCast == null) savedCast = remoteOrig?.takeIf { it >= 0 } ?: v.level
        }
        c.setVolume(Prefs.castVolume)
        if (!c.load(url, title, autoplay = false)) return false
        Thread.sleep(300) // 버퍼링 여유
        AppLog.d("스피커 준비 완료")
        return true
    }

    /** 스탠바이미: 음량은 TV 캐스트로 맞추고, 소리는 DLNA로 준비 */
    private fun prepareDlna(ctx: Context, url: String, title: String, remoteOrig: Int?): DlnaClient.Desc? {
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

        for (attempt in 1..2) {
            val d = dlnaDesc(ctx) ?: return null
            activeDlna = d
            try {
                DlnaClient.prepare(d, url, title)
                AppLog.d("스탠바이미 준비 완료")
                return d
            } catch (e: java.io.IOException) {
                // 공유기를 껐다 켜서 TV 주소가 바뀐 경우: 다시 찾기
                AppLog.d("스탠바이미 연결 실패(${e.message}), 주소 다시 찾는 중")
                DlnaClient.forget(Prefs.dlnaLocation)
                Prefs.dlnaLocation = ""
                activeDlna = null
            } catch (e: Exception) {
                AppLog.d("스탠바이미 준비 실패: ${e.message}")
                activeDlna = null
                return null
            }
        }
        return null
    }

    @Volatile private var activeTvCast: CastClient? = null

    /** 이 주소가 스탠바이미인지 */
    fun isTvHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host == Prefs.tvCastHost) return true
        return try { java.net.URL(Prefs.dlnaLocation).host == host } catch (_: Exception) { false }
    }

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
        if (Prefs.castName.contains("webOS", true) || isTvHost(Prefs.castHost)) {
            if (Prefs.castHost.isNotEmpty()) AppLog.d("스피커 주소 ${Prefs.castHost}는 스탠바이미라서 지움")
            Prefs.castName = ""; Prefs.castHost = ""
        }
        if (Prefs.castHost.isNotEmpty()) {
            // 사운드바는 재생이 끝난 직후 잠깐(20~30초) 응답하지 않을 때가 있어 몇 번 다시 시도
            for (attempt in 1..4) {
                try {
                    val c = CastClient.connect(Prefs.castHost, Prefs.castPort, 3000)
                    if (attempt > 1) AppLog.d("스피커 재시도 $attempt 번째에 연결됨")
                    return c
                } catch (e: Exception) {
                    AppLog.d("스피커 연결 실패 ($attempt/4): ${e.message?.take(60)}")
                    Thread.sleep(2000)
                }
            }
            AppLog.d("저장된 스피커 주소로 연결 안 됨, 다시 찾는 중")
        }
        val list = Discovery.findCast(ctx)
        val pick = list.firstOrNull { Prefs.castName.isNotEmpty() && it.name == Prefs.castName }
            ?: list.firstOrNull { it.name.contains("S95", true) || it.name.contains("Speaker", true) || it.name.contains("사운드바") }
            ?: list.firstOrNull { !it.name.contains("webOS", true) && !it.name.contains("TV", true) && !isTvHost(it.host) }
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

    // ---------- 기기 준비 / 정리 ----------

    @Volatile var prepared = false; private set
    @Volatile var deviceStatus = ""; private set
    /** 앱이 대기 상태에서 깨운 스피커인지 (끌 때 이것만 정리) */
    @Volatile private var wokeSpeaker = false

    /** 스피커/스탠바이미를 재생할 수 있게 준비. 사용 중인 기기는 건드리지 않음 */
    fun prepareDevices(ctx: Context) {
        if (useCast()) {
            try {
                connectCast(ctx)?.let { c ->
                    try {
                        val st = c.getState()
                        if (st != null && st.standby == true && !st.appRunning && c.launchOnly()) {
                            wokeSpeaker = true
                            AppLog.d("기기 준비: 대기 중인 스피커를 깨움")
                        }
                    } finally { c.close() }
                }
            } catch (e: Exception) { AppLog.d("스피커 준비 실패: ${e.message}") }
        }
        prepared = true
        checkDevices(ctx)
    }

    /** 지금 시점의 두 기기 상태 확인 (깨우지는 않음) */
    fun checkDevices(ctx: Context) {
        val lines = mutableListOf<String>()
        // 스피커
        lines += try {
            val c = connectCast(ctx)
            if (c == null) "🔈 스피커: 연결 안 됨 (꺼짐 또는 Wi-Fi 확인)"
            else try {
                val st = c.getState()
                when {
                    st == null -> "🔈 스피커: 응답 없음"
                    wokeSpeaker -> "🔈 스피커: 켜짐 (앱이 깨움) ✓"
                    st.standby == true -> "🔈 스피커: 대기 중 (재생하면 깨움)"
                    else -> "🔈 스피커: 켜짐 ✓"
                }
            } finally { c.close() }
        } catch (e: Exception) { "🔈 스피커: 확인 실패" }
        // 스탠바이미
        val tvOk = try {
            val loc = Prefs.dlnaLocation
            if (loc.isEmpty()) dlnaDesc(ctx) != null
            else {
                val h = java.net.URL(loc).openConnection() as java.net.HttpURLConnection
                h.connectTimeout = 2500; h.readTimeout = 2500
                val code = h.responseCode; h.disconnect()
                code < 400
            }
        } catch (_: Exception) {
            DlnaClient.forget(Prefs.dlnaLocation); Prefs.dlnaLocation = ""
            try { dlnaDesc(ctx) != null } catch (_: Exception) { false }
        }
        lines += if (tvOk) "📺 스탠바이미: 켜짐 ✓" else "📺 스탠바이미: 꺼짐 (원격 켜기는 다음 버전)"
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        lines += "($t 기준 · 재생 기기: ${Prefs.target.label})"
        deviceStatus = lines.joinToString("\n")
        AppLog.d("기기 상태: " + deviceStatus.replace("\n", " / "))
    }

    /** 준비 끄기 / 자동 꺼짐: 앱이 깨운 기기만 정리 */
    fun releaseDevices() {
        if (wokeSpeaker && !playing) {
            try {
                CastClient.connect(Prefs.castHost, Prefs.castPort, 3000).let { it.stopApp(); it.close() }
                AppLog.d("앱이 깨운 스피커 정리 (대기로 돌아감)")
            } catch (e: Exception) { AppLog.d("스피커 정리 실패: ${e.message}") }
        }
        wokeSpeaker = false
        prepared = false
    }
}
