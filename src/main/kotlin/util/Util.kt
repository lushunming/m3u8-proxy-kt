package cn.com.lushunming.util

import java.util.*

object Util {
    fun base64Encode(toByteArray: ByteArray): String {
        return Base64.getEncoder().encodeToString(toByteArray)
    }

    fun base64Decode(string: String): String {
        return Base64.getDecoder().decode(string).toString(charset = Charsets.UTF_8)
    }

}
