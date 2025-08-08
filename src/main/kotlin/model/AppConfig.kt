package cn.com.lushunming.model

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table


@Serializable
data class AppConfig(
    val proxy: String
    )



object Config : Table("config") {
    val id = integer("id").autoIncrement()
    val proxy = varchar("proxy", 1000)

}