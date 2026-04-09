package com.example.tictacfirebase.game

/**
 * Модель состояния игровой доски (ходы игроков)
 * Используется GameManager для проверки победителя
 */
data class GameBoardState(
    val player1Moves: List<Int> = emptyList(),
    val player2Moves: List<Int> = emptyList(),
    val activePlayer: Int = 1, // 1 или 2
    val winner: Int? = null, // null, 1, 2, или 0 (ничья)
    val isGameActive: Boolean = true,
    val sessionID: String? = null,
    val playerSymbol: String? = null // "X" или "O"
)

/**
 * Результат проверки победителя
 */
sealed class WinResult {
    object NoWinner : WinResult()
    object Player1Wins : WinResult()
    object Player2Wins : WinResult()
    object Draw : WinResult()
}

/**
 * Класс для управления логикой игры в крестики-нолики
 */
class GameManager {
    
    private var player1Moves = mutableListOf<Int>()
    private var player2Moves = mutableListOf<Int>()
    private var activePlayer = 1
    
    /**
     * Проверка выигрышной комбинации
     */
    fun checkWinner(): WinResult {
        val winningCombinations = listOf(
            // Ряды
            listOf(1, 2, 3),
            listOf(4, 5, 6),
            listOf(7, 8, 9),
            // Колонки
            listOf(1, 4, 7),
            listOf(2, 5, 8),
            listOf(3, 6, 9),
            // Диагонали
            listOf(1, 5, 9),
            listOf(3, 5, 7)
        )
        
        for (combination in winningCombinations) {
            if (player1Moves.containsAll(combination)) {
                return WinResult.Player1Wins
            }
            if (player2Moves.containsAll(combination)) {
                return WinResult.Player2Wins
            }
        }
        
        // Проверка на ничью (все клетки заполнены)
        if (player1Moves.size + player2Moves.size == 9) {
            return WinResult.Draw
        }
        
        return WinResult.NoWinner
    }
    
    /**
     * Выполнение хода
     * @param cellId ID клетки (1-9)
     * @return true если ход успешен, false если клетка занята или игра окончена
     */
    fun makeMove(cellId: Int): Boolean {
        if (cellId !in 1..9) return false
        if (player1Moves.contains(cellId) || player2Moves.contains(cellId)) return false
        
        when (activePlayer) {
            1 -> player1Moves.add(cellId)
            2 -> player2Moves.add(cellId)
        }
        
        // Переключение игрока
        activePlayer = if (activePlayer == 1) 2 else 1
        
        return true
    }
    
    /**
     * Получение текущего активного игрока
     */
    fun getActivePlayer(): Int = activePlayer
    
    /**
     * Установка активного игрока
     */
    fun setActivePlayer(player: Int) {
        if (player == 1 || player == 2) {
            activePlayer = player
        }
    }
    
    /**
     * Сброс игры в начальное состояние
     */
    fun reset() {
        player1Moves.clear()
        player2Moves.clear()
        activePlayer = 1
    }
    
    /**
     * Применение хода из сети (от другого игрока)
     */
    fun applyRemoteMove(cellId: Int, playerEmail: String, myEmail: String, playerSymbol: String?) {
        // Определяем, какой это игрок (1 или 2) на основе email и символа
        val isPlayer1 = if (playerSymbol == "X") {
            playerEmail == myEmail
        } else {
            playerEmail != myEmail
        }
        
        if (isPlayer1) {
            if (!player1Moves.contains(cellId)) {
                player1Moves.add(cellId)
            }
        } else {
            if (!player2Moves.contains(cellId)) {
                player2Moves.add(cellId)
            }
        }
    }
    
    /**
     * Получение текущего состояния игры
     */
    fun getGameState(): GameBoardState {
        return GameBoardState(
            player1Moves = player1Moves.toList(),
            player2Moves = player2Moves.toList(),
            activePlayer = activePlayer,
            winner = when (val result = checkWinner()) {
                is WinResult.Player1Wins -> 1
                is WinResult.Player2Wins -> 2
                is WinResult.Draw -> 0
                else -> null
            },
            isGameActive = checkWinner() is WinResult.NoWinner
        )
    }
}
