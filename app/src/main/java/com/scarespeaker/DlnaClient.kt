package com.scarespeaker

import java.net.HttpURLConnection
import java.net.URL

/** 스탠바이미(LG webOS TV)에 DLNA(UPnP)로 소리를 보내는 클라이언트 */
object DlnaClient {

    data class Desc(
        val friendlyName: String,
        val avTransportUrl: String?,
        val renderingUrl: String?,
    )

    private val cache = HashMap<String, Desc>()

    fun forget(location: String) { cache.remove(location) }

    fun describe(location: String): Desc? {
        cache[location]?.let { return it }
        return try {
            val xml = http("GET", location, null, null, 3000)
            val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim() ?: "TV"
            var av: String? = null
            var rc: String? = null
            Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                val s = m.groupValues[1]
                val type = Regex("<serviceType>(.*?)</serviceType>").find(s)?.groupValues?.get(1) ?: ""
                val ctl = Regex("<controlURL>(.*?)</controlURL>").find(s)?.groupValues?.get(1)?.trim() ?: return@forEach
                val abs = URL(URL(location), ctl).toString()
                if (type.contains("AVTransport")) av = abs
                if (type.contains("RenderingControl")) rc = abs
            }
            Desc(unescape(name), av, rc).also { cache[location] = it }
        } catch (e: Exception) {
            AppLog.d("DLNA 설명 읽기 실패 $location: ${e.message}")
            null
        }
    }

    fun play(d: Desc, url: String, title: String): Boolean {
        val av = d.avTransportUrl ?: return false
        try { soap(av, AVT, "Stop", "<InstanceID>0</InstanceID>") } catch (_: Exception) {}
        Thread.sleep(600)
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:${SoundServer.MIME}:${SoundServer.DLNA_FEATURES}">${esc(url)}</res></item></DIDL-Lite>"""
        retry701(av) {
            soap(av, AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>", 25000)
        }
        retry701(av) { soap(av, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>", 15000) }
        return true
    }

    /** TV가 아직 이전 재생을 정리 중이면(701) 멈추고 잠시 뒤 다시 시도 */
    private fun retry701(av: String, block: () -> Unit) {
        for (attempt in 1..3) {
            try { block(); return } catch (e: Upnp) {
                if (e.code != "701" || attempt == 3) throw e
                AppLog.d("TV가 준비 중(701), 다시 시도 $attempt")
                try { soap(av, AVT, "Stop", "<InstanceID>0</InstanceID>") } catch (_: Exception) {}
                Thread.sleep(1500)
            }
        }
    }

    class Upnp(val code: String?, msg: String) : Exception(msg)

    fun stop(d: Desc) {
        val av = d.avTransportUrl ?: return
        soap(av, AVT, "Stop", "<InstanceID>0</InstanceID>")
    }

    fun getVolume(d: Desc): Int? {
        val rc = d.renderingUrl ?: return null
        val r = soap(rc, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        return Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(r)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun setVolume(d: Desc, v: Int) {
        val rc = d.renderingUrl ?: return
        soap(rc, RC, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>")
    }

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

    private fun soap(url: String, service: String, action: String, args: String, timeout: Int = 6000): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        return http("POST", url, body, "\"$service#$action\"", timeout)
    }

    private fun http(method: String, url: String, body: String?, soapAction: String?, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            soapAction?.let { c.setRequestProperty("SOAPAction", it) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code >= 400) {
            val ec = Regex("<errorCode>(\\d+)</errorCode>").find(text)?.groupValues?.get(1)
            val ed = Regex("<errorDescription>(.*?)</errorDescription>").find(text)?.groupValues?.get(1)
            AppLog.d("DLNA $code ${soapAction ?: url}: 오류코드 $ec $ed")
            throw Upnp(ec, "DLNA 오류 $code/$ec")
        }
        return text
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
