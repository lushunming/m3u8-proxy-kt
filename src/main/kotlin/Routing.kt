package cn.com.lushunming

import cn.com.lushunming.model.AppConfig
import cn.com.lushunming.model.Downloads
import cn.com.lushunming.server.M3u8ProxyServer
import cn.com.lushunming.server.ProxyServer
import cn.com.lushunming.server.startDownload
import cn.com.lushunming.service.ConfigService
import cn.com.lushunming.service.TaskService
import cn.com.lushunming.util.Constant
import cn.com.lushunming.util.HttpClientUtil.setProxy
import cn.com.lushunming.util.Util
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import model.Task
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*


fun Application.configureRouting() {
    val port = environment.config.port
    val logger = LoggerFactory.getLogger(Application::class.java)
    val taskService = TaskService()
    var sessions = Collections.synchronizedList<WebSocketServerSession>(ArrayList())




    CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {  // 检查协程是否还活跃
            delay(5000L)  // 等待指定的时间间隔
            sessions.forEach {
                it.sendSerialized(taskService.getTaskList())
            }               // 执行传入的代码块
        }
    }








    routing {
        get("/") {
            call.respond(
                ThymeleafContent(
                    "index", mapOf()
                )
            )
        }
        get("/video/{id}") {
            val id = call.parameters["id"]
            val task = taskService.getTaskById(id!!)
            call.respond(
                ThymeleafContent(
                    "video", mapOf(
                        "url" to (task?.url ?: ""), "type" to (task?.type ?: "application/x-mpegURL")
                    )
                )
            )
        }

        get("/proxy") {
            logger.info("代理中: ${call.parameters["url"]}")


            val url = Util.base64Decode(call.parameters["url"]!!)
            val header: Map<String, String> = Gson().fromJson<Map<String, String>>(
                Util.base64Decode(call.parameters["headers"]!!), MutableMap::class.java
            )
            if (url.contains("m3u8")) {
                M3u8ProxyServer().proxyAsyncM3u8(url, header, call)
            } else {
                ProxyServer().proxyAsync(
                    url, header, call
                )
            }

        }

        /**
         * 代理ts
         */

        get("/ts/{path}/{tsName}") {
            logger.info("路径: ${call.parameters["path"]}")
            logger.info("tsName: ${call.parameters["tsName"]}")


            val url = call.parameters["path"] ?: ""
            val tsName = call.parameters["tsName"] ?: ""

            call.response.header(
                HttpHeaders.ContentType, "video/mp2t"
            )
            call.respondFile(File(Constant.downloadPath + File.separator + url + File.separator + tsName))

        }
        get("/download") {
            call.respond(
                ThymeleafContent(
                    "download", mapOf()
                )
            )
        }

        get("/download/{id}") {


            val id = call.parameters["id"] ?: ""
            val old = taskService.getTaskById(id)
            if (old == null) {
                call.respondText("不存在")
                return@get
            }

            if (old.oriUrl.contains("m3u8")) {

                val dir = Constant.downloadPath + File.separator + id
                CoroutineScope(Dispatchers.IO).launch {
                    File(dir).mkdirs()
                    val headerFile = File(dir, "header.tmp")
                    val headerParam = Gson().fromJson<MutableMap<String, String>>(
                        headerFile.readText(Charsets.UTF_8), object : TypeToken<MutableMap<String, String>>() {}.type
                    )
                    startDownload(
                        dir, old.oriUrl, headerParam
                    )
                }
            }
            call.respondText("开始下载")

        }
        /**
         * 提交下载
         */
        post("/download") {

            val download = call.receive<Downloads>()
            val urlParam = download.list[0].url
            val headerParam = download.list[0].headers
            val url = ProxyServer().buildProxyUrl(urlParam, headerParam, port)
            val id = Util.md5(urlParam)
            val old = taskService.getTaskById(id);
            if (old != null) {
                call.respondText("已经存在")
                return@post
            }
            var type = ContentType.Video.MP4.toString()
            //M3U8开始下载
            if (urlParam.contains("m3u8")) {
                type = "application/x-mpegURL"
                val dir = Constant.downloadPath + File.separator + Util.md5(urlParam)
                CoroutineScope(Dispatchers.IO).launch {
                    File(dir).mkdirs()
                    val headerFile = File(dir, "header.tmp")
                    headerFile.writeText(Util.json(headerParam))
                    startDownload(
                        dir, urlParam, headerParam
                    )
                }
            }
            taskService.addTask(Task(id, download.list[0].filename, url, urlParam, type, 0, 0))
            sessions = sessions.filter { it.isActive }.toMutableList()
            if (sessions.isNotEmpty()) {
                for (session in sessions) {
                    session.sendSerialized(taskService.getTaskList())

                }
            }
            call.respondText(url)
        }

        post("/config") {
            val configService = ConfigService()
            val config = call.receive<AppConfig>()
            configService.saveConfig(config)

            setProxy(config.proxy)

            call.respondText("保存成功")
        }

        get("/config") {

            val configService = ConfigService()
            val config = configService.getConfig()
            call.respond(
                ThymeleafContent(
                    "config", mapOf("proxy" to config?.proxy, "open" to config?.open)
                )
            )
        }



        webSocket("/tasks") {
            sessions.add(this)
            sendSerialized(taskService.getTaskList())


            while (true) { // <-------------------------- notice the loop here
                val frame = incoming.receive()
                println("connect")
                if (frame is Frame.Text) {
                    val msg = frame.readText()
                    println(msg)
                    outgoing.send(Frame.Text("Server: ${msg}"))
                    // ...
                } else {
                    println(1)
                }
            }

        }


    }
}
