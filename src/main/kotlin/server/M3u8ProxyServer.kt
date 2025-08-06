package cn.com.lushunming.server

import cn.com.lushunming.util.Constant
import cn.com.lushunming.util.HttpClientUtil
import cn.com.lushunming.util.Util
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.OctetStream
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import java.io.File

class M3u8ProxyServer {

    val logger = LoggerFactory.getLogger(M3u8ProxyServer::class.java)
    val partSize = 1024 * 1024 // 1MB
    val THREAD_NUM = 10


    suspend fun proxyAsyncM3u8(
        url: String, headers: Map<String, String>, call: ApplicationCall
    ) {
        //所在目录
        val dir = Constant.downloadPath + File.separator + Util.md5(url)
        try {

            call.response.header(
                HttpHeaders.ContentType, "application/vnd.apple.mpegurl"
            )
            call.respondFile(File(dir),"local.m3u8")
        } catch (e: Exception) {
            logger.info("error: ${e.message}")
            call.respondText("error: ${e.message}", ContentType.Text.Plain)
        } finally {

        }
    }


    // 辅助函数（需要实现）
    private fun parseRangePoint(rangeHeader: String): Pair<Long, Long> {
        // 实现范围解析逻辑
        val regex = """bytes=(\d+)-(\d*)""".toRegex()
        val match = regex.find(rangeHeader) ?: return 0L to -1L
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: -1L
        return start to end
    }

    private suspend fun getContentLength(url: String, headers: Map<String, String>): Long {
        // 实现获取内容长度逻辑
        val res = HttpClientUtil.get(url, headers)

        return res.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
    }

    private suspend fun getVideoStream(
        start: Long, end: Long, url: String, headers: Map<String, String>
    ): ByteArray {
        val header = headers.toMutableMap()
        // 实现分段下载逻辑
        logger.info("getVideoStream: $start-$end; ")
        header[HttpHeaders.Range] = "bytes=$start-$end"
        val res = HttpClientUtil.get(url, header)
        val body = res.bodyAsBytes()
        return body
    }


}