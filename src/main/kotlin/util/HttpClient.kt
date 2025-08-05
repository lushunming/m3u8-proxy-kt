package cn.com.lushunming.util

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

object HttpClientUtil {
    val client = HttpClient(OkHttp) {

        engine {

         //   proxy = ProxyBuilder.socks(host = "127.0.0.1", port = 1080)

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