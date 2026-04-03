package com.example.tictacfirebase


import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tictacfirebase.game.GameManager
import com.example.tictacfirebase.game.WinResult
import com.example.tictacfirebase.models.User
import com.example.tictacfirebase.repository.GameRepository
import com.example.tictacfirebase.service.MyFirebaseMessagingService
import com.example.tictacfirebase.utils.AppConstants
import com.example.tictacfirebase.utils.splitEmail
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.*

open class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = AppConstants.TAG
    }

    private val SENDER_ID = getString(R.string.SENDER_ID)
    private val random = Random()

    // Repository для работы с Firebase
    private lateinit var gameRepository: GameRepository
    
    // Менеджер игры для локальной логики
    private lateinit var gameManager: GameManager

    // Database instance (оставляем для обратной совместимости, но постепенно убираем)
    private var database = FirebaseDatabase.getInstance()
    private var myRef = database.reference

    var myEmail: String? = null

    lateinit var tokenID: MyFirebaseMessagingService
    lateinit var mFirebaseAnalytics: FirebaseAnalytics
    
    // Переменные для управления подписками
    private var gameSessionListener: ValueEventListener? = null
    private var incomingRequestsListener: ValueEventListener? = null
    private var userProfileListener: ValueEventListener? = null
    
    // Coroutine jobs для отмены в onDestroy
    private var gameSessionJob: Job? = null
    private var incomingRequestsJob: Job? = null

    // UI Views
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var tvConnectionStatus: android.widget.TextView
    
    // Игровые переменные
    private var sessionID: String? = null
    private var playerSymbol: String? = null
    private var activePlayer = 1
    private var player1 = ArrayList<Int>()
    private var player2 = ArrayList<Int>()
    
    // Image views
    private val image_View_user2 by lazy { findViewById<de.hdodenhof.circleimageview.CircleImageView>(com.example.tictacfirebase.R.id.image_View_user2) }
    private val player2_text_View by lazy { findViewById<android.widget.TextView>(com.example.tictacfirebase.R.id.player2_text_View) }
    private val buAcceptEvent by lazy { findViewById<Button>(com.example.tictacfirebase.R.id.buAcceptEvent) }
    private val burequest by lazy { findViewById<Button>(com.example.tictacfirebase.R.id.burequest) }
    private val etEmail by lazy { findViewById<android.widget.EditText>(com.example.tictacfirebase.R.id.etEmail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Инициализация UI элементов
        progressBar = findViewById(com.example.tictacfirebase.R.id.progressBar)
        tvConnectionStatus = findViewById(com.example.tictacfirebase.R.id.tvConnectionStatus)
        
        // Инициализация repository и game manager
        gameRepository = GameRepository()
        gameManager = GameManager()
        
        //Hide img+player2name
        player2_text_View.visibility = View.GONE
        image_View_user2.visibility = View.GONE

        //Block_ACCEPT_BUTTON
        buAcceptEvent.isEnabled = false

        refreshTokens()

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        val channelId = getString(R.string.default_notification_channel_id)
        val b: Bundle = intent.extras
        myEmail = b.getString(AppConstants.KEY_EMAIL)
        Log.d(TAG, "getExtraEmail: $myEmail")
        supportActionBar?.title = getString(R.string.app_name) + " $myEmail"
        
        // Обновляем статус подключения
        updateConnectionStatus(getString(R.string.connecting))
        
        // Запускаем прослушивание входящих запросов с использованием lifecycleScope
        setupIncomingRequestsListener()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Отменяем все корутины
        gameSessionJob?.cancel()
        incomingRequestsJob?.cancel()
        
        // Удаляем слушатели Firebase
        sessionID?.let {
            if (gameSessionListener != null) {
                myRef.child("PlayerOnline").child(it).removeEventListener(gameSessionListener!!)
            }
        }
        
        myEmail?.let { email ->
            if (incomingRequestsListener != null) {
                myRef.child("users").child(email.splitEmail()).child("request")
                    .removeEventListener(incomingRequestsListener!!)
            }
        }
        
        // Скрываем индикатор загрузки
        showLoading(false)
    }
    
    /**
     * Обновление статуса подключения
     */
    private fun updateConnectionStatus(status: String) {
        tvConnectionStatus.text = status
    }
    
    /**
     * Показать/скрыть индикатор загрузки
     */
    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshTokens(): String? {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            val newToken = task.result
            Log.d("newToken", newToken)
            
            // Обновляем токен в базе данных с использованием lifecycleScope
            myEmail?.let { email ->
                lifecycleScope.launch {
                    try {
                        showLoading(true)
                        gameRepository.updateUserToken(email.splitEmail(), newToken)
                        updateConnectionStatus(getString(R.string.connected))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating token: ${e.message}")
                        updateConnectionStatus(getString(R.string.error_connection))
                    } finally {
                        showLoading(false)
                    }
                }
            }
        }
        return null
    }

    fun buClick(view: View) {
        val buSelected = view as Button
        var cellID = 0
        when (buSelected.id) {
            com.example.tictacfirebase.R.id.bu1 -> cellID = AppConstants.CellIds.CELL_1
            com.example.tictacfirebase.R.id.bu2 -> cellID = AppConstants.CellIds.CELL_2
            com.example.tictacfirebase.R.id.bu3 -> cellID = AppConstants.CellIds.CELL_3
            com.example.tictacfirebase.R.id.bu4 -> cellID = AppConstants.CellIds.CELL_4
            com.example.tictacfirebase.R.id.bu5 -> cellID = AppConstants.CellIds.CELL_5
            com.example.tictacfirebase.R.id.bu6 -> cellID = AppConstants.CellIds.CELL_6
            com.example.tictacfirebase.R.id.bu7 -> cellID = AppConstants.CellIds.CELL_7
            com.example.tictacfirebase.R.id.bu8 -> cellID = AppConstants.CellIds.CELL_8
            com.example.tictacfirebase.R.id.bu9 -> cellID = AppConstants.CellIds.CELL_9
        }
        
        // Делаем ход через repository с использованием lifecycleScope
        sessionID?.let { sid ->
            myEmail?.let { email ->
                lifecycleScope.launch {
                    try {
                        showLoading(true)
                        gameRepository.makeMove(sid, cellID, email)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error making move: ${e.message}")
                        Toast.makeText(this@MainActivity, "Error making move: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        showLoading(false)
                    }
                }
            }
        }
    }

    // Игровые переменные перенесены в начало класса
    // Используем gameManager для логики игры
    
    /**
     * Локальная игра (для тестирования или офлайн режима)
     * В продакшене лучше использовать только сетевую игру через repository
     */
    fun PlayGame(cellID: Int, buSelected: Button) {
        // Используем GameManager для проверки валидности хода
        if (!gameManager.makeMove(cellID)) {
            Toast.makeText(this, "Invalid move", Toast.LENGTH_SHORT).show()
            return
        }

        if (activePlayer == 1) {
            buSelected.text = "X"
            buSelected.setBackgroundResource(R.color.blue)
            activePlayer = 2
        } else {
            buSelected.text = "O"
            buSelected.setBackgroundResource(R.color.darkgreen)
            activePlayer = 1
        }

        buSelected.isEnabled = false
        
        // Проверяем победителя через GameManager
        when (gameManager.checkWinner()) {
            is WinResult.Player1Wins -> {
                Toast.makeText(this, "Player 1 wins the game", Toast.LENGTH_LONG).show()
                restartGame()
            }
            is WinResult.Player2Wins -> {
                Toast.makeText(this, "Player 2 wins the game", Toast.LENGTH_LONG).show()
                restartGame()
            }
            is WinResult.Draw -> {
                Toast.makeText(this, "Draw!", Toast.LENGTH_LONG).show()
                restartGame()
            }
            else -> {
                // Игра продолжается
            }
        }
    }
    
    /**
     * Устаревший метод проверки победителя
     * Используется GameManager вместо ручной проверки
     */
    @Deprecated("Use GameManager instead")
    fun CheckWiner() {
        // Этот метод больше не используется, логика перенесена в PlayGame
    }

    fun AutoPlay(cellID: Int) {


        val buSelect: Button? = when (cellID) {
            1 -> bu1
            2 -> bu2
            3 -> bu3
            4 -> bu4
            5 -> bu5
            6 -> bu6
            7 -> bu7
            8 -> bu8
            9 -> bu9
            else -> {
                bu1
            }
        }

        buSelect?.let { PlayGame(cellID, it) }

    }

    fun buRequestEvent(view: View) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val userUID = FirebaseAuth.getInstance().uid.toString()
                val userDemail = etEmail.text.toString()

                //unHide player2 icon
                player2_text_View.visibility = View.VISIBLE
                image_View_user2.visibility = View.VISIBLE
                player2_text_View.text = "Player2-" + splitEmailFull(userDemail)

                // Загружаем аватар противника
                val opponentAvatarUrl = loadOpponentAvatar(userDemail)
                image_View_user2.load(opponentAvatarUrl)

                // Отправляем запрос на игру через repository
                gameRepository.sendGameRequest(myEmail!!, userDemail)
                
                // Создаем сессию игры
                sessionID = splitEmailFull(myEmail!!) + splitEmailFull(userDemail)
                gameRepository.createGameSession(sessionID!!)
                
                playerSymbol = AppConstants.SYMBOL_X
                
                updateConnectionStatus(getString(R.string.waiting_for_opponent))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending request: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }


    fun buAcceptEvent(view: View) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val userDemail = etEmail.text.toString()
                
                //unHide player2 icon
                player2_text_View.visibility = View.VISIBLE
                image_View_user2.visibility = View.VISIBLE
                player2_text_View.text = "Player2-" + splitEmailFull(userDemail)
                
                // Загружаем аватар противника
                val opponentAvatarUrl = loadOpponentAvatar(userDemail)
                image_View_user2.load(opponentAvatarUrl)

                // Создаем сессию игры
                sessionID = splitEmailFull(userDemail) + splitEmailFull(myEmail!!)
                gameRepository.createGameSession(sessionID!!)
                
                playerSymbol = AppConstants.SYMBOL_O
                
                updateConnectionStatus(getString(R.string.your_turn))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting request: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }
    
    /**
     * Загрузка аватара противника из Firebase
     */
    private suspend fun loadOpponentAvatar(email: String): String? {
        return try {
            gameRepository.getUserProfileImage(email)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar: ${e.message}")
            null
        }
    }




    /**
     * Завершение игры и перезапуск
     */
    private fun endGame(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        restartGame()
    }

        fun perfotmFCMSendMessages() {
        val fromId = FirebaseAuth.getInstance().uid
//        val user = intent.getParcelableExtra<User>(NewMessageActivity.USER_KEY)
        val user = myEmail
        val toId = user
//        btn_upmessage.setOnClickListener {
        val fm = FirebaseMessaging.getInstance()
//793202519353@gcm.googleapis.com
        val message = RemoteMessage.Builder(SENDER_ID + "@fcm.googleapis.com")

                .setMessageId(Integer.toString(random.nextInt(9999)))
                .addData("TEST1-- $fromId", "TEST1--  $toId")
//                    .addData(edt_key1.text.toString(), edt_value1.text.toString())
//                    .addData(edt_key2.text.toString(), edt_value2.text.toString())
                .build()
        Log.e(TAG, "UpstreamData: " + message)

        if (!message.data.isEmpty()) {
            Log.e(TAG, "UpstreamData: " + message.data)
        }

        if (!message.messageId!!.isEmpty()) {
            Log.e(TAG, "UpstreamMessageId: " + message.messageId)
        }

        fm.send(message)
//        }
    }

    fun restartGame() {
        Toast.makeText(this, " RESTART the game", Toast.LENGTH_LONG).show()
    }


}
