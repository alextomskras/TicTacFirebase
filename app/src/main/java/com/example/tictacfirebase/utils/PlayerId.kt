package com.example.tictacfirebase.utils

/**
 * Единый канон (канонический формат) идентификатора игрока.
 *
 * ПРИЧИНА появления бага «один игрок может натыкать все клетки»:
 * в разных местах приложения один и тот же игрок назывался по-разному:
 *  - узлы users/* адресуются по логину: email.substringBefore("@") ("alice");
 *  - PlayerOnline/{sessionId}/firstPlayer|player1|player2|currentTurn хранили ПОЛНЫЙ email;
 *  - значения в клетках "1".."9" — тоже полный email;
 *  - сравнения вида `currentTurn == currentPlayerName` были case-sensitive,
 *    а email вводился пользователем вручную (etEmail) без приведения к нижнему регистру.
 * Из-за рассогласования сравнений оба клиента считали, что ход их,
 * а observeBoardState любое несовпадение трактовал как "O".
 *
 * ПРАВИЛО: все строки, которыми обмениваются клиенты через БД
 * (currentTurn, firstPlayer, player1, player2, значения клеток, request),
 * приводятся к этому канону перед записью и перед сравнением.
 *
 * Канон = trim + lowercase. Сохраняем форму email целиком, чтобы не ломать
 * обратную совместимость с уже существующими сессиями в БД
 * (users/* по-прежнему адресуется через splitEmail()).
 */
fun String.asCanonicalPlayer(): String = this.trim().lowercase()

/**
 * Сравнение идентификаторов игроков в каноническом формате.
 */
fun samePlayer(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return a.asCanonicalPlayer() == b.asCanonicalPlayer()
}
