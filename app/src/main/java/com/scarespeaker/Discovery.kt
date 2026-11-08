package com.scarespeaker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class CastDevice(val name: String, val host: String, val port: Int)
data class DlnaDevice(val name: String, val location: String)

/** 같은 Wi-Fi의 스피커(캐스트)와 TV(DLNA) 찾기 */
object Discovery {

    fun findCast(ctx: Context, waitMs: Long = 6000): List<CastDevice> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = CopyOnWriteArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { AppLog.d("NSD 시작 실패 $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { found += s }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        val lock = multicastLock(ctx)
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(waitMs)
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        val result = mutableListOf<CastDevice>()
        AppLog.d("캐스트 검색 결과 ${found.size}개: ${found.joinToString { it.serviceName }}")
        for (s in found.distinctBy { it.serviceName }) for (attempt in 1..2) {
            if (result.any { it.name == s.serviceName }) break
            val latch = CountDownLatch(1)
            var ok = false
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    AppLog.d("주소 확인 실패 ${s.serviceName} (오류 $e)")
                    latch.countDown()
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val fn = i.attributes["fn"]?.toString(Charsets.UTF_8) ?: i.serviceName
                    @Suppress("DEPRECATION")
                    val host = i.host?.hostAddress
                    if (host != null) { result += CastDevice(fn, host, i.port); ok = true }
                    latch.countDown()
                }
            })
            latch.await(4, TimeUnit.SECONDS)
            if (ok) break
            Thread.sleep(300)
        }
        lock.release()
        AppLog.d("캐스트 기기: ${result.joinToString { "${it.name}@${it.host}" }}")
        return result.distinctBy { it.host }
    }

    fun findDlna(ctx: Context, waitMs: Int = 3500): List<DlnaDevice> {
        val lock = multicastLock(ctx)
        val locations = LinkedHashSet<String>()
        try {
            MulticastSocket(null as java.net.SocketAddress?).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(0))
                sock.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val b = msg.toByteArray()
                    repeat(2) { sock.send(DatagramPacket(b, b.size, group, 1900)) }
                }
                val end = System.currentTimeMillis() + waitMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        text.lines().firstOrNull { it.lowercase().startsWith("location:") }
                            ?.substringAfter(":")?.trim()?.let { locations += it }
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (e: Exception) {
            AppLog.d("SSDP 오류: ${e.message}")
        } finally {
            lock.release()
        }
        val out = mutableListOf<DlnaDevice>()
        for (loc in locations) {
            val d = DlnaClient.describe(loc) ?: continue
            if (d.avTransportUrl != null) out += DlnaDevice(d.friendlyName, loc)
        }
        AppLog.d("DLNA 기기: ${out.joinToString { "${it.name}@${it.location}" }}")
        return out.distinctBy { it.location }
    }

    private fun multicastLock(ctx: Context): WifiManager.MulticastLock {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.createMulticastLock("scare-discovery").apply { setReferenceCounted(false); acquire() }
    }
}
