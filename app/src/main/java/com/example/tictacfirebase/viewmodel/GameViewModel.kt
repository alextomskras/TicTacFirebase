package com.example.tictacfirebase.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tictacfirebase.game.GameManager
import com.example.tictacfirebase.model.GameState
import com.example.tictacfirebase.model.GameStatus
import com.example.tictacfirebase.model.UiEvent
import com.example.tictacfirebase.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием игры.
 * Инкапсулирует бизнес-логику и состояние UI.
 */
class GameViewModel(
    private val gameRepository: GameRepository,
    private val gameId: String,
    private val context: android.content.Context
) : ViewModel() {

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val gameManager = GameManager()

    init {
        observeNetworkStatus()
        loadInitialData()
        observeGameChanges()
    }

    /**
     * Наблюдение за статусом сети
     */
    private fun observeNetworkStatus() {
        viewModelScope.launch {
            com.example.tictacfirebase.utils.NetworkMonitor.observeNetworkConnectivity(context)
                .collect { isOnline ->
                    updateState { copy(isOnline = isOnline) }
                    if (!isOnline) {
                        updateState { 
                            copy(errorMessage = "Нет подключения к интернету") 
                        }
                    }
                }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                // Загружаем имена и аватарки
                val playerNames = gameRepository.getPlayerNames(gameId)
                val myName = playerNames.first
                val opponentName = playerNames.second
                
                // Загружаем аватарки (асинхронно, не блокируя основной поток)
                val myAvatar = gameRepository.getUserProfileImage(myName)
                val opponentAvatar = gameRepository.getUserProfileImage(opponentName)

                updateState {
                    copy(
                        currentPlayerName = myName,
                        opponentName = opponentName,
                        playerAvatarUrl = myAvatar,
                        opponentAvatarUrl = opponentAvatar,
                        isLoading = false,
                        sessionId = gameId
                    )
                }
            } catch (e: Exception) {
                updateState { 
                    copy(
                        isLoading = false,
                        errorMessage = "Ошибка загрузки данных: ${e.message}"
                    )
                }
            }
        }
    }

    private fun observeGameChanges() {
        viewModelScope.launch {
            gameRepository.observeBoardState(gameId).collect { board ->
                val currentTurn = gameRepository.getCurrentTurn(gameId)
                val myName = _gameState.value.currentPlayerName
                
                // Обновляем состояние доски
                val newBoardState = board.map { it ?: "" }
                
                // Определяем результат игры
                val winResult = gameManager.checkWin(newBoardState)
                
                val newStatus = when {
                    winResult != null -> {
                        if (winResult.winner == myName) GameStatus.Won
                        else if (winResult.winner == "Draw") GameStatus.Draw
                        else GameStatus.Lost
                    }
                    currentTurn == myName -> GameStatus.Playing
                    else -> GameStatus.Playing
                }

                updateState {
                    copy(
                        boardState = newBoardState,
                        isMyTurn = currentTurn == myName,
                        gameStatus = newStatus
                    )
                }
            }
        }

        // Слушаем выход соперника
        viewModelScope.launch {
            gameRepository.observeOpponentLeft(gameId).collect { hasLeft ->
                if (hasLeft) {
                    updateState { copy(gameStatus = GameStatus.OpponentLeft) }
                }
            }
        }
    }

    fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.CellClicked -> handleCellClick()
            is UiEvent.RestartGameClicked -> restartGame()
            else -> { /* Другие события обрабатываются в MainActivity */ }
        }
    }

    private fun handleCellClick() {
        viewModelScope.launch {
            val currentState = _gameState.value
            
            // Блокируем клики если нет интернета
            if (!currentState.isOnline) {
                updateState { 
                    copy(errorMessage = "Нет подключения к интернету") 
                }
                return@launch
            }
            
            if (!currentState.isMyTurn || currentState.gameStatus != GameStatus.Playing) {
                return@launch
            }

            // Логика клика вынесена в MainActivity, так как нужен индекс клетки
            // Здесь только валидация состояния
        }
    }

    fun makeMove(cellIndex: Int) {
        viewModelScope.launch {
            val currentState = _gameState.value
            
            // Блокируем ходы если нет интернета
            if (!currentState.isOnline) {
                updateState { 
                    copy(errorMessage = "Нет подключения к интернету") 
                }
                return@launch
            }
            
            if (!currentState.isMyTurn || currentState.gameStatus != GameStatus.Playing) {
                return@launch
            }

            try {
                val board = currentState.boardState.toMutableList()
                if (board[cellIndex].isNotEmpty()) {
                    return@launch // Клетка занята
                }

                // Определяем мой символ
                val mySymbol = if (currentState.currentPlayerName == 
                    gameRepository.getFirstPlayer(gameId)) "X" else "O"

                board[cellIndex] = mySymbol
                
                // Отправляем ход на сервер
                gameRepository.updateBoardState(gameId, board)
                
            } catch (e: Exception) {
                updateState {
                    copy(errorMessage = "Ошибка хода: ${e.message}")
                }
            }
        }
    }

    private fun restartGame() {
        viewModelScope.launch {
            try {
                gameRepository.restartGame(gameId)
                updateState {
                    copy(
                        boardState = List(9) { "" },
                        gameStatus = GameStatus.Playing,
                        isMyTurn = gameRepository.getCurrentTurn(gameId) == currentPlayerName
                    )
                }
            } catch (e: Exception) {
                updateState {
                    copy(errorMessage = "Ошибка перезапуска: ${e.message}")
                }
            }
        }
    }

    private suspend fun updateState(update: GameState.() -> GameState) {
        _gameState.emit(_gameState.value.update())
    }

    // Экспортируем имя текущего игрока для внешнего использования
    val currentPlayerName: String
        get() = _gameState.value.currentPlayerName
}
