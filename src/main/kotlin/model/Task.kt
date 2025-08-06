package model

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table

@Serializable
data class Task(
    val id: Int, val name: String, val url: String, val type: String  //m3u8 or mp4

)


object Tasks : Table("tasks") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 1000)
    val url = varchar("url", 1000)
    val type = varchar("type", 255)
}

