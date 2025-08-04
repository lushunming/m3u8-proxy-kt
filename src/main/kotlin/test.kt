package cn.com.lushunming
// 添加AtomicInteger导入

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

// 应用程序入口点
fun main() {
    embeddedServer(Netty, port = 8085, host = "0.0.0.0") {
        module1()
    }.start(wait = true)
}

// 应用程序模块
fun Application.module1() {

    // 初始化M3U8代理服务
    val m3u8ProxyService = M3u8ProxyService(
        downloadDir = "./downloads", maxConcurrentDownloads = 16, chunkSize = 1024
    )

    // 配置路由
    routing {
        // 健康检查路由
        get("/health") {
            call.respondText("OK")
        }

        // M3U8代理路由
        get("/proxy/m3u8") {
            val originalUrl = call.parameters.get("url") ?: return@get call.respondText(
                "Missing URL", status = HttpStatusCode.BadRequest
            )

            try {
                // 代理M3U8文件
                val proxiedM3u8 = m3u8ProxyService.proxyM3u8(originalUrl)
                call.respondText(proxiedM3u8, ContentType.Text.Plain)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        // TS文件代理路由
        get("/proxy/ts/{filename}") {
            // 使用withContext确保在协程中执行
            withContext(Dispatchers.IO) {
                val filename = call.parameters["filename"] ?: return@withContext call.respondText(
                    "Missing filename", status = HttpStatusCode.BadRequest
                )

                try {
                    // 获取文件路径（挂起函数）
                    val filePath = m3u8ProxyService.getTsFilePath(filename)
                    val file = File(filePath)

                    if (!file.exists()) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                        return@withContext
                    }

                    // 设置适当的内容类型
                    call.response.header(HttpHeaders.ContentType, ContentType.Video.MPEG.toString())
                    // 发送文件内容
                    call.respondFile(file)
                } catch (e: Exception) {
                    call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                }
            }
        }

        // key文件代理路由
        get("/proxy/key/{filename}") {
            // 使用withContext确保在协程中执行
            withContext(Dispatchers.IO) {
                val filename = call.parameters["filename"] ?: return@withContext call.respondText(
                    "Missing filename", status = HttpStatusCode.BadRequest
                )

                try {
                    // 获取文件路径（挂起函数）
                    val filePath = m3u8ProxyService.getKeyFilePath(filename)
                    val file = File(filePath)

                    if (!file.exists()) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                        return@withContext
                    }

                    // 设置适当的内容类型
                    call.response.header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    // 发送文件内容
                    call.respondFile(file)
                } catch (e: Exception) {
                    call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

// M3U8代理服务
class M3u8ProxyService(
    private val downloadDir: String, private val maxConcurrentDownloads: Int, private val chunkSize: Int
) {
    private val downloadManager = DownloadManager(maxConcurrentDownloads, chunkSize)
    private val mutex = Mutex()
    private val tsFileMap = mutableMapOf<String, String>() // 映射本地文件名到原始TS URL
    private val urlToFilenameMap = mutableMapOf<String, String>() // 映射原始TS URL到本地文件名
    private val keyFileMap = mutableMapOf<String, String>() // 映射本地文件名到原始key URL
    private val urlToKeyFilenameMap = mutableMapOf<String, String>() // 映射原始key URL到本地文件名

    init {
        // 确保下载目录存在
        File(downloadDir).mkdirs()
    }

    // 代理M3U8文件
    suspend fun proxyM3u8(originalUrl: String): String {
        return mutex.withLock {
            // 下载原始M3U8文件
            val m3u8Content = downloadManager.downloadText(originalUrl)

            // 解析并修改M3U8内容
            val modifiedContent = modifyM3u8Content(originalUrl, m3u8Content)

            // 开始预下载TS文件（低优先级）

                   preDownloadTsFiles()



            return@withLock modifiedContent
        }
    }

    // 修改M3U8内容，替换TS文件和key文件URL为本地代理URL
    private suspend fun modifyM3u8Content(baseUrl: String, content: String): String {
        val basePath = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1)
        val lines = content.lines()
        val modifiedLines = mutableListOf<String>()

        for (line in lines) {
            if (line.endsWith(".ts") || line.contains(".ts?")) {
                // 处理TS文件URL
                val tsUrl = if (line.startsWith("http")) line else basePath + line
                val tsFilename = generateTsFilename(tsUrl)
                val localTsUrl = "/proxy/ts/$tsFilename"

                // 记录TS文件映射

                tsFileMap[tsFilename] = tsUrl
                urlToFilenameMap[tsUrl] = tsFilename


                // 替换为本地代理URL
                modifiedLines.add(localTsUrl)
            } else if (line.contains("KEY=") || line.contains(".key")) {
                // 处理key文件URL
                val result = extractKeyUrl(line, basePath)
                val (old, keyUrl) = result["old"] to result["new"]
                if (keyUrl != null) {
                    val keyFilename = generateKeyFilename(keyUrl)
                    val localKeyUrl = "/proxy/key/$keyFilename"

                    // 记录key文件映射

                    keyFileMap[keyFilename] = keyUrl
                    urlToKeyFilenameMap[keyUrl] = keyFilename


                    // 替换为本地代理URL
                    val modifiedLine = line.replace(old!!, localKeyUrl)
                    modifiedLines.add(modifiedLine)
                } else {
                    // 无法提取key URL，保留原行
                    modifiedLines.add(line)
                }
            } else {
                // 保留其他行不变
                modifiedLines.add(line)
            }
        }

        return modifiedLines.joinToString("\n")
    }

    // 提取key文件URL
    private fun extractKeyUrl(line: String, basePath: String): Map<String, String> {
        // 处理类似 #EXT-X-KEY:METHOD=AES-128,URI="key.key"
        val keyRegex = Regex("URI=\"([^\"]+)\"")
        val matchResult = keyRegex.find(line)
        if (matchResult != null) {
            val keyPath = matchResult.groupValues[1]
            return if (keyPath.startsWith("http")) mapOf(
                "old" to keyPath, "new" to keyPath
            ) else mapOf("old" to keyPath, "new" to basePath + keyPath)
        }

        // 处理直接的key文件URL
        if (line.endsWith(".key")) {
            return if (line.startsWith("http")) mapOf("old" to line, "new" to line) else mapOf(
                "old" to line, "new" to basePath + line
            )
        }

        return mapOf()
    }

    // 生成唯一的key文件名
    private fun generateKeyFilename(keyUrl: String): String {
        return "key_" + keyUrl.hashCode().toString().replace('-', '_') + ".key"
    }

    // 生成唯一的TS文件名
    private fun generateTsFilename(tsUrl: String): String {
        return "ts_" + tsUrl.hashCode().toString().replace('-', '_') + ".ts"
    }

    // 获取TS文件的本地路径，并在需要时优先下载
    suspend fun getTsFilePath(filename: String): String {
        val tsUrl = mutex.withLock {
            tsFileMap[filename] ?: throw IllegalArgumentException("Unknown TS file: $filename")
        }

        val filePath = Paths.get(downloadDir, filename).toString()
        val file = File(filePath)

        // 如果文件不存在或不完整，优先下载
        if (!file.exists() || file.length() == 0L) {
            downloadManager.prioritizeDownload(tsUrl, filePath)
        }

        return filePath
    }

    // 预下载TS文件和key文件（低优先级）
    private suspend fun preDownloadTsFiles() {
        // 预下载TS文件
        val tsUrls = mutex.withLock { urlToFilenameMap.keys.toList() }
        for (tsUrl in tsUrls) {
            val filename = mutex.withLock { urlToFilenameMap[tsUrl] }
            if (filename != null) {
                val filePath = Paths.get(downloadDir, filename).toString()
                // 低优先级下载
                downloadManager.downloadFile(tsUrl, filePath, DownloadPriority.LOW)
            }
        }

        // 预下载key文件
        val keyUrls = mutex.withLock { urlToKeyFilenameMap.keys.toList() }
        for (keyUrl in keyUrls) {
            val filename = mutex.withLock { urlToKeyFilenameMap[keyUrl] }
            if (filename != null) {
                val filePath = Paths.get(downloadDir, filename).toString()
                // 低优先级下载
                downloadManager.downloadFile(keyUrl, filePath, DownloadPriority.LOW)
            }
        }
    }

    // 获取key文件的本地路径，并在需要时优先下载
    suspend fun getKeyFilePath(filename: String): String {
        val keyUrl = mutex.withLock {
            keyFileMap[filename] ?: throw IllegalArgumentException("Unknown key file: $filename")
        }

        val filePath = Paths.get(downloadDir, filename).toString()
        val file = File(filePath)

        // 如果文件不存在或不完整，优先下载
        if (!file.exists() || file.length() == 0L) {
            downloadManager.prioritizeDownload(keyUrl, filePath)
        }

        return filePath
    }

    // 下载任务优先级
    enum class DownloadPriority {
        HIGH, MEDIUM, LOW
    }

    // 下载任务
    data class DownloadTask(
        val url: String, val filePath: String, val priority: DownloadPriority, val order: Int
    ) : Comparable<DownloadTask> {
        override fun compareTo(other: DownloadTask): Int {
            // 先按优先级排序，再按顺序排序
            return if (this.priority != other.priority) {
                this.priority.ordinal.compareTo(other.priority.ordinal)
            } else {
                this.order.compareTo(other.order)
            }
        }
    }

    // 下载管理器
    class DownloadManager(
        private val maxConcurrentDownloads: Int, private val chunkSize: Int
    ) {
        private val dispatcher = Dispatchers.IO
        private val downloadJobs = mutableMapOf<String, Job>()
        private val pendingTasks = PriorityQueue<DownloadTask>()
        private val activeDownloadCount = AtomicInteger(0)
        private val mutex = Mutex()
        private var taskCounter = 0

        // 下载文本内容
        suspend fun downloadText(url: String): String {
            return withContext(dispatcher) {
                URL(url).openStream().bufferedReader().use { it.readText() }
            }
        }

        // 下载文件（支持优先级）
        suspend fun downloadFile(
            url: String, filePath: String, priority: DownloadPriority = DownloadPriority.MEDIUM
        ): Boolean {
            return mutex.withLock {
                // 检查文件是否已下载
                val file = File(filePath)
                if (file.exists() && file.length() > 0) {
                    return@withLock true
                }

                // 检查是否已有下载任务在进行
                if (downloadJobs.containsKey(url)) {
                    // 如果已有任务，但优先级不同，更新优先级
                    // 这里简化处理，实际应用中可能需要更复杂的优先级调整
                    return@withLock waitForDownload(url, file)
                }

                // 创建新的下载任务
                val task = DownloadTask(url, filePath, priority, taskCounter++)
                pendingTasks.add(task)

                // 处理待处理任务
                processPendingTasks()

                // 等待当前任务完成
                return@withLock waitForDownload(url, file)
            }
        }

        // 等待下载完成
        private suspend fun waitForDownload(url: String, file: File): Boolean {
            while (true) {
                mutex.withLock {
                    val job = downloadJobs[url]
                    if (job == null) {
                        // 任务已完成或不存在
                        return@withLock file.exists() && file.length() > 0
                    }

                    if (job.isCompleted) {
                        downloadJobs.remove(url)
                        return@withLock file.exists() && file.length() > 0
                    }
                }

                // 等待一段时间后重试
                delay(100)
            }
        }

        // 处理待处理任务
        private suspend fun processPendingTasks() {
            while (activeDownloadCount.get() < maxConcurrentDownloads && pendingTasks.isNotEmpty()) {
                val task = mutex.withLock { pendingTasks.poll() }
                if (task != null) {
                    activeDownloadCount.incrementAndGet()

                    val job = CoroutineScope(dispatcher).launch {
                        try {
                            URL(task.url).openStream().use { inputStream ->
                                File(task.filePath).outputStream().use { outputStream ->
                                    inputStream.copyTo(outputStream, chunkSize)
                                }
                            }
                        } catch (e: Exception) {
                            println("Download failed: ${e.message}")
                            File(task.filePath).delete()
                        } finally {
                            mutex.withLock {
                                downloadJobs.remove(task.url)
                                activeDownloadCount.decrementAndGet()
                                // 触发处理下一个任务
                                processPendingTasks()
                            }
                        }
                    }

                    mutex.withLock {
                        downloadJobs[task.url] = job
                    }
                }
            }
        }

        // 优先下载指定的TS文件
        suspend fun prioritizeDownload(tsUrl: String, tsFilePath: String) {
            downloadFile(tsUrl, tsFilePath, DownloadPriority.HIGH)
        }
    }
}


