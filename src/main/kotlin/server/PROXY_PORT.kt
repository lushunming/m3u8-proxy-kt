package server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// 配置
const val PROXY_PORT = 8082
const val CACHE_DIR = "./video_cache"
const val MAX_DOWNLOAD_THREADS = 5
const val MAX_SEQUENCE_GAP = 2 // 序列号差距超过此值判定为跳转

// TS片段信息
data class TsSegment(
    val url: String, val duration: Double, val sequenceNumber: Int, val originalLine: String // 原始M3U8中的行信息
)

// 当前播放状态
data class PlaybackState(
    val currentM3u8Url: String, val lastSequenceNumber: Int, val allSegments: List<TsSegment>, val lastAccessTime: Long
)

// 状态管理器 - 跟踪TS请求连贯性
object PlaybackStateManager {
    private val stateMap = ConcurrentHashMap<String, PlaybackState>() // key: client identifier
    private val clientCounter = AtomicInteger(0)

    // 生成客户端标识
    fun generateClientId(): String {
        return "client_${clientCounter.incrementAndGet()}"
    }

    // 更新播放状态
    fun updateState(clientId: String, state: PlaybackState) {
        stateMap[clientId] = state.copy(lastAccessTime = System.currentTimeMillis())
        cleanupExpiredStates()
    }

    // 获取客户端状态
    fun getState(clientId: String): PlaybackState? {
        return stateMap[clientId]?.also {
            // 更新访问时间
            stateMap[clientId] = it.copy(lastAccessTime = System.currentTimeMillis())
        }
    }

    // 清理过期状态（10分钟未活动）
    private fun cleanupExpiredStates() {
        val now = System.currentTimeMillis()
        stateMap.entries.removeIf { (_, state) ->
            now - state.lastAccessTime > 10 * 60 * 1000
        }
    }

    // 移除客户端状态
    fun removeState(clientId: String) {
        stateMap.remove(clientId)
    }
}

// 下载任务管理器
object DownloadManager {
    private val downloadJobs = ConcurrentHashMap<String, Job>() // key: TS URL
    private val isDownloading = ConcurrentHashMap<String, AtomicBoolean>()
    private val client = HttpClient(CIO)

    // 开始下载TS文件
    fun startDownload(tsUrl: String, targetFile: File, onComplete: () -> Unit = {}) {
        if (isDownloading.containsKey(tsUrl) && isDownloading[tsUrl]?.get() == true) {
            return // 已经在下载中
        }

        isDownloading[tsUrl] = AtomicBoolean(true)
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.get(tsUrl)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(response.bodyAsBytes())
                onComplete()
            } catch (e: Exception) {
                println("下载失败: $tsUrl, 错误: ${e.message}")
                targetFile.delete() // 下载失败删除文件
            } finally {
                isDownloading[tsUrl]?.set(false)
                downloadJobs.remove(tsUrl)
            }
        }
        downloadJobs[tsUrl] = job
    }

    // 取消指定序列号范围外的下载任务
    fun cancelDownloadsOutsideRange(
        segments: List<TsSegment>, startSequence: Int, endSequence: Int
    ) {
        segments.filter { it.sequenceNumber < startSequence || it.sequenceNumber > endSequence }.forEach { segment ->
            downloadJobs[segment.url]?.cancel()
            downloadJobs.remove(segment.url)
            isDownloading.remove(segment.url)
        }
    }

    // 取消所有下载任务
    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        isDownloading.clear()
    }

    // 检查是否正在下载
    fun isDownloading(tsUrl: String): Boolean {
        return isDownloading.containsKey(tsUrl) && isDownloading[tsUrl]?.get() == true
    }
}

// M3U8解析器
object M3U8Parser {
    private val mediaSequencePattern = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)")
    private val targetDurationPattern = Regex("#EXT-X-TARGETDURATION:(\\d+)")
    private val extInfPattern = Regex("#EXTINF:(\\d+\\.\\d+),")

    // 解析M3U8获取TS片段和头部信息
    fun parse(
        m3u8Content: String, baseUrl: String
    ): Pair<List<TsSegment>, Map<String, String>> {
        val lines = m3u8Content.lines()
        val tsSegments = mutableListOf<TsSegment>()
        val headers = mutableMapOf<String, String>()

        // 解析头部信息获取起始序列号
        var startSequence = 0
        lines.forEach { line ->
            when {
                line.matches(mediaSequencePattern) -> {
                    startSequence = mediaSequencePattern.find(line)?.groupValues?.get(1)?.toInt() ?: 0
                    headers["#EXT-X-MEDIA-SEQUENCE"] = line
                }

                line.matches(targetDurationPattern) -> headers["#EXT-X-TARGETDURATION"] = line
                line.startsWith("#EXT-X-VERSION") -> headers["#EXT-X-VERSION"] = line
                line.startsWith("#EXT-X-PLAYLIST-TYPE") -> headers["#EXT-X-PLAYLIST-TYPE"] = line
                line.startsWith("#EXTM3U") -> headers["#EXTM3U"] = line
                line.startsWith("#EXT-X-KEY") -> {

                    val regex = Regex("URI=\"(.*?)\"")  // 匹配 URI="..." 并捕获引号内的内容
                    val regex1 = Regex("uri=\"(.*?)\"")  // 匹配 URI="..." 并捕获引号内的内容
                    var result = regex.find(line)?.groupValues?.get(1)
                    result = result ?: regex1.find(line)?.groupValues?.get(1)
                    result = result ?: ""

                    val key = if (result.startsWith("http")) result else "$baseUrl/$result"
                    headers["#EXT-X-KEY"] = line.replace(result, key)

                }
            }
        }

        // 解析TS片段
        var currentSequence = startSequence
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.matches(extInfPattern)) {
                // 解析EXTINF标签获取时长
                val duration = extInfPattern.find(line)?.groupValues?.get(1)?.toDouble() ?: 0.0

                // 获取TS文件URL
                if (i + 1 < lines.size) {
                    val tsPath = lines[i + 1].trim()
                    val tsUrl = if (tsPath.startsWith("http")) tsPath else "$baseUrl/$tsPath"

                    tsSegments.add(
                        TsSegment(
                            url = tsUrl, duration = duration, sequenceNumber = currentSequence, originalLine = tsPath
                        )
                    )

                    currentSequence++
                    i += 2 // 跳过当前EXTINF和TS行
                    continue
                }
            }

            i++
        }

        return Pair(tsSegments, headers)
    }

    // 重写M3U8内容，替换TS URL为代理URL
    fun rewriteM3u8(
        segments: List<TsSegment>, headers: Map<String, String>, startSequence: Int = -1, clientId: String
    ): String {
        val rewrittenLines = mutableListOf<String>()

        // 添加头部信息
        headers.forEach { (key, value) ->
            if (key == "#EXT-X-MEDIA-SEQUENCE" && startSequence != -1) {
                rewrittenLines.add("#EXT-X-MEDIA-SEQUENCE:$startSequence")
            } else {
                rewrittenLines.add(value)
            }
        }

        // 添加TS片段
        segments.forEach { segment ->
            rewrittenLines.add("#EXTINF:${segment.duration},")
            val encodedUrl = segment.url.encodeURLParameter()
            rewrittenLines.add("/proxy/ts?url=$encodedUrl&seq=${segment.sequenceNumber}&clientId=$clientId")
        }

        // 添加结束标签（如果原文件没有）
        if (!rewrittenLines.any { it.startsWith("#EXT-X-ENDLIST") }) {
            rewrittenLines.add("#EXT-X-ENDLIST")
        }

        return rewrittenLines.joinToString("\n")
    }

    // 根据序列号查找片段
    fun findSegmentBySequence(segments: List<TsSegment>, sequence: Int): TsSegment? {
        return segments.find { it.sequenceNumber == sequence }
    }

    // 获取序列号范围
    fun getSequenceRange(segments: List<TsSegment>): Pair<Int, Int> {
        if (segments.isEmpty()) return Pair(0, 0)
        return Pair(segments.first().sequenceNumber, segments.last().sequenceNumber)
    }
}

// 文件缓存管理器
object CacheManager {
    init {
        File(CACHE_DIR).mkdirs()
    }

    // 获取缓存文件
    fun getCacheFile(url: String): File {
        val fileName = url.hashCode().toString() + "_" + url.substringAfterLast("/")
        return File("$CACHE_DIR/$fileName")
    }

    // 检查文件是否已缓存
    fun isCached(url: String): Boolean {
        val file = getCacheFile(url)
        return file.exists() && file.length() > 0
    }

    // 清理指定序列号范围外的缓存
    fun cleanCacheOutsideRange(segments: List<TsSegment>, startSequence: Int, endSequence: Int) {
        segments.filter { it.sequenceNumber < startSequence || it.sequenceNumber > endSequence }.forEach { segment ->
            val file = getCacheFile(segment.url)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

fun main() {
    embeddedServer(Netty, port = PROXY_PORT, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        // allowHeader(HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true

        //allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowCredentials = true
        anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
    }

    routing {
        // 客户端获取唯一标识
        get("/proxy/client-id") {
            val clientId = PlaybackStateManager.generateClientId()
            call.respondText(clientId)
        }

        // 代理M3U8文件
        get("/proxy/m3u8") {
            val url = call.request.queryParameters["url"]?.decodeURLPart() ?: return@get call.respondText(
                "缺少URL参数", status = HttpStatusCode.BadRequest
            )
            val clientId = call.request.queryParameters["clientId"] ?: return@get call.respondText(
                "缺少clientId参数", status = HttpStatusCode.BadRequest
            )

            try {
                val client = HttpClient(CIO)
                val m3u8Content = client.get(url).bodyAsText()
                val baseUrl = URI(url).resolve(".").toString()

                // 解析M3U8内容
                val (segments, headers) = M3U8Parser.parse(m3u8Content, baseUrl)
                val (minSeq, maxSeq) = M3U8Parser.getSequenceRange(segments)

                // 更新客户端状态
                PlaybackStateManager.updateState(
                    clientId, PlaybackState(
                        currentM3u8Url = url, lastSequenceNumber = minSeq - 1, // 初始化为第一个序列之前
                        allSegments = segments, lastAccessTime = System.currentTimeMillis()
                    )
                )

                // 生成重写后的M3U8
                val rewrittenContent = M3U8Parser.rewriteM3u8(segments, headers, -1, clientId)

                // 预下载后续TS片段
                CoroutineScope(Dispatchers.IO).launch {
                    val semaphore = Semaphore(MAX_DOWNLOAD_THREADS)
                    segments.take(MAX_DOWNLOAD_THREADS * 2).forEach { tsSegment ->
                        semaphore.acquire()
                        launch {
                            try {
                                if (!CacheManager.isCached(tsSegment.url)) {
                                    val targetFile = CacheManager.getCacheFile(tsSegment.url)
                                    DownloadManager.startDownload(tsSegment.url, targetFile)
                                }
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }

                call.respondText(rewrittenContent, ContentType("application", "octet-stream"))
            } catch (e: Exception) {
                call.respondText("获取M3U8失败: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        // 代理TS文件 - 核心的连贯性检查逻辑
        get("/proxy/ts") {
            val url = call.request.queryParameters["url"]?.decodeURLPart() ?: return@get call.respondText(
                "缺少URL参数", status = HttpStatusCode.BadRequest
            )
            val sequence = call.request.queryParameters["seq"]?.toIntOrNull() ?: return@get call.respondText(
                "缺少序列号参数", status = HttpStatusCode.BadRequest
            )
            val clientId = call.request.queryParameters["clientId"] ?: return@get call.respondText(
                "缺少clientId参数", status = HttpStatusCode.BadRequest
            )

            try {
                // 获取客户端状态
                val clientState = PlaybackStateManager.getState(clientId)
                    ?: return@get call.respondText("客户端状态不存在，请先获取M3U8", status = HttpStatusCode.BadRequest)

                // 检查TS序列号连贯性
                val lastSequence = clientState.lastSequenceNumber
                val isContinuous =
                    sequence == lastSequence + 1 || (lastSequence == -1 && sequence == clientState.allSegments.firstOrNull()?.sequenceNumber)

                // 如果不连贯，判定为跳转
                if (!isContinuous && Math.abs(sequence - lastSequence) > MAX_SEQUENCE_GAP) {
                    println("检测到跳转: 从 $lastSequence 到 $sequence")

                    // 1. 找到跳转目标位置的片段
                    val targetSegment = M3U8Parser.findSegmentBySequence(clientState.allSegments, sequence)
                        ?: return@get call.respondText("找不到指定序列号的TS片段", status = HttpStatusCode.NotFound)

                    // 2. 确定需要保留的序列号范围（当前序列前后各5个，可调整）
                    val startKeep = Math.max(clientState.allSegments.first().sequenceNumber, sequence - 5)
                    val endKeep = Math.min(clientState.allSegments.last().sequenceNumber, sequence + 10)

                    // 3. 取消范围外的下载任务
                    DownloadManager.cancelDownloadsOutsideRange(clientState.allSegments, startKeep, endKeep)

                    // 4. 清理范围外的缓存
                    CacheManager.cleanCacheOutsideRange(clientState.allSegments, startKeep, endKeep)

                    // 5. 预下载跳转后的后续片段
                    CoroutineScope(Dispatchers.IO).launch {
                        val semaphore = Semaphore(MAX_DOWNLOAD_THREADS)
                        clientState.allSegments.filter { it.sequenceNumber in sequence..endKeep }.forEach { tsSegment ->
                            semaphore.acquire()
                            launch {
                                try {
                                    if (!CacheManager.isCached(tsSegment.url)) {
                                        val targetFile = CacheManager.getCacheFile(tsSegment.url)
                                        DownloadManager.startDownload(tsSegment.url, targetFile)
                                    }
                                } finally {
                                    semaphore.release()
                                }
                            }
                        }
                    }
                }

                // 更新客户端状态（记录最后访问的序列号）
                PlaybackStateManager.updateState(
                    clientId, clientState.copy(lastSequenceNumber = sequence)
                )

                // 处理TS文件响应
                val cacheFile = CacheManager.getCacheFile(url)
                if (CacheManager.isCached(url)) {
                    println("使用缓存: $url")
                    call.respondFile(cacheFile)
                } else {
                    // 未缓存，从源服务器获取并缓存
                    if (!DownloadManager.isDownloading(url)) {
                        DownloadManager.startDownload(url, cacheFile)
                    }

                    val client = HttpClient(CIO)
                    val response = client.get(url)
                    call.respondBytes(
                        bytes = response.bodyAsBytes(), contentType = ContentType.Video.MPEG
                    )
                }
            } catch (e: Exception) {
                call.respondText("获取TS文件失败: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        // 根路径说明
        get("/") {
            call.respondText(
                "M3U8代理服务器运行中\n" + "使用步骤:\n" + "1. 获取客户端ID: /proxy/client-id\n" + "2. 获取代理M3U8: /proxy/m3u8?url=源M3U8地址&clientId=你的客户端ID\n" + "服务器会自动根据TS请求的连贯性判断跳转并调整下载策略"
            )
        }
    }
}

// 信号量实现，控制并发下载数量
class Semaphore(private val permits: Int) {
    private val available = AtomicInteger(permits)

    suspend fun acquire() {
        while (true) {
            val current = available.get()
            if (current > 0 && available.compareAndSet(current, current - 1)) {
                return
            }
            delay(10)
        }
    }

    fun release() {
        available.incrementAndGet()
    }
}
