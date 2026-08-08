package com.ycode.app.remote

data class ConnectionProfile(
    val id: String,
    var name: String,
    var protocol: String,
    var host: String,
    var port: Int,
    var username: String,
    var password: String,
    var hostKeySha256: String = "",
    var allowCommands: Boolean = false,
    var allowWrite: Boolean = false,
    var allowDelete: Boolean = false
)
