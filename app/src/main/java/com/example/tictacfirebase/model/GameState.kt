package com.example.tictacfirebase.model

/**
 * Представление состояния игры для UI.
 * Immutable data class.
 */
data class GameState(
    val isLoading: Boolean = false,
    val isMyTurn: Boolean = false,
    val currentPlayerName: String = "",
    val opponentName: String = "",
    val playerAvatarUrl: String? = null,
    val opponentAvatarUrl: String? = null,
    val boardState: List<String> = List(9) { "" }, // "X", "O", ""
    val gameStatus: GameStatus = GameStatus.WaitingForOpponent,
    val errorMessage: String? = null,
    val sessionId: String? = null
)

/**
 * Статус игры (состояние матча)
 */
enum class GameStatus {
    WaitingForOpponent, // Ожидание соперника
    Playing,            // Игра идет
    Won,                // Победа
    Lost,               // Поражение
    Draw,               // Ничья
    OpponentLeft        // Соперник вышел
}

/**
 * Результат действия пользователя (UI Event)
 */
sealed class UiEvent {
    object CellClicked : UiEvent()
    object CreateGameClicked : UiEvent()
    object JoinGameClicked : UiEvent()
    object RestartGameClicked : UiEvent()
    data class JoinWithCode(val code: String) : UiEvent()
}
