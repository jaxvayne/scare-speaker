package com.scarespeaker

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** 음성 인식을 켜고 바로 닫히는 투명 화면 (위젯/바로가기/예약 알림용) */
class ListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ScareService.send(this, ScareService.ACTION_LISTEN_ON)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        getSystemService(NotificationManager::class.java).cancel(Schedule.NOTI_ID)
        finish()
    }
}

/** 홈 화면 위젯: 음성 인식 켜기/끄기 + 정지 */
class ScareWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        fun refresh(ctx: Context) {
            val c = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, ScareWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(c.packageName, R.layout.widget)
            val on = ScareService.listening
            v.setTextViewText(R.id.w_listen, if (on) "듣는 중 (끄기)" else "음성 인식 켜기")
            v.setTextViewCompoundDrawables(R.id.w_listen, if (on) R.drawable.ic_mic_on else R.drawable.ic_mic_off, 0, 0, 0)
            v.setInt(R.id.w_listen, "setBackgroundResource", if (on) R.drawable.widget_btn_on else R.drawable.widget_btn)
            val listenPi = if (on) PendingIntent.getForegroundService(c, 11,
                Intent(c, ScareService::class.java).setAction(ScareService.ACTION_LISTEN_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(c, 12, Intent(c, ListenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stopPi = PendingIntent.getForegroundService(c, 13,
                Intent(c, ScareService::class.java).setAction(ScareService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_listen, listenPi)
            v.setOnClickPendingIntent(R.id.w_stop, stopPi)
            mgr.updateAppWidget(ids, v)
        }
    }
}

/**
 * 예약: 정한 시각에 알림을 띄우고, 알림의 [켜기]를 누르면 음성 인식이 켜진다.
 * (안드로이드 정책상 앱이 화면 밖에서 마이크를 스스로 켤 수 없어서 한 번 탭이 필요)
 */
object Schedule {
    const val NOTI_ID = 21
    private const val CHANNEL = "scare_schedule"

    fun apply(ctx: Context) {
        val c = ctx.applicationContext
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 31, Intent(c, ScheduleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!Prefs.scheduleOn || Prefs.scheduleDays == 0) return
        val next = nextTime() ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        AppLog.d("예약: 다음 알림 ${java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(next))}")
    }

    /** 요일 비트: 월=1 … 일=64 */
    private fun dayBit(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 4; Calendar.THURSDAY -> 8
        Calendar.FRIDAY -> 16; Calendar.SATURDAY -> 32; else -> 64
    }

    private fun nextTime(): Long? {
        val now = Calendar.getInstance()
        for (d in 0..7) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, Prefs.scheduleMinute / 60)
                set(Calendar.MINUTE, Prefs.scheduleMinute % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.after(now) && (Prefs.scheduleDays and dayBit(c)) != 0) return c.timeInMillis
        }
        return null
    }

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "이놈아저씨 예약", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 32, Intent(ctx, ListenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("이놈아저씨 · 재울 시간이에요")
            .setContentText("탭하면 음성 인식이 켜져요")
            .setContentIntent(pi)
            .addAction(0, "켜기", pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(NOTI_ID, n) } catch (_: SecurityException) {}
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Schedule.apply(ctx)
            return
        }
        if (!ScareService.listening) Schedule.notify(ctx)
        Schedule.apply(ctx) // 다음 예약
    }
}

/** 업데이트 내역 (assets/changelog.json) */
object Changelog {
    data class Entry(val version: Int, val name: String, val date: String, val items: List<String>)

    fun all(ctx: Context): List<Entry> = try {
        val a = org.json.JSONArray(ctx.assets.open("changelog.json").bufferedReader().readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val it = o.getJSONArray("items")
            Entry(o.getInt("version"), o.getString("name"), o.optString("date"), List(it.length()) { k -> it.getString(k) })
        }
    } catch (_: Exception) { emptyList() }
}

/** 새 버전 확인 (GitHub Release) */
object Updater {
    private const val API = "https://api.github.com/repos/jaxvayne/scare-speaker/releases/latest"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val pageUrl: String)

    /** 새 버전이 있으면 돌려줌 (6시간에 한 번만 확인) */
    fun check(currentCode: Long, force: Boolean = false): Release? {
        if (!force && System.currentTimeMillis() - Prefs.lastUpdateCheck < 6 * 3600_000L) return null
        return try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (BuildConfig.UPDATE_TOKEN.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
            if (c.responseCode != 200) { AppLog.d("업데이트 확인 실패 ${c.responseCode}"); return null }
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            val tag = j.optString("tag_name") // v1.0.N
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= currentCode) return null
            val assets = j.optJSONArray("assets")
            var apk = j.optString("html_url")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            Release(code, tag.removePrefix("v"), apk, j.optString("html_url"))
        } catch (e: Exception) {
            AppLog.d("업데이트 확인 오류: ${e.message}")
            null
        }
    }
}
