package com.example.tictacfirebase.repository

import android.util.Log
import com.example.tictacfirebase.utils.Result
import com.example.tictacfirebase.utils.runCatchingResult
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Repository для работы с Firebase Realtime Database
 * Инкапсулирует всю логику доступа к данным
 */
class GameRepository {
    
    private val database = FirebaseDatabase.getInstance()
    private val myRef = database.reference
    
    /**
     * Обновление токена FCM для пользователя
     */
    suspend fun updateUserToken(userId: String, token: String): Result<Unit> {
        return runCatchingResult {
            myRef.child("users").child(userId).child("newToken").setValue(token).await()
        }
    }
    
    /**
     * Отправка запроса на игру другому пользователю
     * ПРОВЕРЯЕТ: нет ли уже активного приглашения от этого пользователя
     */
    suspend fun sendGameRequest(fromUser: String, toUser: String): Result<Unit> {
        return runCatchingResult {
            val splitToUser = toUser.substringBefore("@")
            val splitFromUser = fromUser.substringBefore("@")
            
            // Проверяем существование пользователя и получаем его токен
            val tokenSnapshot = myRef.child("users").child(splitToUser).child("newToken").get().await()
            val recipientToken = tokenSnapshot.value as? String
            
            if (recipientToken == null) {
                Log.w("GameRepository", "Token not found for user: $splitToUser. User may not be online or registered.")
                throw Exception("Пользователь $toUser не найден или не в сети")
            }
            
            // ВАЖНО: Проверяем, нет ли уже активного запроса от fromUser к toUser
            // Это предотвращает дублирование приглашений
            val existingRequestsSnapshot = myRef.child("users").child(splitToUser).child("request").get().await()
            var alreadyInvited = false
            existingRequestsSnapshot.children.forEach { child ->
                if (child.value.toString() == fromUser) {
                    alreadyInvited = true
                    Log.d("GameRepository", "User $fromUser already invited $toUser")
                }
            }
            
            if (alreadyInvited) {
                Log.d("GameRepository", "Skipping duplicate invitation from $fromUser to $toUser")
                return@runCatchingResult // Не выбрасываем ошибку, просто ничего не делаем
            }
            
            // Также проверяем, нет ли встречного приглашения (toUser уже приглашал fromUser)
            val reverseRequestsSnapshot = myRef.child("users").child(splitFromUser).child("request").get().await()
            var reverseInvitationExists = false
            reverseRequestsSnapshot.children.forEach { child ->
                if (child.value.toString() == toUser) {
                    reverseInvitationExists = true
                    Log.d("GameRepository", "Reverse invitation exists: $toUser already invited $fromUser")
                }
            }
            
            if (reverseInvitationExists) {
                Log.w("GameRepository", "Cannot send request: $toUser already invited $fromUser. Please accept their request instead.")
                throw Exception("$toUser уже пригласил вас. Примите их приглашение вместо отправки нового.")
            }
            
            Log.d("GameRepository", "Found token for user $splitToUser, sending game request")
            
            // Сохраняем запрос в БД
            myRef.child("users").child(splitToUser).child("request").push().setValue(fromUser).await()
            
            // Создаем sessionId заранее для создания сессии
            val sessionId = if (splitFromUser < splitToUser) "${splitFromUser}_${splitToUser}" else "${splitToUser}_${splitFromUser}"
            
            // ВАЖНО: Создаем и настраиваем игровую сессию сразу при отправке запроса
            // Это позволяет обоим игрокам видеть сессию в БД до принятия запроса
            // Также создаем начальную структуру для ходов (очищаем старые ходы если были)
            // firstPlayer = fromUser (отправитель запроса), он будет ходить первым и играть за X
            // player1 = fromUser (X), player2 = toUser (O)
            val sessionUpdates = mapOf(
                "firstPlayer" to fromUser,      // Отправитель запроса всегда первый (X)
                "currentTurn" to fromUser,     // Первый ход у отправителя запроса
                "player1" to fromUser,         // player1 = X
                "player2" to toUser,           // player2 = O
                "initialized" to true,
                "sessionCreated" to System.currentTimeMillis(),
                // Явно создаем пустые клетки 1-9 для игрового поля
                "1" to "",
                "2" to "",
                "3" to "",
                "4" to "",
                "5" to "",
                "6" to "",
                "7" to "",
                "8" to "",
                "9" to ""
            )
            myRef.child("PlayerOnline").child(sessionId).updateChildren(sessionUpdates).await()
            
            // Принудительно читаем только что записанные данные для подтверждения
            val verificationSnapshot = myRef.child("PlayerOnline").child(sessionId).get().await()
            val verifiedCurrentTurn = verificationSnapshot.child("currentTurn").value as? String ?: ""
            val verifiedFirstPlayer = verificationSnapshot.child("firstPlayer").value as? String ?: ""
            val verifiedPlayer1 = verificationSnapshot.child("player1").value as? String ?: ""
            val verifiedPlayer2 = verificationSnapshot.child("player2").value as? String ?: ""
            Log.d("GameRepository", "Verified after sendGameRequest setup:")
            Log.d("GameRepository", "  currentTurn=$verifiedCurrentTurn")
            Log.d("GameRepository", "  firstPlayer=$verifiedFirstPlayer")
            Log.d("GameRepository", "  player1=$verifiedPlayer1")
            Log.d("GameRepository", "  player2=$verifiedPlayer2")
            
            Log.d("GameRepository", "Game session created and setup in PlayerOnline/$sessionId")
            Log.d("GameRepository", "Initial game board with cells 1-9 created in database")
            
            // Сохраняем данные для FCM в отдельном узле для Cloud Function
            val fcmData = mapOf(
                "fromUser" to fromUser,
                "toUser" to toUser,
                "token" to recipientToken,
                "title" to "Запрос на игру",
                "body" to "$fromUser приглашает вас сыграть в крестики-нолики!",
                "timestamp" to System.currentTimeMillis()
            )
            myRef.child("fcm_queue").push().setValue(fcmData).await()
            Log.d("GameRepository", "FCM data saved to queue for Cloud Function processing")
        }
    }
    
    /**
     * Создание игровой сессии
     * @param sessionID Уникальный идентификатор сессии (комбинация имен игроков)
     */
    suspend fun createGameSession(sessionID: String): Result<Unit> {
        return runCatchingResult {
            // Добавляем логирование для отладки
            Log.d("GameRepository", "=== GAME SESSION CREATED ===")
            Log.d("GameRepository", "SessionID: $sessionID")
            Log.d("GameRepository", "=====================================")
            
            // ВАЖНО: Не удаляем сессию полностью, чтобы setupGameSession мог корректно установить данные
            // Просто убеждаемся что сессия существует в БД
            // setupGameSession установит player1, player2, firstPlayer, currentTurn заново
            val updates = mapOf(
                "initialized" to true,
                "sessionCreated" to System.currentTimeMillis()
            )
            myRef.child("PlayerOnline").child(sessionID).updateChildren(updates).await()
            
            Log.d("GameRepository", "Session initialized in database")
        }
    }
    
    /**
     * Подписание на обновления игровой сессии
     * Возвращает Flow с данными о ходах
     */
    fun observeGameSession(sessionID: String): Flow<Map<String, String>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val moves = mutableMapOf<String, String>()
                    snapshot.children.forEach { child ->
                        child.key?.let { key ->
                            moves[key] = child.value.toString()
                        }
                    }
                    trySend(moves)
                } catch (e: Exception) {
                    println("observeGameSession error: $e")
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeGameSession cancelled: ${error.message}")
            }
        }
        
        myRef.child("PlayerOnline").child(sessionID).addValueEventListener(listener)
        
        awaitClose {
            myRef.child("PlayerOnline").child(sessionID).removeEventListener(listener)
        }
    }
    
    /**
     * Наблюдение за входящими запросами на игру (возвращает email отправителя)
     */
    fun observeGameRequests(userEmail: String): Flow<String> = callbackFlow {
        val splitEmail = userEmail.substringBefore("@")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val td = snapshot.value as? HashMap<String, Any>
                    if (td != null && td.isNotEmpty()) {
                        // Берем первый запрос
                        val requesterEmail = td.values.firstOrNull() as? String
                        if (requesterEmail != null) {
                            trySend(requesterEmail)
                        }
                    }
                } catch (e: Exception) {
                    println("observeGameRequests error: $e")
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeGameRequests cancelled: ${error.message}")
            }
        }
        
        myRef.child("users").child(splitEmail).child("request").addValueEventListener(listener)
        
        awaitClose {
            myRef.child("users").child(splitEmail).child("request").removeEventListener(listener)
        }
    }
    
    /**
     * Очистка запроса пользователя после обработки
     */
    suspend fun clearGameRequest(userEmail: String): Result<Unit> {
        return runCatchingResult {
            val splitEmail = userEmail.substringBefore("@")
            myRef.child("users").child(splitEmail).child("request").removeValue().await()
        }
    }

    /**
     * Прослушивание входящих запросов на игру
     */
    fun observeIncomingRequests(userEmail: String): Flow<List<String>> = callbackFlow {
        val splitEmail = userEmail.substringBefore("@")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val requests = mutableListOf<String>()
                    snapshot.children.forEach { child ->
                        child.value?.toString()?.let { requests.add(it) }
                    }
                    trySend(requests)
                } catch (e: Exception) {
                    println("observeIncomingRequests error: $e")
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeIncomingRequests cancelled: ${error.message}")
            }
        }
        
        myRef.child("users").child(splitEmail).child("request").addValueEventListener(listener)
        
        awaitClose {
            myRef.child("users").child(splitEmail).child("request").removeEventListener(listener)
        }
    }
    
    /**
     * Получение профиля пользователя (URL аватара)
     * Возвращает URL изображения или null если не найдено
     */
    suspend fun getUserProfileImage(email: String): Result<String?> {
        return runCatchingResult {
            val splitEmail = email.substringBefore("@")
            val snapshot = myRef.child("users").child(splitEmail).child("profileImageUrl").get().await()
            snapshot.value as? String
        }
    }

    
    /**
     * Очистка запросов пользователя после обработки
     */
    suspend fun clearUserRequests(userEmail: String): Result<Unit> {
        return runCatchingResult {
            val splitEmail = userEmail.substringBefore("@")
            myRef.child("users").child(splitEmail).child("request").removeValue().await()
        }
    }
    
    /**
     * Наблюдение за состоянием доски (возвращает список ходов)
     */
    fun observeBoardState(sessionID: String): Flow<List<String?>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    // Создаем массив из 9 элементов для доски 3x3
                    val board = Array<String?>(9) { null }
                    
                    // Получаем информацию об игроках для определения символов
                    val player1Snapshot = snapshot.child("player1").value as? String ?: ""
                    val player2Snapshot = snapshot.child("player2").value as? String ?: ""
                    
                    Log.d("GameRepository", "observeBoardState: player1=$player1Snapshot, player2=$player2Snapshot")
                    
                    // Определяем первого игрока (кто ходит первым) - он всегда X
                    val firstPlayer = snapshot.child("firstPlayer").value as? String ?: player1Snapshot
                    
                    snapshot.children.forEach { child ->
                        val key = child.key
                        // Пропускаем служебные ключи (firstPlayer, currentTurn, player1, player2 и т.д.)
                        if (key != null && key.toIntOrNull() != null) {
                            val index = key.toInt()
                            if (index in 1..9) {
                                val value = child.value.toString()
                                
                                // Если клетка пустая - пропускаем
                                if (value.isBlank()) {
                                    board[index - 1] = ""
                                    return@forEach
                                }
                                
                                // Определяем символ на основе email игрока
                                // ВАЖНО: firstPlayer всегда получает X, второй игрок получает O
                                val symbol = when {
                                    value == firstPlayer -> "X"
                                    value == player1Snapshot && player1Snapshot != firstPlayer -> "O"
                                    value == player2Snapshot -> {
                                        if (player2Snapshot == firstPlayer) "X" else "O"
                                    }
                                    value == "X" -> "X"
                                    value == "O" -> "O"
                                    else -> {
                                        // Fallback: если не можем определить, оставляем как есть
                                        Log.w(
                                            "GameRepository",
                                            "Unknown value in cell $index: '$value' (firstPlayer=$firstPlayer, player1=$player1Snapshot, player2=$player2Snapshot)"
                                        )
                                        // Пытаемся определить по текущему ходу
                                        val currentTurn = snapshot.child("currentTurn").value as? String ?: ""
                                        if (value == currentTurn) {
                                            // Это только что сделанный ход - определяем по firstPlayer
                                            if (firstPlayer == value) "X" else "O"
                                        } else {
                                            value
                                        }
                                    }
                                }
                                board[index - 1] = symbol
                                Log.d(
                                    "GameRepository",
                                    "observeBoardState: cell $index = '$value' -> symbol '$symbol'"
                                )
                            }
                        }
                    }
                    
                    Log.d("GameRepository", "observeBoardState: sending board state: ${board.toList()}")
                    trySend(board.toList())
                } catch (e: Exception) {
                    println("observeBoardState error: $e")
                    trySend(emptyList())
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeBoardState cancelled: ${error.message}")
                trySend(emptyList())
            }
        }
        
        myRef.child("PlayerOnline").child(sessionID).addValueEventListener(listener)
        
        awaitClose {
            myRef.child("PlayerOnline").child(sessionID).removeEventListener(listener)
        }
    }
    
    /**
     * Получение текущего хода (чей сейчас ход)
     */
    suspend fun getCurrentTurn(sessionID: String): Result<String> {
        return runCatchingResult {
            // Логика определения текущего игрока может быть расширена
            // Сейчас возвращаем заглушку - в реальном приложении нужно хранить turn в базе
            val snapshot = myRef.child("PlayerOnline").child(sessionID).child("currentTurn").get().await()
            snapshot.value as? String ?: ""
        }
    }
    
    /**
     * Получение первого игрока в сессии (для определения символа X/O)
     */
    suspend fun getFirstPlayer(sessionID: String): Result<String> {
        return runCatchingResult {
            val snapshot = myRef.child("PlayerOnline").child(sessionID).child("firstPlayer").get().await()
            snapshot.value as? String ?: ""
        }
    }
    
    /**
     * Наблюдение за выходом соперника
     */
    fun observeOpponentLeft(sessionID: String): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    // Проверяем, существует ли节点 opponentLeft или аналогичный флаг
                    val opponentLeftSnapshot = snapshot.child("opponentLeft")
                    val hasLeft = opponentLeftSnapshot.exists() && opponentLeftSnapshot.value == true
                    trySend(hasLeft)
                } catch (e: Exception) {
                    println("observeOpponentLeft error: $e")
                    trySend(false)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeOpponentLeft cancelled: ${error.message}")
                trySend(false)
            }
        }
        
        myRef.child("PlayerOnline").child(sessionID).addValueEventListener(listener)
        
        awaitClose {
            myRef.child("PlayerOnline").child(sessionID).removeEventListener(listener)
        }
    }
    
    /**
     * Перезапуск игры (очистка доски с сохранением игроков)
     */
    suspend fun restartGame(sessionID: String): Result<Unit> {
        return runCatchingResult {
            // Получаем текущих игроков перед очисткой
            val player1Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player1").get().await()
            val player2Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player2").get().await()
            val firstPlayerSnapshot = myRef.child("PlayerOnline").child(sessionID).child("firstPlayer").get().await()
            
            val player1 = player1Snapshot.value as? String ?: ""
            val player2 = player2Snapshot.value as? String ?: ""
            val firstPlayer = firstPlayerSnapshot.value as? String ?: player1
            
            // Очищаем только ходы (клетки 1-9), но сохраняем информацию об игроках
            for (i in 1..9) {
                myRef.child("PlayerOnline").child(sessionID).child(i.toString()).removeValue().await()
            }
            
            // Восстанавливаем currentTurn - ход переходит к первому игроку
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(firstPlayer).await()
            
            Log.d("GameRepository", "Game restarted: player1=$player1, player2=$player2, firstPlayer=$firstPlayer, currentTurn=$firstPlayer")
        }
    }
    
    /**
     * Настройка игровой сессии после создания
     * Устанавливает первого игрока, текущий ход и имена игроков
     * Также гарантирует наличие пустого игрового поля (клетки 1-9)
     * @param player1 Email первого игрока (отправитель запроса, всегда X)
     * @param player2 Email второго игрока (принявший запрос, всегда O)
     */
    suspend fun setupGameSession(sessionID: String, player1: String, player2: String): Result<Unit> {
        return runCatchingResult {
            // Используем updateChildren для атомарной установки всех полей
            // Это гарантирует что все данные будут записаны одновременно
            // ВАЖНО: player1 = отправитель запроса = firstPlayer = X
            //        player2 = принявший запрос = второй игрок = O
            val updates = mapOf(
                "firstPlayer" to player1,      // Отправитель запроса всегда первый (X)
                "currentTurn" to player1,     // Первый ход у отправителя запроса
                "player1" to player1,         // player1 = X
                "player2" to player2,         // player2 = O
                "initialized" to true,
                // Явно создаем пустые клетки 1-9 для игрового поля
                // Это гарантирует что поле существует даже если было удалено
                "1" to "",
                "2" to "",
                "3" to "",
                "4" to "",
                "5" to "",
                "6" to "",
                "7" to "",
                "8" to "",
                "9" to ""
            )
            
            myRef.child("PlayerOnline").child(sessionID).updateChildren(updates).await()
            
            // Добавляем логирование для отладки
            Log.d("GameRepository", "=== GAME SESSION SETUP ===")
            Log.d("GameRepository", "SessionID: $sessionID")
            Log.d("GameRepository", "player1 (X, first): $player1")
            Log.d("GameRepository", "player2 (O, second): $player2")
            Log.d("GameRepository", "firstPlayer/currentTurn: $player1")
            Log.d("GameRepository", "Game board cells 1-9 initialized")
            Log.d("GameRepository", "=========================")
            
            // Принудительно читаем только что записанные данные для подтверждения
            val verificationSnapshot = myRef.child("PlayerOnline").child(sessionID).get().await()
            val verifiedCurrentTurn = verificationSnapshot.child("currentTurn").value as? String ?: ""
            val verifiedFirstPlayer = verificationSnapshot.child("firstPlayer").value as? String ?: ""
            val verifiedPlayer1 = verificationSnapshot.child("player1").value as? String ?: ""
            val verifiedPlayer2 = verificationSnapshot.child("player2").value as? String ?: ""
            Log.d("GameRepository", "Verified after setupGameSession:")
            Log.d("GameRepository", "  currentTurn=$verifiedCurrentTurn")
            Log.d("GameRepository", "  firstPlayer=$verifiedFirstPlayer")
            Log.d("GameRepository", "  player1=$verifiedPlayer1")
            Log.d("GameRepository", "  player2=$verifiedPlayer2")
        }
    }
    
    /**
     * Совершение хода в игре
     * @param sessionID Уникальный идентификатор сессии
     * @param cellId Индекс клетки (1-9) - как хранится в БД
     * @param playerEmail Email игрока
     * @param symbol Символ игрока (X или O)
     */
    suspend fun makeMove(sessionID: String, cellId: Int, playerEmail: String, symbol: String): Result<Unit> {
        return runCatchingResult {
            Log.d("GameRepository", "=== MAKING MOVE ===")
            Log.d(
                "GameRepository",
                "SessionID: $sessionID, CellId: $cellId, Player: $playerEmail, Symbol: $symbol"
            )

            // Проверяем текущий ход ПЕРЕД записью
            val currentTurnSnapshot = myRef.child("PlayerOnline").child(sessionID).child("currentTurn").get().await()
            val currentPlayer = currentTurnSnapshot.value as? String ?: ""
            Log.d("GameRepository", "Current turn before move: $currentPlayer")

            if (currentPlayer != playerEmail) {
                Log.w(
                    "GameRepository",
                    "Attempted move by $playerEmail but it's $currentPlayer's turn!"
                )
                throw Exception("Сейчас не ваш ход! Ожидается ход от $currentPlayer")
            }
            
            // Проверяем, не занята ли клетка
            val cellSnapshot = myRef.child("PlayerOnline").child(sessionID).child(cellId.toString()).get().await()
            val cellValue = cellSnapshot.value as? String
            if (!cellValue.isNullOrBlank()) {
                Log.w("GameRepository", "Cell $cellId is already occupied by $cellValue")
                throw Exception("Клетка уже занята!")
            }

            // Сохраняем email игрока в базу (Firebase использует ключи 1-9)
            // observeBoardState затем преобразует email в символ (X/O) на основе player1/player2
            myRef.child("PlayerOnline").child(sessionID).child(cellId.toString()).setValue(playerEmail).await()
            
            // Переключаем текущий ход на следующего игрока
            val player1Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player1").get().await()
            val player1 = player1Snapshot.value as? String ?: ""
            
            val player2Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player2").get().await()
            val player2 = player2Snapshot.value as? String ?: ""
            
            val nextPlayer = if (currentPlayer == player1) player2 else player1
            
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(nextPlayer).await()
            Log.d("GameRepository", "Move made at cell $cellId by $playerEmail ($symbol), switched turn from $currentPlayer to $nextPlayer")
        }
    }
    
    /**
     * Обновление состояния доски (устаревший метод, используется makeMove)
     * После обновления автоматически переключает currentTurn на следующего игрока
     */
    suspend fun updateBoardState(sessionID: String, board: List<String>): Result<Unit> {
        return runCatchingResult {
            // Получаем текущего игрока из локальных данных (передается из ViewModel)
            // Этот метод оставлен для обратной совместимости, но лучше использовать makeMove
            val currentTurnResult = getCurrentTurn(sessionID)
            val currentPlayer = if (currentTurnResult is Result.Success) currentTurnResult.data else ""
            
            val player1Result = getPlayer1(sessionID)
            val player1 = if (player1Result is Result.Success) player1Result.data else ""
            
            val player2Result = getPlayer2(sessionID)
            val player2 = if (player2Result is Result.Success) player2Result.data else ""
            
            val nextPlayer = if (currentPlayer == player1) player2 else player1
            
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(nextPlayer).await()
            Log.d("GameRepository", "Board state updated, switched turn from $currentPlayer to $nextPlayer")
        }
    }
    
    /**
     * Получение первого игрока из сессии
     */
    suspend fun getPlayer1(sessionID: String): Result<String> {
        return runCatchingResult {
            val snapshot = myRef.child("PlayerOnline").child(sessionID).child("player1").get().await()
            snapshot.value as? String ?: ""
        }
    }
    
    /**
     * Получение второго игрока из сессии
     */
    suspend fun getPlayer2(sessionID: String): Result<String> {
        return runCatchingResult {
            val snapshot = myRef.child("PlayerOnline").child(sessionID).child("player2").get().await()
            snapshot.value as? String ?: ""
        }
    }
    
    /**
     * Наблюдение за изменениями текущего хода в реальном времени
     */
    fun observeCurrentTurn(sessionID: String): Flow<String> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    // Наблюдаем за всем узлом сессии, а не только за currentTurn
                    // Это гарантирует что мы получим обновление когда currentTurn изменится
                    val currentTurnSnapshot = snapshot.child("currentTurn")
                    val currentTurn = currentTurnSnapshot.value as? String ?: ""
                    Log.d("GameRepository", "observeCurrentTurn: currentTurn=$currentTurn for session=$sessionID")
                    trySend(currentTurn)
                } catch (e: Exception) {
                    println("observeCurrentTurn error: $e")
                    trySend("")
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeCurrentTurn cancelled: ${error.message}")
                trySend("")
            }
        }
        
        // Слушаем конкретный child "currentTurn" для более эффективного обновления
        myRef.child("PlayerOnline").child(sessionID).child("currentTurn").addValueEventListener(listener)
        
        awaitClose {
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").removeEventListener(listener)
        }
    }
    
    /**
     * Получение полной информации о сессии
     */
    suspend fun getSessionInfo(sessionID: String): Result<SessionInfo> {
        return runCatchingResult {
            val snapshot = myRef.child("PlayerOnline").child(sessionID).get().await()
            val firstPlayer = snapshot.child("firstPlayer").value as? String ?: ""
            val currentTurn = snapshot.child("currentTurn").value as? String ?: ""
            val player1 = snapshot.child("player1").value as? String ?: ""
            val player2 = snapshot.child("player2").value as? String ?: ""
            
            SessionInfo(
                firstPlayer = firstPlayer,
                currentTurn = currentTurn,
                player1 = player1,
                player2 = player2
            )
        }
    }
    
    /**
     * Информация о сессии
     */
    data class SessionInfo(
        val firstPlayer: String,
        val currentTurn: String,
        val player1: String,
        val player2: String
    )
    
    /**
     * Получение имен игроков из сессии
     */
    suspend fun getPlayerNames(sessionID: String): Result<Pair<String, String>> {
        return runCatchingResult {
            val player1Result = getPlayer1(sessionID)
            val player2Result = getPlayer2(sessionID)
            
            val player1 = if (player1Result is Result.Success) player1Result.data else ""
            val player2 = if (player2Result is Result.Success) player2Result.data else ""
            
            Pair(player1, player2)
        }
    }
}
