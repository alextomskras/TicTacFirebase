package com.example.tictacfirebase


import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.tictacfirebase.model.UiEvent
import com.example.tictacfirebase.repository.GameRepository
import com.example.tictacfirebase.utils.AppConstants
import com.example.tictacfirebase.utils.NetworkMonitor
import com.example.tictacfirebase.utils.splitEmail
import com.example.tictacfirebase.utils.splitEmailFull
import com.example.tictacfirebase.viewmodel.GameViewModel
import com.example.tictacfirebase.viewmodel.GameViewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import coil.load
import kotlinx.coroutines.launch
import java.util.Random

open class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = AppConstants.TAG
        private const val SENDER_ID = "793202519353"
    }

    private val random = Random()

    var myEmail: String? = null

    lateinit var mFirebaseAnalytics: FirebaseAnalytics
    
    // UI Views
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var tvConnectionStatus: android.widget.TextView
    private lateinit var noInternetOverlay: android.widget.FrameLayout
    
    // GameViewModel для управления состоянием игры и сетью
    private lateinit var gameViewModel: GameViewModel
    
    // Image views
    private val imageViewUser2 by lazy { findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.image_View_user2) }
    private val player2TextView by lazy { findViewById<android.widget.TextView>(R.id.player2_text_View) }
    private val buAcceptEvent by lazy { findViewById<Button>(R.id.buAcceptEvent) }
    private val buRequest by lazy { findViewById<Button>(R.id.burequest) }
    private val etEmail by lazy { findViewById<android.widget.EditText>(R.id.etEmail) }
    private val bu1 by lazy { findViewById<Button>(R.id.bu1) }
    private val bu2 by lazy { findViewById<Button>(R.id.bu2) }
    private val bu3 by lazy { findViewById<Button>(R.id.bu3) }
    private val bu4 by lazy { findViewById<Button>(R.id.bu4) }
    private val bu5 by lazy { findViewById<Button>(R.id.bu5) }
    private val bu6 by lazy { findViewById<Button>(R.id.bu6) }
    private val bu7 by lazy { findViewById<Button>(R.id.bu7) }
    private val bu8 by lazy { findViewById<Button>(R.id.bu8) }
    private val bu9 by lazy { findViewById<Button>(R.id.bu9) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Инициализация UI элементов
        progressBar = findViewById(R.id.progressBar)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        noInternetOverlay = findViewById(R.id.noInternetOverlay)
        
        // Инициализация GameRepository для передачи в ViewModel
        val gameRepository = GameRepository()
        
        //Hide img+player2name
        player2TextView.visibility = View.GONE
        imageViewUser2.visibility = View.GONE

        //Block_ACCEPT_BUTTON
        buAcceptEvent.isEnabled = false

        refreshTokens()

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)

        val b: Bundle? = intent.extras
        myEmail = b?.getString(AppConstants.KEY_EMAIL)
        Log.d(TAG, "getExtraEmail: $myEmail")
        supportActionBar?.title = getString(R.string.app_name) + " $myEmail"
        
        // Обновляем статус подключения
        updateConnectionStatus(getString(R.string.connecting))
        
        // Инициализация GameViewModel с временным gameId (будет обновлен при создании/присоединении к игре)
        gameViewModel = ViewModelProvider(
            this,
            GameViewModelFactory(gameRepository, "temp_game_id", this)
        )[GameViewModel::class.java]
        
        // Наблюдаем за состоянием сети через ViewModel и показываем/скрываем оверлей
        setupNetworkObserver()
        
        // Наблюдаем за UI эффектами от ViewModel (toast, навигация)
        setupUiEffectObserver()
        
        // Запускаем прослушивание входящих запросов с использованием lifecycleScope
        setupIncomingRequestsListener()
    }
    
    /**
     * Настройка наблюдения за UI эффектами от ViewModel
     * Показывает toast сообщения и обрабатывает навигацию
     */
    private fun setupUiEffectObserver() {
        lifecycleScope.launch {
            gameViewModel.uiEffect.collect { effect ->
                when (effect) {
                    is UiEffect.ShowToast -> {
                        Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                    }
                    is UiEffect.NavigateTo -> {
                        // TODO: Реализовать навигацию
                    }
                    UiEffect.GameEnded -> {
                        // TODO: Показать диалог конца игры
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Настройка прослушивания входящих запросов на игру
     * Использует lifecycleScope для автоматической отмены при уничтожении Activity
     */
    private fun setupIncomingRequestsListener() {
        myEmail?.let { email ->
            lifecycleScope.launch {
                try {
                    // Слушаем запросы через Flow из ViewModel
                    gameViewModel.observeGameRequests(email.splitEmail()).collect { requesterEmail ->
                        Log.d(TAG, "Incoming request from: $requesterEmail")
                        etEmail.setText(requesterEmail)
                        
                        // Отправляем уведомление (FCM)
                        performFcmSendMessages()
                        
                        // Активируем кнопку принятия запроса
                        buAcceptEvent.isEnabled = true
                        buAcceptEvent.tag = "enabled"
                        
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.incoming_request_message, requesterEmail),
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Очищаем запрос после обработки
                        gameViewModel.clearGameRequest(email.splitEmail())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing incoming request: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_processing_request, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
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
    
    /**
     * Показать/скрыть оверлей "Нет интернета"
     */
    private fun showNoInternetOverlay(show: Boolean) {
        noInternetOverlay.visibility = if (show) View.VISIBLE else View.GONE
        // Блокируем все UI элементы когда нет интернета
        bu1.isEnabled = !show
        bu2.isEnabled = !show
        bu3.isEnabled = !show
        bu4.isEnabled = !show
        bu5.isEnabled = !show
        bu6.isEnabled = !show
        bu7.isEnabled = !show
        bu8.isEnabled = !show
        bu9.isEnabled = !show
        buRequest.isEnabled = !show
        buAcceptEvent.isEnabled = !show && buAcceptEvent.tag == "enabled"
        etEmail.isEnabled = !show
    }
    
    /**
     * Настройка наблюдения за состоянием сети через GameViewModel
     * Показывает/скрывает оверлей при потере/восстановлении подключения
     */
    private fun setupNetworkObserver() {
        lifecycleScope.launch {
            NetworkMonitor.observeNetworkConnectivity(this@MainActivity).collect { isOnline ->
                Log.d(TAG, "Network status changed: isOnline=$isOnline")
                
                // Обновляем текст статуса подключения
                val statusText = if (isOnline) {
                    getString(R.string.connected)
                } else {
                    getString(R.string.no_internet)
                }
                updateConnectionStatus(statusText)
                
                // Показываем/скрываем оверлей
                showNoInternetOverlay(!isOnline)
                
                // Также обновляем состояние в ViewModel
                gameViewModel.observeNetworkStatusForUi(isOnline)
            }
        }
    }

    private fun refreshTokens(): String? {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            val newToken = task.result
            Log.d("newToken", newToken)
            
            // Обновляем токен в базе данных с использованием lifecycleScope через ViewModel
            myEmail?.let { email ->
                lifecycleScope.launch {
                    try {
                        showLoading(true)
                        gameViewModel.updateUserToken(email.splitEmail(), newToken)
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
            R.id.bu1 -> cellID = AppConstants.CellIds.CELL_1
            R.id.bu2 -> cellID = AppConstants.CellIds.CELL_2
            R.id.bu3 -> cellID = AppConstants.CellIds.CELL_3
            R.id.bu4 -> cellID = AppConstants.CellIds.CELL_4
            R.id.bu5 -> cellID = AppConstants.CellIds.CELL_5
            R.id.bu6 -> cellID = AppConstants.CellIds.CELL_6
            R.id.bu7 -> cellID = AppConstants.CellIds.CELL_7
            R.id.bu8 -> cellID = AppConstants.CellIds.CELL_8
            R.id.bu9 -> cellID = AppConstants.CellIds.CELL_9
        }
        
        // Отправляем событие в ViewModel для обработки хода
        gameViewModel.onEvent(UiEvent.CellSelected(cellID))
    }

    fun buRequestEvent(view: View) {
        val userDemail = etEmail.text.toString()
        
        //unHide player2 icon
        player2TextView.visibility = View.VISIBLE
        imageViewUser2.visibility = View.VISIBLE
        player2TextView.text = getString(R.string.player2_label, splitEmailFull(userDemail))

        // Загружаем аватар противника
        lifecycleScope.launch {
            val opponentAvatarUrl = loadOpponentAvatar(userDemail)
            imageViewUser2.load(opponentAvatarUrl)
        }

        // Отправляем событие в ViewModel
        gameViewModel.onEvent(UiEvent.SendGameRequest(myEmail!!, userDemail))
    }


    fun buAcceptEvent(view: View) {
        val userDemail = etEmail.text.toString()
        
        //unHide player2 icon
        player2TextView.visibility = View.VISIBLE
        imageViewUser2.visibility = View.VISIBLE
        player2TextView.text = getString(R.string.player2_label, splitEmailFull(userDemail))
        
        // Загружаем аватар противника
        lifecycleScope.launch {
            val opponentAvatarUrl = loadOpponentAvatar(userDemail)
            imageViewUser2.load(opponentAvatarUrl)
        }

        // Отправляем событие в ViewModel
        gameViewModel.onEvent(UiEvent.AcceptGameRequest(userDemail, myEmail!!))
    }
    
    /**
     * Загрузка аватара противника из Firebase
     */
    private suspend fun loadOpponentAvatar(email: String): String? {
        return try {
            gameViewModel.getUserProfileImage(email)
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

    private fun performFcmSendMessages() {
        val user = myEmail
        val toId = user
        val fm = FirebaseMessaging.getInstance()
        val message = RemoteMessage.Builder("$SENDER_ID@fcm.googleapis.com")
                .setMessageId(random.nextInt(9999).toString())
                .addData("TEST1-- $user", "TEST1--  $toId")
                .build()
        Log.e(TAG, "UpstreamData: $message")

        if (message.data.isNotEmpty()) {
            Log.e(TAG, "UpstreamData: ${message.data}")
        }

        if (message.messageId!!.isNotEmpty()) {
            Log.e(TAG, "UpstreamMessageId: ${message.messageId}")
        }

        @Suppress("DEPRECATION")
        fm.send(message)
    }

    fun restartGame() {
        Toast.makeText(this, getString(R.string.restart_game_message), Toast.LENGTH_LONG).show()
    }


}
