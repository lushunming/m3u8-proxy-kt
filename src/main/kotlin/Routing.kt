package cn.com.lushunming

import cn.com.lushunming.model.Downloads
import cn.com.lushunming.server.ProxyServer
import cn.com.lushunming.service.TaskService
import cn.com.lushunming.util.Util
import com.google.gson.Gson
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.thymeleaf.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import model.Task
import org.slf4j.LoggerFactory
import java.util.*

fun Application.configureRouting() {
    val port = environment.config.port
    val logger = LoggerFactory.getLogger(Application::class.java)
    val taskService = TaskService()
    var sessions = Collections.synchronizedList<WebSocketServerSession>(ArrayList())
    routing {
        get("/") {
            call.respond(
                ThymeleafContent(
                    "index", mapOf("user" to ThymeleafUser(1, "user1"))
                )
            )
        }
        get("/video/{id}") {
            val id = call.parameters["id"]
            val task = taskService.getTaskById(id)
            call.respond(
                ThymeleafContent(
                    "video", mapOf("url" to (task?.url ?: ""))
                )
            )
        }

        get("/proxy") {
            logger.info("代理中: ${call.parameters["url"]}")


            val url = Util.base64Decode(call.parameters["url"]!!)
            val header: Map<String, String> = Gson().fromJson<Map<String, String>>(
                Util.base64Decode(call.parameters["headers"]!!), MutableMap::class.java
            )
            ProxyServer().proxyAsync(
                url, header, call
            )
        }

        post("/download") {
            val download = call.receive<Downloads>()
            val url = ProxyServer().buildProxyUrl(download.list[0].url, download.list[0].headers, port)
            taskService.addTask(Task(UUID.randomUUID().toString(), download.list[0].filename, url))
            sessions = sessions.filter { it.isActive }.toMutableList()
            if (sessions.isNotEmpty()) {
                for (session in sessions) {

                    session.sendSerialized(taskService.getTaskList())

                }
            }

            call.respondText(url)
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
