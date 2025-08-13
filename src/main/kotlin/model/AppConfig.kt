package cn.com.lushunming.model

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table


@Serializable
data class AppConfig(

    val id: Int?, val proxy: String, val open: Int = 0, val downloadPath: String = "Downloads"
)


object Config : Table("config") {
    val id = integer("id").autoIncrement()
    val proxy = varchar("proxy", 1000)
    val open = integer("open")

    val downloadPath = varchar("downloadPath", 1000)

}