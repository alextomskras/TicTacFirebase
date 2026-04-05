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
     * Получает токен получателя из БД и сохраняет данные для отправки FCM
     * 
     * ВАЖНО: Прямая отправка FCM на токен получателя невозможна с клиента без сервера.
     * Для реальной отправки уведомлений нужно использовать:
     * 1. Firebase Cloud Functions - триггер на запись в БД (рекомендуется)
     * 2. Firebase Admin SDK на бэкенде
     * 3. HTTP v1 API с сервисным аккаунтом (требует серверной авторизации)
     * 
     * В данном примере мы сохраняем запрос в БД, а Cloud Function должен отправить FCM
     */
    suspend fun sendGameRequest(fromUser: String, toUser: String): Result<Unit> {
        return runCatchingResult {
            val splitToUser = toUser.substringBefore("@")
            
            // Проверяем существование пользователя и получаем его токен
            val tokenSnapshot = myRef.child("users").child(splitToUser).child("newToken").get().await()
            val recipientToken = tokenSnapshot.value as? String
            
            if (recipientToken == null) {
                Log.w("GameRepository", "Token not found for user: $splitToUser. User may not be online or registered.")
            } else {
                Log.d("GameRepository", "Found token for user $splitToUser, will send FCM via Cloud Function")
            }
            
            // Сохраняем запрос в БД
            // Cloud Function должен следить за изменениями в /users/{userId}/request/
            // и отправлять FCM уведомление при появлении нового запроса
            myRef.child("users").child(splitToUser).child("request").push().setValue(fromUser).await()
            
            // Также сохраняем данные для FCM в отдельном узле для Cloud Function
            if (recipientToken != null) {
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
    }
    
    /**
     * Создание игровой сессии
     * @param sessionID Уникальный идентификатор сессии (комбинация имен игроков)
     */
    suspend fun createGameSession(sessionID: String): Result<Unit> {
        return runCatchingResult {
            // Очищаем только нашу сессию, а не все PlayerOnline
            myRef.child("PlayerOnline").child(sessionID).removeValue().await()
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
     * Совершение хода в игре
     */
    suspend fun makeMove(sessionID: String, cellId: Int, playerEmail: String): Result<Unit> {
        return runCatchingResult {
            myRef.child("PlayerOnline").child(sessionID).child(cellId.toString()).setValue(playerEmail).await()
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
            myRef.child("users").child(userEmail).child("request").setValue(true).await()
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
            myRef.child("users").child(splitEmail).child("request").setValue(true).await()
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
                    
                    snapshot.children.forEach { child ->
                        val key = child.key
                        // Пропускаем служебные ключи (firstPlayer, currentTurn, player1, player2 и т.д.)
                        if (key != null && key.toIntOrNull() != null) {
                            val index = key.toInt()
                            if (index in 1..9) {
                                board[index - 1] = child.value.toString()
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
     * Перезапуск игры (очистка доски)
     */
    suspend fun restartGame(sessionID: String): Result<Unit> {
        return runCatchingResult {
            // Очищаем все ходы в сессии
            myRef.child("PlayerOnline").child(sessionID).removeValue().await()
            // Можно добавить установку флага currentTurn и firstPlayer заново
        }
    }
    
    /**
     * Настройка игровой сессии после создания
     * Устанавливает первого игрока, текущий ход и имена игроков
     */
    suspend fun setupGameSession(sessionID: String, player1: String, player2: String): Result<Unit> {
        return runCatchingResult {
            // Первый игрок получает "X" и ходит первым
            myRef.child("PlayerOnline").child(sessionID).child("firstPlayer").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("player1").setValue(player1).await()
            myRef.child("PlayerOnline").child(sessionID).child("player2").setValue(player2).await()
            Log.d("GameRepository", "Game session setup: firstPlayer=$player1, currentTurn=$player1")
        }
    }
    
    /**
     * Совершение хода в игре
     * @param sessionID Уникальный идентификатор сессии
     * @param cellIndex Индекс клетки (0-8)
     * @param playerEmail Email игрока
     * @param symbol Символ игрока (X или O)
     */
    suspend fun makeMove(sessionID: String, cellIndex: Int, playerEmail: String, symbol: String): Result<Unit> {
        return runCatchingResult {
            // Сохраняем ход в базу (Firebase использует ключи 1-9)
            myRef.child("PlayerOnline").child(sessionID).child((cellIndex + 1).toString()).setValue(symbol).await()
            
            // Переключаем текущий ход на следующего игрока
            val currentTurnSnapshot = myRef.child("PlayerOnline").child(sessionID).child("currentTurn").get().await()
            val currentPlayer = currentTurnSnapshot.value as? String ?: ""
            
            val player1Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player1").get().await()
            val player1 = player1Snapshot.value as? String ?: ""
            
            val player2Snapshot = myRef.child("PlayerOnline").child(sessionID).child("player2").get().await()
            val player2 = player2Snapshot.value as? String ?: ""
            
            val nextPlayer = if (currentPlayer == player1) player2 else player1
            
            myRef.child("PlayerOnline").child(sessionID).child("currentTurn").setValue(nextPlayer).await()
            Log.d("GameRepository", "Move made at cell $cellIndex by $playerEmail ($symbol), switched turn from $currentPlayer to $nextPlayer")
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
