package com.scarespeaker

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** 폰 안의 작은 HTTP 서버. 스피커/TV가 여기서 음성 파일을 가져간다. */
class SoundServer(private val dir: File) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        AppLog.d("HTTP ${session.method} $uri from ${session.remoteIpAddress} range=${session.headers["range"]}")
        if (!uri.startsWith("/c/")) return notFound()
        val file = File(dir, File(uri.removePrefix("/c/")).name)
        if (!file.isFile) return notFound()

        val len = file.length()
        val range = session.headers["range"]
        val res: Response
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split(",")[0].trim()
            val parts = spec.split("-")
            var start = parts[0].toLongOrNull() ?: 0L
            var end = parts.getOrNull(1)?.toLongOrNull() ?: (len - 1)
            if (parts[0].isEmpty() && end > 0) { start = len - end; end = len - 1 }
            if (end >= len) end = len - 1
            if (start >= len) {
                res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                res.addHeader("Content-Range", "bytes */$len")
                return withHeaders(res)
            }
            val count = end - start + 1
            val input = FileInputStream(file)
            input.skip(start)
            res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, MIME, input, count)
            res.addHeader("Content-Range", "bytes $start-$end/$len")
        } else {
            res = newFixedLengthResponse(Response.Status.OK, MIME, FileInputStream(file), len)
        }
        return withHeaders(res)
    }

    private fun notFound() = withHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found"))

    private fun withHeaders(r: Response): Response {
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader("contentFeatures.dlna.org", DLNA_FEATURES)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    companion object {
        const val PORT = 8089
        const val MIME = "audio/wav"
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        private var server: SoundServer? = null

        @Synchronized
        fun ensure(dir: File) {
            val s = server
            if (s != null && s.isAlive) return
            try {
                server = SoundServer(dir).also { it.start(SOCKET_READ_TIMEOUT, false) }
                AppLog.d("HTTP 서버 시작 :$PORT")
            } catch (e: Exception) {
                AppLog.d("HTTP 서버 시작 실패: ${e.message}")
            }
        }

        @Synchronized
        fun stop() {
            server?.stop()
            server = null
        }

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }

        fun urlFor(f: File): String? {
            val ip = localIp() ?: return null
            return "http://$ip:$PORT/c/${f.name}?v=${f.lastModified()}"
        }
    }
}
