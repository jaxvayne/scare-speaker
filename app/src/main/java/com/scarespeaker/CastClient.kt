package com.scarespeaker

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 구글 캐스트 프로토콜을 직접 구현한 작은 클라이언트.
 * 앱에서 기기를 매번 고르지 않아도 S95QR 같은 캐스트 스피커에 바로 재생할 수 있다.
 */
class CastClient private constructor(private val socket: SSLSocket) {

    private val out = DataOutputStream(socket.getOutputStream())
    private val input = DataInputStream(socket.getInputStream())
    private val reqId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<JSONObject?>>>()
    @Volatile private var closed = false
    @Volatile var lastReceiverStatus: JSONObject? = null
        private set
    private var transportId: String? = null
    var sessionId: String? = null
        private set

    data class Volume(val level: Double, val step: Double)

    init {
        Thread({ readLoop() }, "cast-read").apply { isDaemon = true }.start()
        Thread({ pingLoop() }, "cast-ping").apply { isDaemon = true }.start()
        send(NS_CONN, RECEIVER, JSONObject().put("type", "CONNECT"))
    }

    // ---------- 공개 동작 ----------

    fun getVolume(): Volume? {
        val r = request(NS_RECEIVER, RECEIVER, JSONObject().put("type", "GET_STATUS")) ?: return null
        val v = r.optJSONObject("status")?.optJSONObject("volume") ?: return null
        return Volume(v.optDouble("level", 0.0), v.optDouble("stepInterval", 0.0))
    }

    fun setVolume(level: Double) {
        request(NS_RECEIVER, RECEIVER, JSONObject().put("type", "SET_VOLUME")
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))))
    }

    /** 기본 미디어 수신기를 띄우고 음성 파일을 재생 */
    fun load(url: String, title: String): Boolean {
        val r = request(NS_RECEIVER, RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP_ID), 20)
            ?: return false
        val apps = r.optJSONObject("status")?.optJSONArray("applications") ?: return false
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            if (a.optString("appId") == APP_ID) {
                transportId = a.optString("transportId")
                sessionId = a.optString("sessionId")
            }
        }
        val tid = transportId ?: return false
        send(NS_CONN, tid, JSONObject().put("type", "CONNECT"))
        val media = JSONObject()
            .put("contentId", url)
            .put("streamType", "BUFFERED")
            .put("contentType", SoundServer.MIME)
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        val lr = request(NS_MEDIA, tid, JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true), 15)
        AppLog.d("캐스트 LOAD 응답: ${lr?.optString("type")}")
        return lr != null && lr.optString("type") != "LOAD_FAILED" && lr.optString("type") != "LOAD_CANCELLED"
    }

    /** 이 기기에서 재생 중인 기본 미디어 수신기를 멈춤 (다른 폰이 틀었어도 멈춘다) */
    fun stopApp() {
        val sid = sessionId ?: run {
            val r = request(NS_RECEIVER, RECEIVER, JSONObject().put("type", "GET_STATUS"))
            val apps = r?.optJSONObject("status")?.optJSONArray("applications")
            var found: String? = null
            if (apps != null) for (i in 0 until apps.length()) {
                val a = apps.getJSONObject(i)
                if (a.optString("appId") == APP_ID) found = a.optString("sessionId")
            }
            found
        } ?: return
        request(NS_RECEIVER, RECEIVER, JSONObject().put("type", "STOP").put("sessionId", sid))
    }

    fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
        pending.values.forEach { it.first.countDown() }
    }

    // ---------- 내부 ----------

    private fun request(ns: String, dest: String, payload: JSONObject, timeoutSec: Long = 8): JSONObject? {
        val id = reqId.incrementAndGet()
        payload.put("requestId", id)
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<JSONObject>(1)
        pending[id] = latch to holder
        send(ns, dest, payload)
        latch.await(timeoutSec, TimeUnit.SECONDS)
        pending.remove(id)
        return holder[0]
    }

    @Synchronized
    private fun send(ns: String, dest: String, payload: JSONObject) {
        if (closed) return
        val msg = encode(SENDER, dest, ns, payload.toString())
        out.writeInt(msg.size)
        out.write(msg)
        out.flush()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > 1_000_000) break
                val buf = ByteArray(len)
                input.readFully(buf)
                val m = decode(buf)
                val payload = m["payload"] ?: continue
                val ns = m["namespace"] ?: ""
                val json = try { JSONObject(payload) } catch (_: Exception) { continue }
                if (ns == NS_HEARTBEAT && json.optString("type") == "PING") {
                    send(NS_HEARTBEAT, m["source"] ?: RECEIVER, JSONObject().put("type", "PONG"))
                    continue
                }
                if (json.optString("type") == "RECEIVER_STATUS") lastReceiverStatus = json
                val rid = json.optInt("requestId", 0)
                if (rid != 0) pending[rid]?.let { it.second[0] = json; it.first.countDown() }
            }
        } catch (_: Exception) {
        } finally {
            closed = true
            pending.values.forEach { it.first.countDown() }
        }
    }

    private fun pingLoop() {
        while (!closed) {
            try {
                Thread.sleep(5000)
                send(NS_HEARTBEAT, RECEIVER, JSONObject().put("type", "PING"))
            } catch (_: Exception) { break }
        }
    }

    companion object {
        const val APP_ID = "CC1AD845" // 기본 미디어 수신기
        private const val SENDER = "sender-0"
        private const val RECEIVER = "receiver-0"
        private const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        fun connect(host: String, port: Int, timeoutMs: Int = 4000): CastClient {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, SecureRandom())
            val raw = java.net.Socket()
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            s.soTimeout = 0
            s.startHandshake()
            return CastClient(s)
        }

        // CastMessage protobuf 직접 인코딩
        private fun encode(src: String, dst: String, ns: String, payload: String): ByteArray {
            val b = ByteArrayOutputStream()
            fun varint(v0: Long) {
                var v = v0
                while (true) {
                    if (v and 0x7F.inv().toLong() == 0L) { b.write(v.toInt()); return }
                    b.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
                }
            }
            fun str(field: Int, s: String) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                varint(((field shl 3) or 2).toLong()); varint(bytes.size.toLong()); b.write(bytes)
            }
            varint((1 shl 3).toLong()); varint(0) // protocol_version
            str(2, src); str(3, dst); str(4, ns)
            varint((5 shl 3).toLong()); varint(0) // payload_type STRING
            str(6, payload)
            return b.toByteArray()
        }

        private fun decode(buf: ByteArray): Map<String, String> {
            val r = HashMap<String, String>()
            var i = 0
            fun varint(): Long {
                var shift = 0; var res = 0L
                while (i < buf.size) {
                    val c = buf[i++].toInt() and 0xff
                    res = res or ((c and 0x7f).toLong() shl shift)
                    if (c and 0x80 == 0) break
                    shift += 7
                }
                return res
            }
            while (i < buf.size) {
                val tag = varint().toInt()
                val field = tag ushr 3
                when (tag and 7) {
                    0 -> varint()
                    2 -> {
                        val len = varint().toInt()
                        val s = String(buf, i, len, Charsets.UTF_8); i += len
                        when (field) {
                            2 -> r["source"] = s
                            3 -> r["dest"] = s
                            4 -> r["namespace"] = s
                            6 -> r["payload"] = s
                        }
                    }
                    else -> return r
                }
            }
            return r
        }
    }
}
