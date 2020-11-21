package com.dreamer.tictacfirebase.models


data class PlayerOnlineID(val id: String, val Player: String) {
    constructor() : this("", "")
}