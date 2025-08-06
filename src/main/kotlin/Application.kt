package cn.com.lushunming

import io.ktor.server.application.*
import service.DatabaseFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureRouting()
    configTemplate()
    DatabaseFactory.connectAndMigrate()
}
