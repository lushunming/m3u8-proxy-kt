package cn.com.lushunming.server

import cn.com.lushunming.util.HttpClientUtil
import cn.com.lushunming.util.Util
import com.google.gson.Gson
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.Charset

class ProxyServer {

    val logger = LoggerFactory.getLogger(ProxyServer::class.java)
    val partSize = 1024 * 1024 // 1MB
    val THREAD_NUM = 10



    fun buildProxyUrl(url: String, headers: Map<String, String>,port:Int): String {
        return "http://127.0.0.1:$port/proxy?url=${
            Util.base64Encode(url.toByteArray(Charset.defaultCharset()))
        }&headers=${
            Util.base64Encode(
                Gson().toJson(headers).toByteArray(
                    Charset.defaultCharset()
                )
            )
        }"
    }


    suspend fun proxyAsync(
        url: String, headers: Map<String, String>, call: ApplicationCall
    ) {
        val channels = List(THREAD_NUM) { Channel<ByteArray>() }
        try {
            logger.info("--proxyMultiThread: THREAD_NUM: $THREAD_NUM")


            var rangeHeader = call.request.headers[HttpHeaders.Range]
            //没有range头
            if (rangeHeader.isNullOrEmpty()) {
                // 处理初始请求
                rangeHeader = "bytes=0-"
            }
            headers.toMutableMap().apply {
                put(HttpHeaders.Range, rangeHeader)
            }

            // 解析范围请求
            val (startPoint, endPoint) = parseRangePoint(
                rangeHeader
            )
            logger.info("startPoint: $startPoint; endPoint: $endPoint")
            val contentLength = getContentLength(url, headers)
            logger.info("contentLength: $contentLength")
            val finalEndPoint = if (endPoint == -1L) contentLength - 1 else endPoint

            call.response.headers.apply {
                append(HttpHeaders.Connection, "keep-alive")
                append(HttpHeaders.ContentLength, (finalEndPoint - startPoint + 1).toString())
                append(HttpHeaders.ContentRange, "bytes $startPoint-$finalEndPoint/$contentLength")
            }
            call.response.status(HttpStatusCode.PartialContent)

            // 使用流式响应
            call.respondBytesWriter() {
                var currentStart = startPoint


                // 启动生产者协程下载数据

                val producerJob = mutableListOf<Job>()

                while (currentStart <= finalEndPoint) {
                    producerJob.clear()
                    // 创建通道用于接收数据块

                    for (i in 0 until THREAD_NUM) {

                        if (currentStart > finalEndPoint) break
                        val chunkStart = currentStart
                        val chunkEnd = minOf(currentStart + partSize - 1, finalEndPoint)
                        producerJob += CoroutineScope(Dispatchers.IO).launch {
                            // 异步下载数据块
                            val data = getVideoStream(chunkStart, chunkEnd, url, headers)
                            channels[i].send(data)

                        }
                        currentStart = chunkEnd + 1
                    }
                    for ((index, job) in producerJob.withIndex()) {

                        val data = channels[index].receive()
                        logger.info("Received chunk: ${data.size} bytes")
                        writeFully(ByteBuffer.wrap(data))
                    }
                }


            }
        } catch (e: Exception) {
            logger.info("error: ${e.message}")
            call.respondText("error: ${e.message}", ContentType.Text.Plain)
        } finally {
            channels.forEach { it.close() }
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


    private suspend fun downloadRange(
        url: String, headerNew: Map<String, String>
    ): HttpResponse = HttpClientUtil.get(url, headerNew)

}