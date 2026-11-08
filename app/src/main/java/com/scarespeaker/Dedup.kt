package com.scarespeaker

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * 같은 Wi-Fi의 다른 폰(같은 앱)과 신호를 주고받아 중복 재생을 막는다.
 * - PLAY: 누가 재생을 시작함 (재생 길이 동안 막기)
 * - STOP: 정지 → 막기 해제
 * - CLAIM: 동시에 같은 말을 알아들었을 때 한 대만 재생하도록 정하기
 */
object Dedup {
    private const val PORT = 47123
    private var socket: DatagramSocket? = null

    @Volatile private var lockUntil = 0L
    @Volatile private var lockKey = ""
    @Volatile var remoteOrigCast: Double = -1.0; private set
    @Volatile var remoteOrigDlna: Int = -1; private set
    @Volatile private var remoteOrigUntil = 0L

    private data class Claim(val prio: Long, val from: String, val key: String, val at: Long)
    private val claims = CopyOnWriteArrayList<Claim>()

    /** 다른 폰이 정지/새 재생을 했을 때 알림 (이 폰의 음량 되돌리기 취소용) */
    var onRemoteTakeover: (() -> Unit)? = null

    fun start(ctx: Context) {
        if (socket != null) return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.createMulticastLock("scare-dedup").apply { setReferenceCounted(false); acquire() }
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            socket = s
            Thread({ loop(s) }, "dedup").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            AppLog.d("중복 방지 시작 실패: ${e.message}")
        }
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (true) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val j = JSONObject(String(p.data, 0, p.length))
                if (j.optString("from") == Prefs.deviceId) continue
                val now = System.currentTimeMillis()
                when (j.optString("t")) {
                    "PLAY" -> {
                        lockKey = j.optString("key")
                        lockUntil = now + j.optLong("dur")
                        if (j.has("oc")) {
                            remoteOrigCast = j.optDouble("oc", -1.0)
                            remoteOrigDlna = j.optInt("od", -1)
                            remoteOrigUntil = now + j.optLong("dur") + 4000
                        }
                        AppLog.d("다른 폰 재생: ${lockKey} (${j.optLong("dur")}ms)")
                        onRemoteTakeover?.invoke()
                    }
                    "STOP" -> {
                        lockUntil = 0
                        AppLog.d("다른 폰 정지")
                        onRemoteTakeover?.invoke()
                    }
                    "CLAIM" -> claims += Claim(j.optLong("p"), j.optString("from"), j.optString("key"), now)
                }
            } catch (e: Exception) {
                if (socket == null) break
            }
        }
    }

    /** 지금 다른 폰 때문에 막혀 있는지 */
    fun isLocked(key: String): Boolean {
        if (System.currentTimeMillis() >= lockUntil) return false
        return when (Prefs.dedupMode) {
            DedupMode.ANY -> true
            DedupMode.SAME -> key == lockKey
        }
    }

    fun remoteActive(): Boolean = System.currentTimeMillis() < lockUntil

    /** 다른 폰이 알려준 '원래 음량' (아직 유효하면) */
    fun remoteOriginal(): Pair<Double, Int>? =
        if (System.currentTimeMillis() < remoteOrigUntil) remoteOrigCast to remoteOrigDlna else null

    /** 동시에 여러 폰이 반응했을 때 이 폰이 재생해도 되는지 (약 0.4초 걸림) */
    fun claim(key: String): Boolean {
        val mine = Random.nextLong(1, Long.MAX_VALUE)
        val start = System.currentTimeMillis()
        send(JSONObject().put("t", "CLAIM").put("p", mine).put("key", key))
        Thread.sleep(400)
        val rivals = claims.filter { it.at >= start - 1500 && (Prefs.dedupMode == DedupMode.ANY || it.key == key) }
        claims.removeAll { it.at < System.currentTimeMillis() - 5000 }
        val lost = rivals.any { it.prio < mine || (it.prio == mine && it.from < Prefs.deviceId) }
        if (lost) AppLog.d("다른 폰이 먼저 반응해서 이 폰은 건너뜀")
        return !lost
    }

    fun announcePlay(key: String, durMs: Long, origCast: Double?, origDlna: Int?) {
        val j = JSONObject().put("t", "PLAY").put("key", key).put("dur", durMs)
        if (origCast != null || origDlna != null) j.put("oc", origCast ?: -1.0).put("od", origDlna ?: -1)
        send(j)
    }

    fun announceStop() {
        lockUntil = 0
        send(JSONObject().put("t", "STOP"))
    }

    private fun send(j: JSONObject) {
        j.put("from", Prefs.deviceId)
        val data = j.toString().toByteArray()
        Thread {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    for (addr in broadcastAddresses()) {
                        s.send(DatagramPacket(data, data.size, addr, PORT))
                    }
                }
            } catch (e: Exception) {
                AppLog.d("신호 보내기 실패: ${e.message}")
            }
        }.start()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { list += it }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }
}
