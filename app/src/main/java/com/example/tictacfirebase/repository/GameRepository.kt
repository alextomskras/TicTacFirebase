package com.example.tictacfirebase.repository

import android.util.Log
import com.example.tictacfirebase.utils.Result
import com.example.tictacfirebase.utils.asCanonicalPlayer
import com.example.tictacfirebase.utils.runCatchingResult
import com.example.tictacfirebase.utils.samePlayer
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutablePostData
import com.google.firebase.database.Transaction
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
    suspend fun sendGameRequest(fromUserRaw: String, toUserRaw: String): Result<Unit> {
        return runCatchingResult {
            // КАНОН ИДЕНТИФИКАТОРОВ: email приводим к нижнему регистру/trim ОДИН раз здесь,
            // дальше по коду используем только канонические значения.
            // users/* адресуется логином (substringBefore("@").asCanonicalPlayer()), а клетки и currentTurn — полным email.
            val fromUser = fromUserRaw.asCanonicalPlayer()
            val toUser = toUserRaw.asCanonicalPlayer()
            val splitToUser = toUser.substringBefore("@").asCanonicalPlayer()
            val splitFromUser = fromUser.substringBefore("@").asCanonicalPlayer()
            
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
            
            if (samePlayer(fromUser, toUser)) {
                throw Exception("Нельзя пригласить самого себя")
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
        val splitEmail = userEmail.substringBefore("@").asCanonicalPlayer()
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
            val splitEmail = userEmail.substringBefore("@").asCanonicalPlayer()
            myRef.child("users").child(splitEmail).child("request").removeValue().await()
        }
    }

    /**
     * Прослушивание входящих запросов на игру
     */
    fun observeIncomingRequests(userEmail: String): Flow<List<String>> = callbackFlow {
        val splitEmail = userEmail.substringBefore("@").asCanonicalPlayer()
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
            val splitEmail = email.substringBefore("@").asCanonicalPlayer()
            val snapshot = myRef.child("users").child(splitEmail).child("profileImageUrl").get().await()
            snapshot.value as? String
        }
    }

    
    /**
     * Очистка запросов пользователя после обработки
     */
    suspend fun clearUserRequests(userEmail: String): Result<Unit> {
        return runCatchingResult {
            val splitEmail = userEmail.substringBefore("@").asCanonicalPlayer()
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
                    
                    // Читаем все значения из snapshot в Map для удобного доступа
                    val allData = mutableMapOf<String, String>()
                    snapshot.children.forEach { child ->
                        child.key?.let { key ->
                            allData[key] = child.value.toString()
                        }
                    }
                    
                    // Определяем первого игрока (кто ходит первым) - он всегда X
                    val firstPlayer = allData["firstPlayer"] ?: ""
                    val player1 = allData["player1"] ?: ""
                    val player2 = allData["player2"] ?: ""
                    
                    Log.d("GameRepository", "observeBoardState: firstPlayer=$firstPlayer, player1=$player1, player2=$player2")
                    
                    // Если firstPlayer пустой - значит данные ещё не готовы, выходим
                    if (firstPlayer.isEmpty()) {
                        Log.w("GameRepository", "observeBoardState: firstPlayer is empty, data not ready yet")
                        trySend(board.toList())
                        return
                    }
                    
                    // Обрабатываем клетки 1-9
                    for (i in 1..9) {
                        val cellValue = allData[i.toString()] ?: ""
                        
                        // Если клетка пустая - оставляем null
                        if (cellValue.isBlank()) {
                            board[i - 1] = null
                            Log.d("GameRepository", "observeBoardState: cell $i is empty")
                            continue
                        }
                        
                        // Сравниваем email в клетке с игроками в ЕДИНОМ каноне (trim+lowercase).
                        // Если email == firstPlayer → это X, иначе если совпадает со вторым игроком → O.
                        // ВАЖНО: раньше любое несовпадение трактовалось как "O", из-за чего
                        // ходы одного игрока перекрашивались и выглядели как захват всего поля.
                        val secondPlayer = if (samePlayer(firstPlayer, player1)) player2 else player1
                        val symbol = when {
                            samePlayer(cellValue, firstPlayer) -> "X"
                            samePlayer(cellValue, secondPlayer) -> "O"
                            else -> {
                                Log.w("GameRepository", "observeBoardState: cell $i value '$cellValue' matches neither player, ignoring")
                                null
                            }
                        }

                        board[i - 1] = symbol
                        Log.d(
                            "GameRepository",
                            "observeBoardState: cell $i = '$cellValue' -> symbol '$symbol' (firstPlayer=$firstPlayer)"
                        )
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
            val sessionRef = myRef.child("PlayerOnline").child(sessionID)

            val playersSnapshot = sessionRef.get().await()
            val player1 = (playersSnapshot.child("player1").value as? String ?: "").asCanonicalPlayer()
            val player2 = (playersSnapshot.child("player2").value as? String ?: "").asCanonicalPlayer()
            val firstPlayerRaw = playersSnapshot.child("firstPlayer").value as? String
            val firstPlayer = (if (firstPlayerRaw.isNullOrBlank()) player1 else firstPlayerRaw).asCanonicalPlayer()

            if (player1.isEmpty() || player2.isEmpty()) {
                throw Exception("Сессия не инициализирована: нет данных об игроках")
            }

            // АТОМАРНЫЙ перезапуск: раньше клетки очищались девятью последовательными
            // removeValue(), и между ними любой из клиентов мог записать ход в уже
            // очищенную клетку. updateChildren выполняет все записи одной
            // операцией на сервере. Ключи клеток в БД — "1".."9" (1-based), как и в makeMove().
            val updates = mutableMapOf<String, Any>(
                "currentTurn" to firstPlayer,
                "firstPlayer" to firstPlayer,
                "player1" to player1,
                "player2" to player2,
                "initialized" to true
            )
            for (i in 1..9) {
                updates[i.toString()] = ""
            }

            sessionRef.updateChildren(updates).await()

            Log.d("GameRepository", "Game restarted atomically: player1=$player1, player2=$player2, firstPlayer=$firstPlayer, currentTurn=$firstPlayer")
        }
    }
    
    /**
     * Настройка игровой сессии после создания
     * Устанавливает первого игрока, текущий ход и имена игроков
     * Также гарантирует наличие пустого игрового поля (клетки 1-9)
     * @param player1 Email первого игрока (отправитель запроса, всегда X)
     * @param player2 Email второго игрока (принявший запрос, всегда O)
     */
    suspend fun setupGameSession(sessionID: String, player1Raw: String, player2Raw: String): Result<Unit> {
        return runCatchingResult {
            // КАНОН ИДЕНТИФИКАТОРОВ: значения, которые пишутся в currentTurn/firstPlayer/
            // player1/player2 и позже сравниваются со значениями клеток, приводим к одному канону.
            val player1 = player1Raw.asCanonicalPlayer()
            val player2 = player2Raw.asCanonicalPlayer()

            if (player1.isEmpty() || player2.isEmpty()) {
                throw Exception("Некорректные данные игроков для сессии")
            }
            if (samePlayer(player1, player2)) {
                throw Exception("Нельзя создать сессию против самого себя")
            }

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
     * Проверка, что партия в узле сессии уже завершена (кто-то выиграл или ничья).
     * Используется внутри транзакции хода, чтобы после победы нельзя было
     * «дотыкать» оставшиеся клетки.
     *
     * Клетки в БД — 1-based ключи "1".."9"; здесь они мапятся в список индексов ходов,
     * а победные комбинации заданы в той же 1-based нумерации (как в GameManager и AppConstants.CellIds).
     */
    private fun isFinished(node: MutablePostData, player1: String, player2: String): Boolean {
        val winningCombinations = listOf(
            listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), // ряды
            listOf(1, 4, 7), listOf(2, 5, 8), listOf(3, 6, 9), // колонки
            listOf(1, 5, 9), listOf(3, 5, 7)                    // диагонали
        )

        val p1Moves = mutableListOf<Int>()
        val p2Moves = mutableListOf<Int>()
        var totalMoves = 0

        for (i in 1..9) {
            val owner = (node.child(i.toString()).getValue(String::class.java) ?: "").asCanonicalPlayer()
            if (owner.isEmpty()) continue
            totalMoves++
            when {
                samePlayer(owner, player1) -> p1Moves.add(i)
                samePlayer(owner, player2) -> p2Moves.add(i)
                else -> {
                    // Клетка принадлежит неизвестному игроку — данные повреждены,
                    // сочтём партию завершённой, чтобы не давали делать новые ходы.
                    Log.w("GameRepository", "isFinished: cell $i owned by unknown player '$owner'")
                    return true
                }
            }
        }

        val finished = winningCombinations.any { p1Moves.containsAll(it) } ||
                winningCombinations.any { p2Moves.containsAll(it) } ||
                totalMoves >= 9

        if (finished) {
            Log.d("GameRepository", "isFinished: game already over (p1=$p1Moves, p2=$p2Moves)")
        }
        return finished
    }

    /**
     * Совершение хода в игре (атомарно через Firebase Transaction)
     * @param sessionID Уникальный идентификатор сессии (комнаты)
     * @param cellId Номер клетки 1-9 — именно под такими ключами ходы хранятся в БД
     *        (в UI/ViewModel доска — 0-based массив boardState[0..8], конверсия выполняется в ViewModel)
     * @param playerEmail Email игрока, заявляющего ход
     * @param symbol Символ игрока (X или O) — используется только для логов
     */
    suspend fun makeMove(sessionID: String, cellId: Int, playerEmail: String, symbol: String): Result<Unit> {
        return runCatchingResult {
            Log.d("GameRepository", "=== MAKING MOVE ===")
            Log.d(
                "GameRepository",
                "SessionID: $sessionID, CellId: $cellId, Player: $playerEmail, Symbol: $symbol"
            )

            if (cellId !in 1..9) {
                throw Exception("Неверный номер клетки: $cellId (допустимы 1-9)")
            }

            // АТОМАРНАЯ ОПЕРАЦИЯ: проверка чьего хода, проверка занятости клетки,
            // запись хода и переключение хода выполняются в одной транзакции Firebase.
            // Раньше это были отдельные get()/setValue(), между которыми второй клик
            // того же игрока успевал пройти все проверки ("check-then-act" гонка) —
            // именно поэтому один игрок мог занять несколько (и все) клеток подряд.
            //
            // НЕЙМИНГ КЛЕТОК: в БД клетки хранятся под ключами "1".."9" (1-based),
            // а boardState во ViewModel/UI — 0-based массив [0..8]. Конверсия
            // выполняется в GameViewModel.makeMove(); сюда приходит уже 1-based cellId.
            val sessionRef = myRef.child("PlayerOnline").child(sessionID)
            val result = sessionRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutablePostData): Transaction.Result {
                    val node = currentData.value ?: return Transaction.abort()

                    val currentTurn = (node.child("currentTurn").getValue(String::class.java) ?: "").asCanonicalPlayer()
                    val player1 = (node.child("player1").getValue(String::class.java) ?: "").asCanonicalPlayer()
                    val player2 = (node.child("player2").getValue(String::class.java) ?: "").asCanonicalPlayer()
                    val actor = playerEmail.asCanonicalPlayer()

                    if (player1.isEmpty() || player2.isEmpty()) {
                        Log.w("GameRepository", "Transaction aborted: session not initialized")
                        return Transaction.abort()
                    }

                    // Заявитель хода должен быть одним из игроков этой сессии
                    if (!samePlayer(actor, player1) && !samePlayer(actor, player2)) {
                        Log.w("GameRepository", "Transaction aborted: $actor is not a player of this session")
                        return Transaction.abort()
                    }

                    // Ход может сделать только тот, чей сейчас ход
                    if (!samePlayer(currentTurn, actor)) {
                        Log.w("GameRepository", "Attempted move by $actor but it's $currentTurn's turn!")
                        return Transaction.abort()
                    }

                    val cellValue = (node.child(cellId.toString()).getValue(String::class.java) ?: "").asCanonicalPlayer()
                    if (cellValue.isNotEmpty()) {
                        Log.w("GameRepository", "Cell $cellId is already occupied by $cellValue")
                        return Transaction.abort()
                    }

                    // Игра ещё не окончена (защита от «дотыкивания» поля после победы)
                    if (isFinished(node, player1, player2)) {
                        Log.w("GameRepository", "Transaction aborted: game already finished")
                        return Transaction.abort()
                    }

                    // В клетку пишем email владельца хода (ключи клеток в БД — "1".."9")
                    node.child(cellId.toString()).value = currentTurn

                    // Переключаем ход на соперника
                    val nextPlayer = if (samePlayer(currentTurn, player1)) player2 else player1
                    node.child("currentTurn").value = nextPlayer

                    return Transaction.success(node)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, data: DataSnapshot?) {
                    if (error != null) {
                        Log.e("GameRepository", "Move transaction failed: ${error.message}")
                    }
                }
            }).await()

            if (!result.committed) {
                throw Exception("Ход отклонён: сейчас не ваш ход или клетка уже занята")
            }

            Log.d("GameRepository", "Move committed atomically: cell $cellId by $playerEmail ($symbol)")
        }
    }
    
    /**
     * Обновление состояния доски (устаревший метод, используется makeMove)
     * После обновления автоматически переключает currentTurn на следующего игрока
     */
    @Deprecated(
        "Небезопасен: переключает currentTurn без проверки авторства хода. Используйте makeMove().",
        ReplaceWith("makeMove(sessionID, cellId, playerEmail, symbol)")
    )
    suspend fun updateBoardState(sessionID: String, board: List<String>): Result<Unit> {
        return runCatchingResult {
            // Метод сохранён только для обратной совместимости и намеренно отключён.
            // Он переключал currentTurn без проверки, чей ход, что позволяло одному
            // клиенту «передавать» ход самому себе и занимать все клетки подряд.
            throw UnsupportedOperationException("updateBoardState отключён: используйте makeMove()")
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
                    // Читаем currentTurn напрямую из child узла
                    val currentTurnSnapshot = snapshot.child("currentTurn")
                    val currentTurn = currentTurnSnapshot.getValue(String::class.java) ?: ""
                    
                    Log.d("GameRepository", "observeCurrentTurn: currentTurn='$currentTurn' for session=$sessionID (snapshot children=${snapshot.childrenCount})")
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
        
        // Слушаем весь узел сессии чтобы получать все обновления
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
