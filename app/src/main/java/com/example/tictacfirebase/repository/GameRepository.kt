package com.example.tictacfirebase.repository

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
    suspend fun updateUserToken(userId: String, token: String) {
        myRef.child("users").child(userId).child("newToken").setValue(token).await()
    }
    
    /**
     * Отправка запроса на игру другому пользователю
     */
    suspend fun sendGameRequest(fromUser: String, toUser: String) {
        val splitToUser = toUser.substringBefore("@")
        myRef.child("users").child(splitToUser).child("request").push().setValue(fromUser).await()
    }
    
    /**
     * Создание игровой сессии
     * @param sessionID Уникальный идентификатор сессии (комбинация имен игроков)
     */
    suspend fun createGameSession(sessionID: String) {
        // Очищаем только нашу сессию, а не все PlayerOnline
        myRef.child("PlayerOnline").child(sessionID).removeValue().await()
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
    suspend fun makeMove(sessionID: String, cellId: Int, playerEmail: String) {
        myRef.child("PlayerOnline").child(sessionID).child(cellId.toString()).setValue(playerEmail).await()
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
     */
    fun observeUserProfile(email: String): Flow<String?> = callbackFlow {
        val splitEmail = email.substringBefore("@")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val profileImageUrl = snapshot.child("profileImageUrl").value as? String
                    trySend(profileImageUrl)
                } catch (e: Exception) {
                    println("observeUserProfile error: $e")
                    trySend(null)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                println("observeUserProfile cancelled: ${error.message}")
                trySend(null)
            }
        }
        
        myRef.child("users").child(splitEmail).addValueEventListener(listener)
        
        awaitClose {
            myRef.child("users").child(splitEmail).removeEventListener(listener)
        }
    }
    
    /**
     * Очистка запросов пользователя после обработки
     */
    suspend fun clearUserRequests(userEmail: String) {
        val splitEmail = userEmail.substringBefore("@")
        myRef.child("users").child(splitEmail).child("request").setValue(true).await()
    }
}
