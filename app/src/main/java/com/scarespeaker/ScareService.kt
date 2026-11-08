package com.scarespeaker

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * 음성 인식과 재생을 맡는 포그라운드 서비스.
 * 켜져 있는 동안 알림창/잠금화면에 상태와 정지 버튼이 보인다.
 */
class ScareService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var handledThisRound = false
    @Volatile private var ignoreUntil = 0L
    private var lastTrigger = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mutedStreams = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scare:svc").apply { setReferenceCounted(false) }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "scare:wifi").apply { setReferenceCounted(false) }
        SoundServer.ensure(Scenario.clipsDir(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN_ON -> { listening = true; goForeground(); startListening() }
            ACTION_LISTEN_OFF -> { stopListening(); goForeground(); stopIfIdle() }
            ACTION_STOP -> {
                goForeground()
                worker.execute {
                    Player.stop(restore = true)
                    Dedup.announceStop()
                    ignoreUntil = 0
                    notifyChanged()
                    main.post { stopIfIdle() }
                }
            }
            ACTION_PLAY -> {
                goForeground()
                val cmd = Cmd.valueOf(intent.getStringExtra(EXTRA_CMD) ?: Cmd.COME.name)
                val opt = NameOpt.valueOf(intent.getStringExtra(EXTRA_OPT) ?: NameOpt.NONE.name)
                worker.execute { runCommand(cmd, opt, fromVoice = false) }
            }
            else -> goForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopListening()
        running = null
        wakeLock?.release()
        wifiLock?.release()
        notifyChanged()
        super.onDestroy()
    }

    // ---------------- 명령 실행 ----------------

    private fun runCommand(cmd: Cmd, opt: NameOpt, fromVoice: Boolean) {
        val role = Prefs.role
        val key = Scenario.clipKey(cmd, role, opt)
        val file = Scenario.clipFile(this, key)
        if (!file.exists()) {
            toast("음성 파일이 아직 없어요. 앱을 열어 음성 만들기를 해 주세요")
            return
        }
        val title = if (cmd == Cmd.TEST) "테스트: ${Prefs.testSound.label}"
        else cmd.label.replace("○○", Scenario.nameLabel(opt))

        if (!cmd.stopsCurrent) {
            if (Dedup.isLocked(key)) {
                AppLog.d("다른 폰에서 재생 중이라 건너뜀: $title")
                vibrate(longArrayOf(0, 60, 80, 60))
                toast("다른 폰에서 재생 중이에요")
                return
            }
            if (!Dedup.claim(key)) return
        }

        acquireLocks()
        if (cmd.stopsCurrent) {
            if (Player.playing || Dedup.remoteActive()) {
                // 1초 동안 소리를 줄이며 멈추고, 1초 쉰 뒤 대사
                Player.fadeOutAndStop()
                Dedup.announceStop()
                Thread.sleep(1000)
            }
        } else if (Player.playing) {
            Player.stop(restore = false)
        }

        if (cmd == Cmd.END && listening) {
            // 상황 종료: 음성 인식 자동으로 끄기
            main.post { stopListening(); notifyChanged() }
        }

        currentTitle = title
        notifyChanged()
        val durMs = (Wav.durationSec(file) * 1000).toLong()
        ignoreUntil = System.currentTimeMillis() + durMs + 4000
        val ok = Player.start(this, file, title, key) {
            currentTitle = ""
            ignoreUntil = System.currentTimeMillis() + 1500
            notifyChanged()
            main.post { stopIfIdle() }
        }
        if (!ok) {
            currentTitle = ""
            ignoreUntil = 0
            toast("재생하지 못했어요. 설정 > 진단 기록을 확인해 주세요")
            notifyChanged()
            main.post { stopIfIdle() }
        }
    }

    // ---------------- 음성 인식 ----------------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("마이크 권한이 필요해요"); listening = false; notifyChanged(); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("이 폰에서 음성 인식을 쓸 수 없어요"); listening = false; notifyChanged(); return
        }
        if (Prefs.muteBeep && !mutedStreams) {
            val am = getSystemService(AudioManager::class.java)
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                mutedStreams = true
            } catch (_: Exception) {}
        }
        acquireLocks()
        AppLog.d("음성 인식 켜짐")
        notifyChanged()
        listenRound()
    }

    private fun listenRound() {
        if (!listening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        }
        handledThisRound = false
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { recognizer?.startListening(i) } catch (e: Exception) {
            AppLog.d("인식 시작 오류: ${e.message}")
            restart(1000, recreate = true)
        }
    }

    private fun restart(delay: Long, recreate: Boolean = false) {
        main.removeCallbacksAndMessages(RESTART_TOKEN)
        if (recreate) { try { recognizer?.destroy() } catch (_: Exception) {}; recognizer = null }
        main.postAtTime({ listenRound() }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
    }

    private fun stopListening() {
        listening = false
        main.removeCallbacksAndMessages(RESTART_TOKEN)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (mutedStreams) {
            val am = getSystemService(AudioManager::class.java)
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
            mutedStreams = false
        }
        AppLog.d("음성 인식 꺼짐")
    }

    private fun handleTexts(texts: List<String>?, final: Boolean) {
        if (texts.isNullOrEmpty() || handledThisRound) return
        if (final) AppLog.d("들림: ${texts.first()}")
        val now = System.currentTimeMillis()
        val testMode = Prefs.testMode
        for (t in texts) {
            val m = Scenario.match(t, testMode) ?: continue
            // 재생 중에는 스피커 소리를 다시 알아듣지 않도록 무시 (멈춤 명령은 예외)
            if (now < ignoreUntil && !m.cmd.stopsCurrent) return
            if (now - lastTrigger < 2500) return
            handledThisRound = true
            lastTrigger = now
            AppLog.d("인식: \"$t\" → ${m.cmd} ${m.opt}")
            vibrate(longArrayOf(0, 90))
            worker.execute { runCommand(m.cmd, m.opt, fromVoice = true) }
            return
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            handleTexts(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), false)
        }
        override fun onResults(results: Bundle?) {
            handleTexts(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), true)
            restart(150)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(800, recreate = true)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    toast("마이크 권한이 필요해요"); stopListening(); notifyChanged(); stopIfIdle()
                }
                else -> { AppLog.d("인식 오류 $error"); restart(1500, recreate = true) }
            }
        }
    }

    // ---------------- 알림 / 상태 ----------------

    private fun goForeground() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (listening && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTI_ID, buildNotification(), if (Build.VERSION.SDK_INT >= 29) type else 0)
        } catch (e: Exception) {
            AppLog.d("포그라운드 시작 실패: ${e.message}")
            if (listening) { listening = false; toast("앱을 연 상태에서 음성 인식을 켜 주세요") }
            try {
                ServiceCompat.startForeground(this, NOTI_ID, buildNotification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val lines = mutableListOf<String>()
        if (listening) lines += "🎙 음성 인식 중" + if (Prefs.testMode) " · 테스트 모드" else ""
        if (currentTitle.isNotEmpty()) lines += "▶ 재생 중: $currentTitle"
        if (lines.isEmpty()) lines += "대기 중"
        val b = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (listening) "이놈아저씨 · 듣는 중" else "이놈아저씨")
            .setContentText(lines.joinToString("  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        b.addAction(0, "⏹ 정지", pending(ACTION_STOP, 1))
        if (listening) b.addAction(0, "🎙 인식 끄기", pending(ACTION_LISTEN_OFF, 2))
        return b.build()
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, ScareService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyChanged() {
        main.post {
            if (running != null) {
                try {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(NOTI_ID, buildNotification())
                } catch (_: Exception) {}
            }
            stateListener?.invoke()
        }
    }

    private fun stopIfIdle() {
        if (!listening && !Player.playing && currentTitle.isEmpty()) {
            wakeLock?.release(); wifiLock?.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        try { wakeLock?.acquire(3 * 60 * 60 * 1000L) } catch (_: Exception) {}
        try { wifiLock?.acquire() } catch (_: Exception) {}
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        const val ACTION_PLAY = "play"
        const val ACTION_STOP = "stop"
        const val ACTION_LISTEN_ON = "listen_on"
        const val ACTION_LISTEN_OFF = "listen_off"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_OPT = "opt"
        private const val NOTI_ID = 7
        private val RESTART_TOKEN = Any()

        @Volatile var running: ScareService? = null; private set
        @Volatile var listening = false; private set
        @Volatile var currentTitle = ""; private set
        /** 화면 갱신용 */
        var stateListener: (() -> Unit)? = null

        fun send(ctx: Context, action: String, cmd: Cmd? = null, opt: NameOpt? = null) {
            val i = Intent(ctx, ScareService::class.java).setAction(action)
            cmd?.let { i.putExtra(EXTRA_CMD, it.name) }
            opt?.let { i.putExtra(EXTRA_OPT, it.name) }
            ctx.startForegroundService(i)
        }
    }
}
