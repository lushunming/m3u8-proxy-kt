package cn.com.lushunming

import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlin.time.DurationUnit
import kotlin.time.toDuration

fun Application.configureHTTP() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
       // allowHeader(HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader(HttpHeaders.ContentType)
        //allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowCredentials = true
        anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
    }
    install(WebSockets) {
        contentConverter = GsonWebsocketContentConverter()
        pingPeriod = 15.toDuration(DurationUnit.SECONDS)
        timeout = 15.toDuration(DurationUnit.SECONDS)
        maxFrameSize = Long.MAX_VALUE
        masking = false

    }

    routing {
        swaggerUI(path = "openapi")
    }

}

data class ThymeleafUser(val id: Int, val name: String)