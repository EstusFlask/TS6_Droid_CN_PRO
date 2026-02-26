package dev.tsdroid.data

data class ServerBookmark(
    val name: String,
    val address: String,
    val nickname: String,
    val password: String? = null,
    val channel: String? = null,
    val serverName: String? = null,
    val iconId: Long = 0,
)
