package com.scarespeaker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "이놈아저씨 상태", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        Dedup.start(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                if (started++ == 0) opened = true
            }
            override fun onActivityStopped(a: android.app.Activity) { started-- }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    companion object {
        private var started = 0
        private var opened = false

        /** 앱이 (백그라운드에서) 새로 열렸으면 true, 한 번만 */
        fun consumeOpened(): Boolean { val o = opened; opened = false; return o }

        const val CHANNEL = "scare_status"
        lateinit var instance: App
            private set
    }
}

/** 진단용 기록. 설정 화면의 '진단 기록'에서 볼 수 있다. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    @Synchronized
    fun d(msg: String) {
        android.util.Log.d("ScareSpeaker", msg)
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}

enum class Role(val label: String) { DAD("아빠"), MOM("엄마"), GRANDMA("할머니") }
enum class Target(val label: String) { SPEAKER("LG 스피커"), STANBYME("스탠바이미"), BOTH("둘 다") }
enum class DedupMode(val label: String) { ANY("어떤 소리든 막기"), SAME("같은 소리만 막기") }
enum class TestSound(val label: String) { COUNT("숫자 세기"), MUSIC("잔잔한 음악") }

/**
 * 앱 설정. 기본값은 여기서 정하고, 테스트로 정한 값은 다음 업데이트에서 기본값으로 바꾼다.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!sp.contains("deviceId")) sp.edit().putString("deviceId", UUID.randomUUID().toString()).apply()
    }

    val deviceId: String get() = sp.getString("deviceId", "")!!

    var roleChosen: Boolean
        get() = sp.getBoolean("roleChosen", false)
        set(v) = sp.edit().putBoolean("roleChosen", v).apply()

    var role: Role
        get() = Role.valueOf(sp.getString("role", Role.DAD.name)!!)
        set(v) = sp.edit().putString("role", v.name).apply()

    var name1: String
        get() = sp.getString("name1", "연우")!!
        set(v) = sp.edit().putString("name1", v.trim()).apply()

    var name2: String
        get() = sp.getString("name2", "건우")!!
        set(v) = sp.edit().putString("name2", v.trim()).apply()

    var target: Target
        get() = Target.valueOf(sp.getString("target", Target.SPEAKER.name)!!)
        set(v) = sp.edit().putString("target", v.name).apply()

    // ----- 스피커(크롬캐스트) -----
    var castName: String
        get() = sp.getString("castName", "")!!
        set(v) = sp.edit().putString("castName", v).apply()
    var castHost: String
        get() = sp.getString("castHost", "")!!
        set(v) = sp.edit().putString("castHost", v).apply()
    var castPort: Int
        get() = sp.getInt("castPort", 8009)
        set(v) = sp.edit().putInt("castPort", v).apply()
    /** 재생할 때 맞출 음량 (0.0~1.0) */
    var castVolume: Double
        get() = sp.getFloat("castVolume", 0.12f).toDouble()
        set(v) = sp.edit().putFloat("castVolume", v.coerceIn(0.0, 1.0).toFloat()).apply()
    /** 기기가 알려주는 최소 단위 */
    var castStep: Double
        get() = sp.getFloat("castStep", 0.02f).toDouble()
        set(v) = sp.edit().putFloat("castStep", v.toFloat()).apply()

    // ----- 스탠바이미(DLNA) -----
    var dlnaName: String
        get() = sp.getString("dlnaName", "")!!
        set(v) = sp.edit().putString("dlnaName", v).apply()
    var dlnaLocation: String
        get() = sp.getString("dlnaLocation", "")!!
        set(v) = sp.edit().putString("dlnaLocation", v).apply()
    var tvCastHost: String
        get() = sp.getString("tvCastHost", "")!!
        set(v) = sp.edit().putString("tvCastHost", v).apply()
    var tvCastPort: Int
        get() = sp.getInt("tvCastPort", 8009)
        set(v) = sp.edit().putInt("tvCastPort", v).apply()
    /** 재생할 때 맞출 음량 (0~100, 1단위) */
    var dlnaVolume: Int
        get() = sp.getInt("dlnaVolume", 12)
        set(v) = sp.edit().putInt("dlnaVolume", v.coerceIn(0, 100)).apply()
    /** 스탠바이미를 늦게(+) / 일찍(-) 시작하는 시간(ms) */
    var dlnaDelayMs: Int
        get() = sp.getInt("dlnaDelayMs", 0)
        set(v) = sp.edit().putInt("dlnaDelayMs", v.coerceIn(-3000, 3000)).apply()

    var restoreVolume: Boolean
        get() = sp.getBoolean("restoreVolume", true)
        set(v) = sp.edit().putBoolean("restoreVolume", v).apply()

    var effects: Boolean
        get() = sp.getBoolean("effects", true)
        set(v) = sp.edit().putBoolean("effects", v).apply()

    var dedupMode: DedupMode
        get() = DedupMode.valueOf(sp.getString("dedupMode", DedupMode.ANY.name)!!)
        set(v) = sp.edit().putString("dedupMode", v.name).apply()

    var testMode: Boolean
        get() = sp.getBoolean("testMode", false)
        set(v) = sp.edit().putBoolean("testMode", v).apply()

    var testSound: TestSound
        get() = TestSound.valueOf(sp.getString("testSound", TestSound.COUNT.name)!!)
        set(v) = sp.edit().putString("testSound", v.name).apply()

    var muteBeep: Boolean
        get() = sp.getBoolean("muteBeep", true)
        set(v) = sp.edit().putBoolean("muteBeep", v).apply()

    /** 마지막으로 음성 파일을 만든 설정. 바뀌면 다시 만든다. */
    var clipsSignature: String
        get() = sp.getString("clipsSig", "")!!
        set(v) = sp.edit().putString("clipsSig", v).apply()

    var style: Style
        get() = try { Style.valueOf(sp.getString("style", Style.BIG.name)!!) } catch (_: Exception) { Style.BIG }
        set(v) = sp.edit().putString("style", v.name).apply()
    var ttsEngine: String
        get() = sp.getString("ttsEngine", "")!!
        set(v) = sp.edit().putString("ttsEngine", v).apply()
    var ttsVoice: String
        get() = sp.getString("ttsVoice", "")!!
        set(v) = sp.edit().putString("ttsVoice", v).apply()

    /** 앱을 열면 음성 인식 자동 켜기 (빅스비 "이놈아저씨 열어줘"로 켜기) */
    var autoListenOnOpen: Boolean
        get() = sp.getBoolean("autoListenOnOpen", true)
        set(v) = sp.edit().putBoolean("autoListenOnOpen", v).apply()

    /** 명령이 없으면 음성 인식 자동 꺼짐 (분) */
    var autoOffMin: Int
        get() = sp.getInt("autoOffMin", 15)
        set(v) = sp.edit().putInt("autoOffMin", v).apply()

    // ----- 예약 -----
    var scheduleOn: Boolean
        get() = sp.getBoolean("scheduleOn", false)
        set(v) = sp.edit().putBoolean("scheduleOn", v).apply()
    var scheduleMinute: Int // 하루 중 분 (21:00 = 1260)
        get() = sp.getInt("scheduleMinute", 21 * 60)
        set(v) = sp.edit().putInt("scheduleMinute", v).apply()
    /** 요일 비트 (월=1, 화=2, ... 일=64) */
    var scheduleDays: Int
        get() = sp.getInt("scheduleDays", 127)
        set(v) = sp.edit().putInt("scheduleDays", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    fun currentSignature(): String = "v${Scenario.version}|$name1|$name2|$effects|${style.name}|$ttsEngine|$ttsVoice"
}
