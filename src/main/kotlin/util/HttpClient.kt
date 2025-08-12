package cn.com.lushunming.util

import cn.com.lushunming.service.ConfigService
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

object HttpClientUtil {
    val configService = ConfigService();
    val client = HttpClient(OkHttp) {
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE
        }

        engine {

            // proxy = ProxyBuilder.http("http://127.0.0.1:1080" )

        }
    }

    init {

        runBlocking {

            val config = configService.getConfig()
            config?.let {
                if (it.open == 1) {
                    // 开启代理
                    setProxy(it.proxy)
                } else {
                    // 关闭代理
                    setProxy(null)
                }
            }
        }


    }

    fun setProxy(proxy: String?) {
        if (proxy == null) {
            client.engine.config.proxy = null
        } else {
            client.engine.config.proxy = ProxyBuilder.http(proxy)
        }


    }


    /**
     * get请求
     */
    suspend fun get(url: String): HttpResponse {

        val response: HttpResponse = client.get(url)
        return response
    }

    /**
     * get请求 带headers
     */
    suspend fun get(url: String, header: Map<String, String>): HttpResponse {

        val response: HttpResponse = client.get(url) {
            headers {
                header.forEach { (key, value) -> set(key, value) }
            }
        }
        return response
    }
}