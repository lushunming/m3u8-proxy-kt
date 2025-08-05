package cn.com.lushunming.util

import java.math.BigInteger
import java.security.MessageDigest
import java.util.*

object Util {
    fun base64Encode(toByteArray: ByteArray): String {
        return Base64.getEncoder().encodeToString(toByteArray)
    }

    fun base64Decode(string: String): String {
        return Base64.getDecoder().decode(string).toString(charset = Charsets.UTF_8)
    }

    fun md5(url: String): String {

        val md5s = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return BigInteger(1, md5s).toString(16)

    }

}
