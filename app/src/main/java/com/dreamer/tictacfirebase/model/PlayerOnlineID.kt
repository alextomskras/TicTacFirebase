package com.dreamer.tictacfirebase.model


data class PlayerOnlineID(val id: String, val Player: String) {
    constructor() : this("", "")
}