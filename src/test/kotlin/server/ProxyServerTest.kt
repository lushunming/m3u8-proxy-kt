package cn.com.lushunming.server

import org.junit.jupiter.api.Test

class ProxyServerTest {
    @Test
    fun testBuildProxyUrl() {

        val url = "https://www.baidu.com"
        val headers = mapOf<String, String>()
        val proxyUrl = ProxyServer().buildProxyUrl(url, headers, 8080)
        println(proxyUrl)
    }

}