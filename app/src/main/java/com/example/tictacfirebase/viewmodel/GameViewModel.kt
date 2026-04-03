package com.example.tictacfirebase.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tictacfirebase.game.GameManager
import com.example.tictacfirebase.model.GameState
import com.example.tictacfirebase.model.GameStatus
import com.example.tictacfirebase.model.UiEffect
import com.example.tictacfirebase.model.UiEvent
import com.example.tictacfirebase.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
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
    
    // Поток для одноразовых эффектов (toast, навигация)
    private val _uiEffect = MutableSharedFlow<UiEffect>()
    val uiEffect: SharedFlow<UiEffect> = _uiEffect.asSharedFlow()

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
    
    /**
     * Публичный метод для обновления статуса сети из Activity
     * Вызывается когда MainActivity наблюдает за сетью и показывает оверлей
     */
    fun observeNetworkStatusForUi(isOnline: Boolean) {
        viewModelScope.launch {
            updateState { copy(isOnline = isOnline) }
        }
    }

    /**
     * Наблюдение за входящими запросами на игру
     */
    fun observeGameRequests(userEmail: String): Flow<String> {
        return gameRepository.observeGameRequests(userEmail)
    }

    /**
     * Очистка запроса на игру после обработки
     */
    suspend fun clearGameRequest(userEmail: String) {
        gameRepository.clearGameRequest(userEmail)
    }

    /**
     * Обновление токена пользователя
     */
    suspend fun updateUserToken(userEmail: String, token: String) {
        gameRepository.updateUserToken(userEmail, token)
    }

    /**
     * Получение аватара пользователя
     */
    suspend fun getUserProfileImage(email: String): String? {
        return gameRepository.getUserProfileImage(email).getOrNull()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            // Загружаем имена и аватарки
            val playerNamesResult = gameRepository.getPlayerNames(gameId)
            
            if (playerNamesResult is com.example.tictacfirebase.utils.Result.Success) {
                val playerNames = playerNamesResult.data
                val myName = playerNames.first
                val opponentName = playerNames.second
                
                // Загружаем аватарки (асинхронно, не блокируя основной поток)
                val myAvatarResult = gameRepository.getUserProfileImage(myName)
                val opponentAvatarResult = gameRepository.getUserProfileImage(opponentName)
                
                val myAvatar = if (myAvatarResult is com.example.tictacfirebase.utils.Result.Success) myAvatarResult.data else null
                val opponentAvatar = if (opponentAvatarResult is com.example.tictacfirebase.utils.Result.Success) opponentAvatarResult.data else null

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
            } else if (playerNamesResult is com.example.tictacfirebase.utils.Result.Error) {
                val error = playerNamesResult.exception
                val errorMessage = playerNamesResult.message ?: error.message ?: "Неизвестная ошибка"
                updateState { 
                    copy(
                        isLoading = false,
                        errorMessage = "Ошибка загрузки данных: $errorMessage"
                    )
                }
                sendEffect(com.example.tictacfirebase.model.UiEffect.ShowToast("Ошибка: $errorMessage"))
            }
        }
    }

    private fun observeGameChanges() {
        viewModelScope.launch {
            gameRepository.observeBoardState(gameId).collect { board ->
                val currentTurnResult = gameRepository.getCurrentTurn(gameId)
                val currentTurn = if (currentTurnResult is com.example.tictacfirebase.utils.Result.Success) currentTurnResult.data else ""
                val myName = _gameState.value.currentPlayerName
                
                // Обновляем состояние доски
                val newBoardState = board.map { it ?: "" }
                
                // Определяем результат игры с помощью GameManager
                // Преобразуем доску в формат, понятный GameManager (списки ходов)
                val player1Moves = mutableListOf<Int>()
                val player2Moves = mutableListOf<Int>()
                newBoardState.forEachIndexed { index, value ->
                    if (value == "X") {
                        player1Moves.add(index + 1)
                    } else if (value == "O") {
                        player2Moves.add(index + 1)
                    }
                }
                
                // Создаем временное состояние для проверки победы
                val tempGameState = com.example.tictacfirebase.game.GameState(
                    player1Moves = player1Moves,
                    player2Moves = player2Moves
                )
                
                // Проверяем победителя
                val winResult = checkWin(tempGameState)
                
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
    
    /**
     * Проверка победителя на основе состояния игры
     * @param gameState Состояние игры с ходами игроков
     * @return Результат проверки (null если игра продолжается, иначе объект с победителем)
     */
    private fun checkWin(gameState: com.example.tictacfirebase.game.GameState): WinResult? {
        val winningCombinations = listOf(
            listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), // Ряды
            listOf(1, 4, 7), listOf(2, 5, 8), listOf(3, 6, 9), // Колонки
            listOf(1, 5, 9), listOf(3, 5, 7) // Диагонали
        )
        
        for (combination in winningCombinations) {
            if (gameState.player1Moves.containsAll(combination)) {
                return WinResult(winner = gameState.player1Moves.first().toString()) // Возвращаем игрока 1
            }
            if (gameState.player2Moves.containsAll(combination)) {
                return WinResult(winner = gameState.player2Moves.first().toString()) // Возвращаем игрока 2
            }
        }
        
        // Проверка на ничью
        if (gameState.player1Moves.size + gameState.player2Moves.size == 9) {
            return WinResult(winner = "Draw")
        }
        
        return null
    }
    
    /**
     * Результат проверки победителя
     */
    private data class WinResult(val winner: String?)

    fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.CellClicked -> handleCellClick()
            is UiEvent.CellSelected -> makeMove(event.cellIndex)
            is UiEvent.RestartGameClicked -> restartGame()
            else -> { /* Другие события обрабатываются в MainActivity */ }
        }
    }

    private fun handleCellClick() {
        viewModelScope.launch {
            val currentState = _gameState.value
            
            // Блокируем клики если нет интернета
            if (!currentState.isOnline) {
                sendEffect(UiEffect.ShowToast("Нет подключения к интернету"))
                return@launch
            }
            
            if (!currentState.isMyTurn || currentState.gameStatus != GameStatus.Playing) {
                sendEffect(UiEffect.ShowToast("Не ваш ход!"))
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
                sendEffect(UiEffect.ShowToast("Нет подключения к интернету"))
                return@launch
            }
            
            if (!currentState.isMyTurn || currentState.gameStatus != GameStatus.Playing) {
                sendEffect(UiEffect.ShowToast("Не ваш ход!"))
                return@launch
            }

            val board = currentState.boardState.toMutableList()
            if (board[cellIndex].isNotEmpty()) {
                sendEffect(UiEffect.ShowToast("Клетка занята"))
                return@launch // Клетка занята
            }

            // Определяем мой символ
            val mySymbolResult = gameRepository.getFirstPlayer(gameId)
            val firstPlayer = if (mySymbolResult is com.example.tictacfirebase.utils.Result.Success) mySymbolResult.data else ""
            val mySymbol = if (currentState.currentPlayerName == firstPlayer) "X" else "O"

            board[cellIndex] = mySymbol
            
            // Отправляем ход на сервер
            val updateResult = gameRepository.updateBoardState(gameId, board)
            
            if (updateResult is com.example.tictacfirebase.utils.Result.Error) {
                val errorMessage = updateResult.message ?: updateResult.exception.message ?: "Неизвестная ошибка"
                sendEffect(UiEffect.ShowToast("Ошибка хода: $errorMessage"))
            }
            // В случае успеха ничего не делаем - состояние обновится через observeGameChanges
        }
    }

    private fun restartGame() {
        viewModelScope.launch {
            val currentTurnResult = gameRepository.getCurrentTurn(gameId)
            val currentTurn = if (currentTurnResult is com.example.tictacfirebase.utils.Result.Success) currentTurnResult.data else ""
            val restartResult = gameRepository.restartGame(gameId)
            
            if (restartResult is com.example.tictacfirebase.utils.Result.Success) {
                updateState {
                    copy(
                        boardState = List(9) { "" },
                        gameStatus = GameStatus.Playing,
                        isMyTurn = currentTurn == _gameState.value.currentPlayerName
                    )
                }
                sendEffect(UiEffect.ShowToast("Игра перезапущена"))
            } else if (restartResult is com.example.tictacfirebase.utils.Result.Error) {
                val errorMessage = restartResult.message ?: restartResult.exception.message ?: "Неизвестная ошибка"
                sendEffect(UiEffect.ShowToast("Ошибка перезапуска: $errorMessage"))
            }
        }
    }

    private suspend fun updateState(update: GameState.() -> GameState) {
        _gameState.emit(_gameState.value.update())
    }
    
    /**
     * Отправка UI эффекта (toast, навигация и т.д.)
     */
    private suspend fun sendEffect(effect: UiEffect) {
        _uiEffect.emit(effect)
    }

    // Экспортируем имя текущего игрока для внешнего использования
    val currentPlayerName: String
        get() = _gameState.value.currentPlayerName
}
