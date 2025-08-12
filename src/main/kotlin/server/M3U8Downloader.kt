package cn.com.lushunming.server

import cn.com.lushunming.model.DownloadStatus
import cn.com.lushunming.service.TaskService
import cn.com.lushunming.util.HttpClientUtil
import cn.com.lushunming.util.Util
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.File

val logger = LoggerFactory.getLogger(M3U8Downloader::class.java)


class M3U8Downloader(private val outputDir: String) {


    // 解析M3U8文件
    suspend fun parseM3U8(m3u8Url: String, headers: Map<String, String>): M3U8Info {
        val response = HttpClientUtil.get(m3u8Url, headers)
        val content = response.bodyAsText()

        val lines = content.lines()
        val tsUrls = mutableListOf<String>()
        val infoLines = mutableListOf<String>()
        var keyUrl: String? = null
        var iv: String? = null

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    // 解析加密信息
                    val methodMatch = Regex("METHOD=([^,]+)").find(line)
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                    val ivMatch = Regex("IV=([^,]+)").find(line)

                    if (methodMatch?.groupValues?.get(1) == "AES-128") {
                        keyUrl = uriMatch?.groupValues?.get(1)
                        iv = ivMatch?.groupValues?.get(1)
                    }
                }

                line.startsWith("#EXTINF") -> {
                    infoLines.add(line)
                }

                !line.startsWith("#") && line.isNotBlank() -> {
                    // 处理TS文件URL
                    tsUrls.add(if (line.startsWith("http")) line else resolveRelativeUrl(m3u8Url, line))
                }
            }
        }

        return M3U8Info(infoLines, tsUrls, keyUrl?.let { resolveRelativeUrl(m3u8Url, it) }, iv, m3u8Url)
    }

    private fun resolveRelativeUrl(baseUrl: String, relativePath: String): String {
        return if (relativePath.startsWith("http")) {
            relativePath
        } else {
            val base = baseUrl.substringBeforeLast("/")
            "$base/$relativePath"
        }
    }

    // 在M3U8Downloader类中添加以下方法

    private suspend fun downloadFile(url: String, headers: Map<String, String>, outputFile: File) {
        val response = HttpClientUtil.get(url, headers)
        outputFile.outputStream().use { outputStream ->
            outputStream.write(response.bodyAsBytes())
        }
    }

    suspend fun downloadAllFiles(m3u8Info: M3U8Info, headers: Map<String, String>) {
        // 创建输出目录
        val dir = File(outputDir)
        if (!dir.exists()) dir.mkdirs()

        // 下载密钥文件
        m3u8Info.keyUrl?.let { keyUrl ->
            val keyFile = File(dir, "key.key")
            downloadFile(keyUrl, headers, keyFile)
        }
        logger.info("下载密钥文件完成")

        // 生成本地M3U8文件
        generateLocalM3U8(m3u8Info, dir)

        /* // 下载TS文件
         m3u8Info.tsUrls.forEachIndexed { index, tsUrl ->
             val tsFile = File(dir, "segment${index + 1}.ts")
             downloadFile(tsUrl, tsFile)
         }*/

        val taskService = TaskService()
        download(m3u8Info.tsUrls, headers, dir).collect { it ->
            when (it) {
                is DownloadStatus.Progress -> {
                    logger.info("已下载 ${it.value} %")
                    CoroutineScope(Dispatchers.IO).launch {
                        taskService.updateProgress(Util.md5(m3u8Info.url), it.value, 100)
                    }
                }

                is DownloadStatus.Done -> {
                    logger.info("下载完成")
                    CoroutineScope(Dispatchers.IO).launch {
                        taskService.updateProgress(Util.md5(m3u8Info.url), 100, 100)
                    }
                }

                is DownloadStatus.Error -> {
                    logger.info("下载失败: ${it.throwable.message}")
                }

                is DownloadStatus.None -> {
                    logger.info("下载还未开始")
                }

            }
        }


    }

    private val batchSize: Int = Runtime.getRuntime().availableProcessors() * 2
    private val maxRetries: Int = 3

    suspend fun download(
        tsUrls: List<String>, headers: Map<String, String>, dir: File
    ): Flow<DownloadStatus> {
        return flow {

            emit(DownloadStatus.Progress(0))
            val batches = tsUrls.chunked(batchSize)
            val total = tsUrls.size

            batches.forEachIndexed { batchIndex, batch ->

                val deferredList = batch.mapIndexed { innerIndex, url ->
                    CoroutineScope(Dispatchers.IO).async {
                        val globalIndex = batchIndex * batchSize + innerIndex + 1
                        downloadWithRetry(url, headers, globalIndex, dir)
                    }
                }
                deferredList.awaitAll()
                // onProgress(min((batchIndex) * batchSize + batch.size, total), total)
                emit(DownloadStatus.Progress((batchIndex* batchSize + batch.size)* 100 / total .floorDiv(1)))
            }
            emit(DownloadStatus.Done(dir))
        }.catch {
            logger.info("下载失败: ${it.message}")
            emit(DownloadStatus.Error(it))
        }
    }

    private suspend fun downloadWithRetry(url: String, headers: Map<String, String>, index: Int, dir: File) {
        var retryCount = 0
        var success = false

        while (retryCount < maxRetries && !success) {
            try {
                val file = File(dir, "segment$index.ts")
                if (file.exists()) {
                    logger.info("TS文件 segment$index.ts 已存在")
                    success = true
                    continue
                }
                val response = HttpClientUtil.get(url, headers)
                file.outputStream().use { outputStream ->
                    outputStream.write(response.bodyAsBytes())
                }
                success = true
            } catch (e: Exception) {
                retryCount++
                if (retryCount == maxRetries) {

                    logger.info("TS文件 $index 下载失败: ${e.message}")
                    throw e
                }

                delay(1000L * retryCount)
            }
        }
    }
}

private fun generateLocalM3U8(m3u8Info: M3U8Info, dir: File) {
    val m3u8Content = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:10")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")

        m3u8Info.keyUrl?.let {
            val keyLine = if (m3u8Info.iv != null) {
                "#EXT-X-KEY:METHOD=AES-128,URI=\"/ts/${Util.md5(m3u8Info.url)}/key.key\",IV=${m3u8Info.iv}"
            } else {
                "#EXT-X-KEY:METHOD=AES-128,URI=\"/ts/${Util.md5(m3u8Info.url)}/key.key\""
            }
            appendLine(keyLine)
        }

        m3u8Info.tsUrls.forEachIndexed { index, _ ->
            appendLine(m3u8Info.infoLines[index])
            appendLine("/ts/${Util.md5(m3u8Info.url)}/segment${index + 1}.ts")
        }

        appendLine("#EXT-X-ENDLIST")
    }

    File(dir, "local.m3u8").writeText(m3u8Content)
}


data class M3U8Info(
    val infoLines: List<String>, val tsUrls: List<String>, val keyUrl: String?, val iv: String?, val url: String
)


suspend fun startDownload(outputDir: String, m3u8Url: String, headers: Map<String, String>) {


    val downloader = M3U8Downloader(outputDir)
    File(outputDir, "header.tmp").writeText(Util.json(headers))

    try {
        // 解析M3U8
        val m3u8Info = downloader.parseM3U8(m3u8Url, headers)
        logger.info("解析完成，找到${m3u8Info.tsUrls.size}个TS片段")

        // 下载所有文件
        downloader.downloadAllFiles(m3u8Info, headers)
        logger.info("文件下载完成")



        logger.info("所有操作完成！本地M3U8文件位于：${File(outputDir).absolutePath}/local.m3u8")
    } catch (e: Exception) {
        logger.info("发生错误: ${e.message}")
        e.printStackTrace()
    }
}


fun main() = runBlocking {
    val outputDir = "downloads"
    val m3u8Url = "https://play.xluuss.com/play/NbW27gJa/index.m3u8"
    val headers = mapOf("a" to "b")

    val downloader = M3U8Downloader(outputDir)

    try {
        // 解析M3U8
        val m3u8Info = downloader.parseM3U8(m3u8Url, headers)
        logger.info("解析完成，找到${m3u8Info.tsUrls.size}个TS片段")

        // 下载所有文件
        downloader.downloadAllFiles(m3u8Info, headers)
        logger.info("文件下载完成")



        logger.info("所有操作完成！本地M3U8文件位于：${File(outputDir).absolutePath}/local.m3u8")
    } catch (e: Exception) {
        logger.info("发生错误: ${e.message}")
        e.printStackTrace()
    }
}
