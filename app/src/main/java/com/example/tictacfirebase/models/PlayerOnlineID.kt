package com.example.tictacfirebase.models


data class PlayerOnlineID(val id: String, val Player: String) {
    constructor() : this("", "")
}