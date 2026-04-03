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
            
            playerNamesResult.fold(
                onSuccess = { playerNames ->
                    val myName = playerNames.first
                    val opponentName = playerNames.second
                    
                    // Загружаем аватарки (асинхронно, не блокируя основной поток)
                    val myAvatarResult = gameRepository.getUserProfileImage(myName)
                    val opponentAvatarResult = gameRepository.getUserProfileImage(opponentName)
                    
                    val myAvatar = myAvatarResult.getOrNull()
                    val opponentAvatar = opponentAvatarResult.getOrNull()

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
                },
                onFailure = { error ->
                    updateState { 
                        copy(
                            isLoading = false,
                            errorMessage = "Ошибка загрузки данных: ${error.message}"
                        )
                    }
                    sendEffect(UiEffect.ShowToast("Ошибка: ${error.message}"))
                }
            )
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
            val mySymbol = if (currentState.currentPlayerName == mySymbolResult.getOrNull()) "X" else "O"

            board[cellIndex] = mySymbol
            
            // Отправляем ход на сервер
            val updateResult = gameRepository.updateBoardState(gameId, board)
            
            updateResult.fold(
                onSuccess = {
                    // Ход успешно отправлен
                },
                onFailure = { error ->
                    sendEffect(UiEffect.ShowToast("Ошибка хода: ${error.message}"))
                }
            )
        }
    }

    private fun restartGame() {
        viewModelScope.launch {
            val currentTurnResult = gameRepository.getCurrentTurn(gameId)
            val restartResult = gameRepository.restartGame(gameId)
            
            restartResult.fold(
                onSuccess = {
                    updateState {
                        copy(
                            boardState = List(9) { "" },
                            gameStatus = GameStatus.Playing,
                            isMyTurn = currentTurnResult.getOrNull() == currentPlayerName
                        )
                    }
                    sendEffect(UiEffect.ShowToast("Игра перезапущена"))
                },
                onFailure = { error ->
                    sendEffect(UiEffect.ShowToast("Ошибка перезапуска: ${error.message}"))
                }
            )
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
