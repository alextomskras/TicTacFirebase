package com.example.tictacfirebase.repository

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
     */
    suspend fun sendGameRequest(fromUser: String, toUser: String): Result<Unit> {
        return runCatchingResult {
            val splitToUser = toUser.substringBefore("@")
            myRef.child("users").child(splitToUser).child("request").push().setValue(fromUser).await()
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
                        child.key?.toIntOrNull()?.let { index ->
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
     * Обновление состояния доски
     */
    suspend fun updateBoardState(sessionID: String, board: List<String>): Result<Unit> {
        return runCatchingResult {
            // Обновляем каждый элемент доски
            board.forEachIndexed { index, value ->
                if (value.isNotEmpty()) {
                    myRef.child("PlayerOnline").child(sessionID).child((index + 1).toString()).setValue(value).await()
                }
            }
        }
    }
    
    /**
     * Получение имен игроков из сессии
     */
    suspend fun getPlayerNames(sessionID: String): Result<Pair<String, String>> {
        return runCatchingResult {
            // В реальном приложении нужно хранить имена игроков в сессии
            // Здесь заглушка - нужно доработать структуру данных
            val snapshot = myRef.child("PlayerOnline").child(sessionID).get().await()
            // Извлекаем имена из sessionID (формат: player1player2)
            // Это упрощенная логика, лучше хранить явно в базе
            Pair("", "")
        }
    }
}
