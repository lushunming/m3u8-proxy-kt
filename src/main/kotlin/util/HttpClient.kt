package cn.com.lushunming.util

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.cio.Response

object HttpClientUtil {
    val client = HttpClient(CIO)

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