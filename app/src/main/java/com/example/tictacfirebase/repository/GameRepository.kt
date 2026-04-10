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
            // Очищаем только нашу сессию, а не все PlayerOnline
            // Это удаляет старые ходы, но setupGameSession установит player1, player2, firstPlayer, currentTurn заново
            myRef.child("PlayerOnline").child(sessionID).removeValue().await()
            
            // Добавляем логирование для отладки
            Log.d("GameRepository", "=== GAME SESSION CREATED/CLEARED ===")
            Log.d("GameRepository", "SessionID: $sessionID")
            Log.d("GameRepository", "=====================================")
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
                    
                    snapshot.children.forEach { child ->
                        val key = child.key
                        // Пропускаем служебные ключи (firstPlayer, currentTurn, player1, player2 и т.д.)
                        if (key != null && key.toIntOrNull() != null) {
                            val index = key.toInt()
                            if (index in 1..9) {
                                val value = child.value.toString()
                                // Определяем символ на основе email игрока
                                val symbol = when {
                                    value == player1Snapshot -> "X"
                                    value == player2Snapshot -> "O"
                                    value == "X" || value == "O" -> value // Уже сохранен как символ
                                    else -> value
                                }
                                board[index - 1] = symbol
                            }
                        }
                    }
                    
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
     */
    suspend fun setupGameSession(sessionID: String, player1: String, player2: String): Result<Unit> {
        return runCatchingResult {
            // Первый игрок (player1) получает "X" и ходит первым
            myRef.child("PlayerOnline").child(sessionID).child("firstPlayer").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("player1").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("player2").setValue(player2).await()
            
            // Добавляем логирование для отладки
            Log.d("GameRepository", "=== GAME SESSION SETUP ===")
            Log.d("GameRepository", "SessionID: $sessionID")
            Log.d("GameRepository", "player1 (X): $player1")
            Log.d("GameRepository", "player2 (O): $player2")
            Log.d("GameRepository", "firstPlayer/currentTurn: $player1")
            Log.d("GameRepository", "=========================")
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
            // Сохраняем email игрока в базу (Firebase использует ключи 1-9)
            // observeBoardState затем преобразует email в символ (X/O) на основе player1/player2
            myRef.child("PlayerOnline").child(sessionID).child(cellId.toString()).setValue(playerEmail).await()
            
            // Переключаем текущий ход на следующего игрока
            val currentTurnSnapshot = myRef.child("PlayerOnline").child(sessionID).child("currentTurn").get().await()
            val currentPlayer = currentTurnSnapshot.value as? String ?: ""
            
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
                    val currentTurnSnapshot = snapshot.child("currentTurn")
                    val currentTurn = currentTurnSnapshot.value as? String ?: ""
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
        
        myRef.child("PlayerOnline").child(sessionID).addValueEventListener(listener)
        
        awaitClose {
            myRef.child("PlayerOnline").child(sessionID).removeEventListener(listener)
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
