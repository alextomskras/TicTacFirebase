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
    initialGameId: String,
    private val context: android.content.Context
) : ViewModel() {

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    
    // Поток для одноразовых эффектов (toast, навигация)
    private val _uiEffect = MutableSharedFlow<UiEffect>()
    val uiEffect: SharedFlow<UiEffect> = _uiEffect.asSharedFlow()

    private val gameManager = GameManager()
    
    // Идентификатор текущей игры - может быть обновлен при создании/присоединении к новой игре
    private var _gameId: String = initialGameId
    
    // Геттер для gameId, который использует sessionId из состояния если он установлен
    private val gameId: String
        get() = _gameState.value.sessionId ?: _gameId

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
            // Не показываем isLoading при первоначальной загрузке, если sessionId еще не установлен
            // Это предотвращает блокировку UI когда игра еще не началась
            val currentState = _gameState.value
            if (currentState.sessionId.isNullOrEmpty()) {
                return@launch
            }
            
            updateState { copy(isLoading = true) }
            
            // Загружаем информацию о сессии (первый игрок, текущий ход, второй игрок)
            val sessionInfoResult = gameRepository.getSessionInfo(gameId)
            
            if (sessionInfoResult is com.example.tictacfirebase.utils.Result.Success) {
                val sessionInfo = sessionInfoResult.data
                val myName = sessionInfo.player1
                val opponentName = sessionInfo.player2
                val firstPlayer = sessionInfo.firstPlayer
                val currentTurn = sessionInfo.currentTurn
                
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
                        sessionId = gameId,
                        isMyTurn = currentTurn == myName,
                        gameStatus = GameStatus.Playing
                    )
                }
            } else if (sessionInfoResult is com.example.tictacfirebase.utils.Result.Error) {
                val error = sessionInfoResult.exception
                val errorMessage = sessionInfoResult.message ?: error.message ?: "Неизвестная ошибка"
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

        // Отдельное наблюдение за изменениями текущего хода для быстрого обновления UI
        viewModelScope.launch {
            gameRepository.observeCurrentTurn(gameId).collect { currentTurn ->
                val myName = _gameState.value.currentPlayerName
                updateState {
                    copy(
                        isMyTurn = currentTurn == myName
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
            is UiEvent.SendGameRequest -> sendGameRequest(event.fromEmail, event.toEmail)
            is UiEvent.AcceptGameRequest -> acceptGameRequest(event.fromEmail, event.toEmail)
            else -> { /* Другие события обрабатываются в MainActivity */ }
        }
    }

    /**
     * Отправка запроса на игру другому пользователю
     */
    private fun sendGameRequest(fromEmail: String, toEmail: String) {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                val result = gameRepository.sendGameRequest(fromEmail, toEmail)
                if (result is com.example.tictacfirebase.utils.Result.Success) {
                    // Создаем сессию игры для отправителя запроса
                    val sessionId = "${fromEmail.substringBefore("@")}_${toEmail.substringBefore("@")}"
                    
                    // Сначала создаем сессию (очищаем старую)
                    val createResult = gameRepository.createGameSession(sessionId)
                    
                    if (createResult is com.example.tictacfirebase.utils.Result.Success) {
                        // Настраиваем сессию: отправитель запроса получает "X" и ходит первым
                        val setupResult = gameRepository.setupGameSession(sessionId, fromEmail, toEmail)
                        
                        if (setupResult is com.example.tictacfirebase.utils.Result.Success) {
                            // Обновляем sessionId в состоянии - это также обновит gameId через getter
                            updateState { 
                                copy(
                                    sessionId = sessionId,
                                    currentPlayerName = fromEmail,  // Текущий пользователь (отправитель)
                                    opponentName = toEmail,         // Соперник
                                    isMyTurn = true,                // Отправитель ходит первым
                                    gameStatus = GameStatus.Playing,
                                    boardState = List(9) { "" }     // Очищаем доску
                                ) 
                            }
                            
                            // Загружаем аватарки
                            val myAvatarResult = gameRepository.getUserProfileImage(fromEmail)
                            val opponentAvatarResult = gameRepository.getUserProfileImage(toEmail)
                            
                            val myAvatar = if (myAvatarResult is com.example.tictacfirebase.utils.Result.Success) myAvatarResult.data else null
                            val opponentAvatar = if (opponentAvatarResult is com.example.tictacfirebase.utils.Result.Success) opponentAvatarResult.data else null
                            
                            updateState {
                                copy(
                                    playerAvatarUrl = myAvatar,
                                    opponentAvatarUrl = opponentAvatar
                                )
                            }
                            
                            sendEffect(UiEffect.ShowToast("Запрос отправлен пользователю $toEmail. Вы ходите первым (X)"))
                            
                            // Перезагружаем данные игры с новым sessionId
                            loadInitialData()
                        } else if (setupResult is com.example.tictacfirebase.utils.Result.Error) {
                            val errorMessage = setupResult.message ?: setupResult.exception.message ?: "Неизвестная ошибка"
                            sendEffect(UiEffect.ShowToast("Ошибка настройки игры: $errorMessage"))
                        }
                    } else if (createResult is com.example.tictacfirebase.utils.Result.Error) {
                        val errorMessage = createResult.message ?: createResult.exception.message ?: "Неизвестная ошибка"
                        sendEffect(UiEffect.ShowToast("Ошибка создания игры: $errorMessage"))
                    }
                } else if (result is com.example.tictacfirebase.utils.Result.Error) {
                    val errorMessage = result.message ?: result.exception.message ?: "Неизвестная ошибка"
                    sendEffect(UiEffect.ShowToast("Ошибка отправки запроса: $errorMessage"))
                }
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    /**
     * Принятие запроса на игру
     */
    private fun acceptGameRequest(fromEmail: String, toEmail: String) {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                // Очищаем запрос после принятия
                gameRepository.clearUserRequests(toEmail)
                
                // Создаем сессию игры
                val sessionId = "${fromEmail.substringBefore("@")}_${toEmail.substringBefore("@")}"
                
                // Сначала создаем сессию (очищаем старую)
                val createResult = gameRepository.createGameSession(sessionId)
                
                if (createResult is com.example.tictacfirebase.utils.Result.Success) {
                    // Настраиваем сессию: первый игрок (отправитель запроса) получает "X" и ходит первым
                    val setupResult = gameRepository.setupGameSession(sessionId, fromEmail, toEmail)
                    
                    if (setupResult is com.example.tictacfirebase.utils.Result.Success) {
                        // Обновляем sessionId в состоянии - это также обновит gameId через getter
                        // toEmail - это текущий пользователь (который принял запрос)
                        // fromEmail - это соперник (который отправил запрос)
                        updateState { 
                            copy(
                                sessionId = sessionId,
                                currentPlayerName = toEmail,  // Текущий пользователь
                                opponentName = fromEmail,     // Соперник
                                isMyTurn = false,             // Отправитель ходит первым
                                gameStatus = GameStatus.Playing,
                                boardState = List(9) { "" }   // Очищаем доску
                            ) 
                        }
                        
                        // Загружаем аватарки
                        val myAvatarResult = gameRepository.getUserProfileImage(toEmail)
                        val opponentAvatarResult = gameRepository.getUserProfileImage(fromEmail)
                        
                        val myAvatar = if (myAvatarResult is com.example.tictacfirebase.utils.Result.Success) myAvatarResult.data else null
                        val opponentAvatar = if (opponentAvatarResult is com.example.tictacfirebase.utils.Result.Success) opponentAvatarResult.data else null
                        
                        updateState {
                            copy(
                                playerAvatarUrl = myAvatar,
                                opponentAvatarUrl = opponentAvatar
                            )
                        }
                        
                        sendEffect(UiEffect.ShowToast("Игра началась! Вы ходите вторым (O)"))
                        
                        // Перезагружаем данные игры с новым sessionId
                        loadInitialData()
                    } else if (setupResult is com.example.tictacfirebase.utils.Result.Error) {
                        val errorMessage = setupResult.message ?: setupResult.exception.message ?: "Неизвестная ошибка"
                        sendEffect(UiEffect.ShowToast("Ошибка настройки игры: $errorMessage"))
                    }
                } else if (createResult is com.example.tictacfirebase.utils.Result.Error) {
                    val errorMessage = createResult.message ?: createResult.exception.message ?: "Неизвестная ошибка"
                    sendEffect(UiEffect.ShowToast("Ошибка создания игры: $errorMessage"))
                }
            } finally {
                updateState { copy(isLoading = false) }
            }
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

            // Проверяем занята ли клетка в локальном состоянии
            val board = currentState.boardState
            if (cellIndex < 0 || cellIndex >= board.size) {
                sendEffect(UiEffect.ShowToast("Неверный индекс клетки"))
                return@launch
            }
            
            if (board[cellIndex].isNotEmpty()) {
                sendEffect(UiEffect.ShowToast("Клетка занята"))
                return@launch // Клетка занята
            }

            // Определяем мой символ через базу данных - первый игрок (отправитель запроса) получает "X"
            val firstPlayerResult = gameRepository.getFirstPlayer(gameId)
            val firstPlayer = if (firstPlayerResult is com.example.tictacfirebase.utils.Result.Success) firstPlayerResult.data else ""
            val mySymbol = if (currentState.currentPlayerName == firstPlayer) "X" else "O"

            // Проверка: является ли текущий игрок тем, чей сейчас ход
            val currentTurnResult = gameRepository.getCurrentTurn(gameId)
            val currentTurn = if (currentTurnResult is com.example.tictacfirebase.utils.Result.Success) currentTurnResult.data else ""
            
            if (currentTurn != currentState.currentPlayerName) {
                sendEffect(UiEffect.ShowToast("Сейчас не ваш ход!"))
                return@launch
            }

            // Отправляем ход на сервер (один атомарный вызов)
            val moveResult = gameRepository.makeMove(gameId, cellIndex, currentState.currentPlayerName, mySymbol)
            
            if (moveResult is com.example.tictacfirebase.utils.Result.Error) {
                val errorMessage = moveResult.message ?: moveResult.exception.message ?: "Неизвестная ошибка"
                sendEffect(UiEffect.ShowToast("Ошибка хода: $errorMessage"))
            }
            // В случае успеха ничего не делаем - состояние обновится через observeBoardState и observeCurrentTurn
        }
    }

    private fun restartGame() {
        viewModelScope.launch {
            // Получаем первого игрока до перезапуска - он будет ходить первым в новой игре
            val firstPlayerResult = gameRepository.getFirstPlayer(gameId)
            val firstPlayer = if (firstPlayerResult is com.example.tictacfirebase.utils.Result.Success) firstPlayerResult.data else ""
            
            val restartResult = gameRepository.restartGame(gameId)
            
            if (restartResult is com.example.tictacfirebase.utils.Result.Success) {
                val currentState = _gameState.value
                updateState {
                    copy(
                        boardState = List(9) { "" },
                        gameStatus = GameStatus.Playing,
                        isMyTurn = firstPlayer == currentState.currentPlayerName
                    )
                }
                sendEffect(UiEffect.ShowToast("Игра перезапущена. Ход игрока: ${if (firstPlayer == currentState.currentPlayerName) "Ваш" else "Соперника"}"))
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
