package com.example.tictacfirebase.model

/**
 * Модель пользователя для списка «Соседи» (экран OnlineUsersActivity).
 *
 * Онлайн определяется по узлу presence/<username>: если запись существует —
 * игрок в сети (клиент ставит её при старте и продлевает onDisconnect().remove()).
 */
data class OnlineUser(
    val username: String,
    val email: String,
    val isOnline: Boolean,
    val profileImageUrl: String? = null
)
