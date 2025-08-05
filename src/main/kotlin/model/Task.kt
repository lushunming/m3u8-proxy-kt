package model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String,
    val name: String,
    val url: String,
    val type: String  //m3u8 or mp4

    )