package com.example.tictacfirebase.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tictacfirebase.game.GameManager
import com.example.tictacfirebase.model.GameState
import com.example.tictacfirebase.model.GameStatus
import com.example.tictacfirebase.model.UiEffect
import com.example.tictacfirebase.model.UiEvent
import com.example.tictacfirebase.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val result = gameRepository.updateUserToken(userEmail, token)
        if (result is com.example.tictacfirebase.utils.Result.Error) {
            throw result.exception
        }
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
                // Определяем текущего пользователя из состояния или из сессии
                val myName = _gameState.value.currentPlayerName.ifEmpty { 
                    // Если имя еще не установлено, пытаемся определить по email из активности
                    // В этом случае берем player1 как текущего игрока (для отправителя запроса)
                    sessionInfo.player1 
                }
                val opponentName = if (myName == sessionInfo.player1) sessionInfo.player2 else sessionInfo.player1
                sessionInfo.firstPlayer
                val currentTurn = sessionInfo.currentTurn
                
                // Определяем кто есть кто относительно текущего пользователя
                // player1 в базе - это первый игрок (X), player2 - второй игрок (O)
                // Нам нужно определить: являюсь ли я player1 или player2
                val amIPlayer1 = (myName == sessionInfo.player1)
                
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
                        gameStatus = GameStatus.Playing,
                        // Сохраняем кто первый игрок для определения символа
                        isFirstPlayer = amIPlayer1
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
                sendEffect(UiEffect.ShowToast("Ошибка: $errorMessage"))
            }
        }
    }

    private fun observeGameChanges() {
        viewModelScope.launch {
            gameRepository.observeBoardState(gameId).collect { board ->
                _gameState.value.currentPlayerName
                
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
                
                // Определяем статус игры на основе результата
                // Используем isFirstPlayer для определения кто я (X или O)
                val currentState = _gameState.value
                val newStatus = when {
                    winResult?.winner == "Player1" -> {
                        // Победил игрок с X. Если я первый игрок (X), то я победил
                        if (currentState.isFirstPlayer) GameStatus.Won else GameStatus.Lost
                    }
                    winResult?.winner == "Player2" -> {
                        // Победил игрок с O. Если я не первый игрок (значит я O), то я победил
                        if (!currentState.isFirstPlayer) GameStatus.Won else GameStatus.Lost
                    }
                    winResult?.winner == "Draw" -> GameStatus.Draw
                    else -> GameStatus.Playing
                }

                updateState {
                    copy(
                        boardState = newBoardState,
                        gameStatus = newStatus
                        // isMyTurn обновляется отдельно через observeCurrentTurn для избежания race condition
                    )
                }
            }
        }

        // Отдельное наблюдение за изменениями текущего хода для быстрого обновления UI
        viewModelScope.launch {
            gameRepository.observeCurrentTurn(gameId).collect { currentTurn ->
                val currentState = _gameState.value
                // Определяем имя текущего пользователя - берем из состояния если уже установлено
                var myName = currentState.currentPlayerName
                
                // Если имя еще не установлено, пытаемся определить из сессии
                if (myName.isEmpty() && currentTurn.isNotEmpty()) {
                    // Получаем информацию о сессии чтобы определить кто есть кто
                    val sessionInfoResult = gameRepository.getSessionInfo(gameId)
                    if (sessionInfoResult is com.example.tictacfirebase.utils.Result.Success) {
                        val sessionInfo = sessionInfoResult.data
                        // Предполагаем что текущий пользователь это player1 если его ход или он первый игрок
                        myName = sessionInfo.player1.ifEmpty { sessionInfo.firstPlayer }
                        
                        // Обновляем opponentName и isFirstPlayer
                        val opponentName = sessionInfo.player2
                        val amIPlayer1 = (myName == sessionInfo.player1)
                        
                        updateState {
                            copy(
                                currentPlayerName = myName,
                                opponentName = opponentName,
                                isFirstPlayer = amIPlayer1,
                                isMyTurn = currentTurn == myName
                            )
                        }
                        return@collect
                    }
                }
                
                val isMyTurn = currentTurn == myName
                
                updateState {
                    copy(isMyTurn = isMyTurn)
                }
            }
        }

        // Слушаем выход соперника
        viewModelScope.launch {
            _gameState.collect { state ->
                if (state.sessionId.isNullOrEmpty()) {
                    return@collect // Не наблюдаем пока нет sessionId
                }
                
                gameRepository.observeOpponentLeft(state.sessionId).collect { hasLeft ->
                    if (hasLeft) {
                        updateState { copy(gameStatus = GameStatus.OpponentLeft) }
                    }
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
                return WinResult(winner = "Player1") // Возвращаем игрока 1 (X)
            }
            if (gameState.player2Moves.containsAll(combination)) {
                return WinResult(winner = "Player2") // Возвращаем игрока 2 (O)
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
            is UiEvent.StartNewGame -> startNewGame()
            is UiEvent.CreateGameClicked -> { /* TODO: Implement create game */ }
            is UiEvent.JoinGameClicked -> { /* TODO: Implement join game */ }
            is UiEvent.JoinWithCode -> { /* TODO: Implement join with code */ }
        }
    }

    /**
     * Запуск новой игры - сброс состояния для начала игры с новым соперником
     */
    private fun startNewGame() {
        viewModelScope.launch {
            // Сбрасываем состояние игры
            updateState { 
                copy(
                    sessionId = null,
                    boardState = List(9) { "" },
                    gameStatus = GameStatus.WaitingForOpponent,
                    isMyTurn = false,
                    isFirstPlayer = false,
                    currentPlayerName = "",
                    opponentName = "",
                    playerAvatarUrl = null,
                    opponentAvatarUrl = null,
                    errorMessage = null
                ) 
            }
            
            // Показываем toast через эффект
            sendEffect(UiEffect.ShowToast("Новая игра готова!"))
        }
    }

    /**
     * Отправка запроса на игру другому пользователю
     * ПРОВЕРЯЕТ: нет ли уже активного приглашения
     */
    private fun sendGameRequest(fromEmail: String, toEmail: String) {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                // Очищаем sessionId перед отправкой нового запроса - это сбросит состояние старой игры
                updateState { 
                    copy(
                        sessionId = null,
                        boardState = List(9) { "" },
                        gameStatus = GameStatus.WaitingForOpponent,
                        isMyTurn = false,
                        isFirstPlayer = false,
                        currentPlayerName = "",
                        opponentName = ""
                    ) 
                }
                
                val result = gameRepository.sendGameRequest(fromEmail, toEmail)
                if (result is com.example.tictacfirebase.utils.Result.Success) {
                    // Запрос успешно отправлен (или уже существовал)
                    // Сессия уже создана в sendGameRequest() - там же где и запрос
                    
                    // Обновляем состояние с sessionId - это запустит observeGameChanges
                    // Отправитель запроса будет player1 (X) и первым ходом
                    updateState { 
                        copy(
                            sessionId = generateSessionId(fromEmail, toEmail),
                            currentPlayerName = fromEmail,  // Текущий пользователь (отправитель)
                            opponentName = toEmail,         // Соперник (получатель)
                            isMyTurn = true,                // Отправитель запроса ходит первым
                            gameStatus = GameStatus.Playing,
                            boardState = List(9) { "" },
                            isFirstPlayer = true            // Отправитель запроса всегда первый игрок (X)
                        ) 
                    }
                    
                    sendEffect(UiEffect.ShowToast("Запрос отправлен пользователю $toEmail. Игра началась! Ваш ход (X)"))
                } else if (result is com.example.tictacfirebase.utils.Result.Error) {
                    val errorMessage = result.message ?: result.exception.message ?: "Неизвестная ошибка"
                    // Проверяем, это ошибка "встречного приглашения"?
                    if (errorMessage.contains("уже пригласил вас")) {
                        sendEffect(UiEffect.ShowToast(errorMessage))
                    } else {
                        sendEffect(UiEffect.ShowToast("Ошибка отправки запроса: $errorMessage"))
                    }
                }
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    /**
     * Генерация симметричного имени сессии (комнаты) для двух игроков.
     * Email'ы сортируются, чтобы имя сессии было одинаковым независимо от того,
     * кто отправил запрос, а кто принял.
     * Например: "user1_user2" вместо "user2_user1"
     */
    private fun generateSessionId(email1: String, email2: String): String {
        val name1 = email1.substringBefore("@")
        val name2 = email2.substringBefore("@")
        return if (name1 < name2) {
            "${name1}_${name2}"
        } else {
            "${name2}_${name1}"
        }
    }
    
    /**
     * Принятие запроса на игру
     * СОЗДАЕТ игровую сессию и определяет кто будет X, а кто O
     * ОТПРАВИТЕЛЬ запроса (fromEmail) получает "X" и ходит первым
     * ПРИНЯВШИЙ запрос (toEmail) получает "O" и ходит вторым
     */
    private fun acceptGameRequest(fromEmail: String, toEmail: String) {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            try {
                Log.d("GameViewModel", "=== ACCEPTING GAME REQUEST ===")
                Log.d("GameViewModel", "fromEmail (X, first): $fromEmail")
                Log.d("GameViewModel", "toEmail (O, second): $toEmail")
                
                // Создаем sessionId заранее
                val sessionId = generateSessionId(fromEmail, toEmail)
                Log.d("GameViewModel", "Generated SessionID: $sessionId")
                
                // ВАЖНО: Сначала настраиваем сессию (создаем поле с клетками 1-9), ТОЛЬКО ПОТОМ очищаем запрос
                // Если очистить запрос раньше, могут возникнуть проблемы с синхронизацией
                Log.d("GameViewModel", "Calling setupGameSession...")
                val setupResult = gameRepository.setupGameSession(sessionId, fromEmail, toEmail)
                Log.d("GameViewModel", "setupGameSession completed with result: $setupResult")
                
                if (setupResult is com.example.tictacfirebase.utils.Result.Success) {
//                    // Очищаем запрос ПОСЛЕ успешной настройки сессии
//                    Log.d("GameViewModel", "Clearing user requests...")
//                    gameRepository.clearUserRequests(toEmail)
//                    Log.d("GameViewModel", "User requests cleared")
                    
                    // Загружаем аватарки ПЕРЕД обновлением состояния
                    val myAvatarResult = gameRepository.getUserProfileImage(toEmail)
                    val opponentAvatarResult = gameRepository.getUserProfileImage(fromEmail)
                    
                    val myAvatar = if (myAvatarResult is com.example.tictacfirebase.utils.Result.Success) myAvatarResult.data else null
                    val opponentAvatar = if (opponentAvatarResult is com.example.tictacfirebase.utils.Result.Success) opponentAvatarResult.data else null
                    
                    // Обновляем sessionId в состоянии - это вызовет обновление gameId и перезапуск наблюдения
                    // Принявший запрос (toEmail) является вторым игроком (O), значит isFirstPlayer = false
                    updateState { 
                        copy(
                            sessionId = sessionId,
                            currentPlayerName = toEmail,  // Текущий пользователь (принявший запрос)
                            opponentName = fromEmail,     // Соперник (отправитель запроса)
                            playerAvatarUrl = myAvatar,
                            opponentAvatarUrl = opponentAvatar,
                            isMyTurn = false,             // Отправитель (X) ходит первым, поэтому у текущего пользователя (O) не его ход
                            gameStatus = GameStatus.Playing,
                            boardState = List(9) { "" },   // Очищаем доску
                            isFirstPlayer = false          // Принявший запрос всегда второй игрок (O)
                        ) 
                    }
                    
                    Log.d("GameViewModel", "Game session successfully created and setup!")
                    Log.d("GameViewModel", "Current user ($toEmail) is player2 (O)")
                    Log.d("GameViewModel", "Opponent ($fromEmail) is player1 (X) and goes first")
                    
                    sendEffect(UiEffect.ShowToast("Игра началась! Вы ходите вторым (O). $fromEmail ходит первым (X)"))
                    // НЕ вызываем loadInitialData() - observeGameChanges уже наблюдает за изменениями через Flow
                } else if (setupResult is com.example.tictacfirebase.utils.Result.Error) {
                    val errorMessage = setupResult.message ?: setupResult.exception.message ?: "Неизвестная ошибка"
                    Log.e("GameViewModel", "Setup failed: $errorMessage")
                    sendEffect(UiEffect.ShowToast("Ошибка настройки игры: $errorMessage"))
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

    fun makeMove(cellIndexFromUi: Int) {
        viewModelScope.launch {
            // Преобразуем индекс из UI (1-9) в индекс массива (0-8)
            val cellIndex = cellIndexFromUi - 1
            
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

            // Определяем мой символ через isFirstPlayer - первый игрок (X), второй игрок (O)
            val mySymbol = if (currentState.isFirstPlayer) "X" else "O"

            // Проверка: является ли текущий игрок тем, чей сейчас ход
            // Используем isMyTurn из состояния, которое обновляется через observeCurrentTurn
            if (!currentState.isMyTurn) {
                sendEffect(UiEffect.ShowToast("Сейчас не ваш ход!"))
                return@launch
            }

            // Отправляем ход на сервер (один атомарный вызов)
            // Передаём cellIndexFromUi для записи в БД (ключи 1-9)
            val moveResult = gameRepository.makeMove(gameId, cellIndexFromUi, currentState.currentPlayerName, mySymbol)
            
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
