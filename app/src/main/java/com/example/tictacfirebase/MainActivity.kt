package com.example.tictacfirebase

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.tictacfirebase.model.UiEffect
import com.example.tictacfirebase.model.UiEvent
import com.example.tictacfirebase.repository.GameRepository
import com.example.tictacfirebase.utils.AppConstants
import com.example.tictacfirebase.utils.NetworkMonitor
import com.example.tictacfirebase.utils.isValidEmail
import com.example.tictacfirebase.utils.splitEmail
import com.example.tictacfirebase.utils.splitEmailFull
import com.example.tictacfirebase.viewmodel.GameViewModel
import com.example.tictacfirebase.viewmodel.GameViewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

open class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TicTacFirebase"
        private const val SENDER_ID = "793202519353"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
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

        // Запрос разрешения на уведомления для Android 13+
        requestNotificationPermission()
        // Инициализация UI элементов
        progressBar = findViewById(R.id.progressBar)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        noInternetOverlay = findViewById(R.id.noInternetOverlay)
        
        // Настройка ActionBar будет выполнена в onCreateOptionsMenu
        
        // Инициализация GameRepository для передачи в ViewModel
        val gameRepository = GameRepository()
        
        //Hide img+player name
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
        
        // Наблюдаем за состоянием загрузки из ViewModel
        setupLoadingObserver()
        
        // Запускаем прослушивание входящих запросов с использованием lifecycleScope
        setupIncomingRequestsListener()
        
        // Наблюдаем за состоянием игры (доска, статус, чей ход) и обновляем UI
        setupGameObserver()
    }

    /**
     * Запрос разрешения на отправку уведомлений для Android 13+ (API 33+)
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
                Log.d(TAG, "Notification permission requested")
            } else {
                Log.d(TAG, "Notification permission already granted")
            }
        } else {
            Log.d(TAG, "Android version < 13, notification permission not required")
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted")
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Notification permission denied")
                Toast.makeText(
                    this,
                    "Notifications disabled - you won't receive game requests",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
     * Настройка наблюдения за состоянием загрузки из ViewModel
     * Показывает/скрывает индикатор загрузки на основе gameState.isLoading
     */
    private fun setupLoadingObserver() {
        lifecycleScope.launch {
            gameViewModel.gameState.collect { state ->
                showLoading(state.isLoading)
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
                        
                        // FCM уведомление уже было отправлено отправителем запроса в sendGameRequest()
                        // Здесь просто показываем UI и toast
                        
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
     * Настройка наблюдения за состоянием игры и обновление UI
     * Обновляет доску, статус игры, аватарки и индикатор хода
     */
    private fun setupGameObserver() {
        lifecycleScope.launch {
            gameViewModel.gameState.collect { state ->
                // Обновляем доску
                state.boardState.forEachIndexed { index, cellValue ->
                    val button = when (index + 1) {
                        1 -> bu1
                        2 -> bu2
                        3 -> bu3
                        4 -> bu4
                        5 -> bu5
                        6 -> bu6
                        7 -> bu7
                        8 -> bu8
                        9 -> bu9
                        else -> null
                    }
                    button?.text = cellValue
                    // Применяем цвет в зависимости от символа: О - синий, Х - красный
                    button?.setTextColor(
                        when (cellValue) {
                            "O" -> ContextCompat.getColor(this@MainActivity, R.color.colorO)
                            "X" -> ContextCompat.getColor(this@MainActivity, R.color.colorX)
                            else -> ContextCompat.getColor(this@MainActivity, R.color.black)
                        }
                    )
                }
                
                // Блокируем/разблокируем кнопки в зависимости от чьего хода и статуса игры
                val canMakeMove = state.isMyTurn && state.gameStatus == com.example.tictacfirebase.model.GameStatus.Playing && state.isOnline
                bu1.isEnabled = canMakeMove && bu1.text.isEmpty()
                bu2.isEnabled = canMakeMove && bu2.text.isEmpty()
                bu3.isEnabled = canMakeMove && bu3.text.isEmpty()
                bu4.isEnabled = canMakeMove && bu4.text.isEmpty()
                bu5.isEnabled = canMakeMove && bu5.text.isEmpty()
                bu6.isEnabled = canMakeMove && bu6.text.isEmpty()
                bu7.isEnabled = canMakeMove && bu7.text.isEmpty()
                bu8.isEnabled = canMakeMove && bu8.text.isEmpty()
                bu9.isEnabled = canMakeMove && bu9.text.isEmpty()
                
                // Обновляем аватарки игроков - постоянно обновляем чтобы не пропадали
                state.playerAvatarUrl?.let { url ->
                    // player1 avatar можно добавить если есть imageViewUser1
                }
                state.opponentAvatarUrl?.let { url ->
                    if (url.isNotEmpty()) {
                        imageViewUser2.load(url) {
                            crossfade(true)
                            placeholder(R.drawable.ic_fire_emoji)
                            error(R.drawable.ic_fire_emoji)
                        }
                    }
                }
                
                // Показываем аватар и имя противника только когда игра активна
                val shouldShowOpponentInfo = state.gameStatus == com.example.tictacfirebase.model.GameStatus.Playing || 
                                            state.gameStatus == com.example.tictacfirebase.model.GameStatus.Won ||
                                            state.gameStatus == com.example.tictacfirebase.model.GameStatus.Lost ||
                                            state.gameStatus == com.example.tictacfirebase.model.GameStatus.Draw ||
                                            state.gameStatus == com.example.tictacfirebase.model.GameStatus.OpponentLeft
                player2TextView.visibility = if (shouldShowOpponentInfo && state.opponentName.isNotEmpty()) View.VISIBLE else View.GONE
                imageViewUser2.visibility = if (shouldShowOpponentInfo && state.opponentName.isNotEmpty()) View.VISIBLE else View.GONE
                
                // Обновляем индикатор чей ход и статус игры
                if (state.gameStatus == com.example.tictacfirebase.model.GameStatus.Playing) {
                    if (state.isMyTurn) {
                        // Определяем мой символ через isFirstPlayer
                        val mySymbol = if (state.isFirstPlayer) "X" else "O"
                        supportActionBar?.subtitle = "Ваш ход ($mySymbol)"
                    } else {
                        val opponentSymbol = if (state.isFirstPlayer) "O" else "X"
                        supportActionBar?.subtitle = "Ход соперника ($opponentSymbol)..."
                    }
                } else {
                    // Показываем результат игры
                    val resultText = when (state.gameStatus) {
                        com.example.tictacfirebase.model.GameStatus.Won -> "Вы победили!"
                        com.example.tictacfirebase.model.GameStatus.Lost -> "Вы проиграли!"
                        com.example.tictacfirebase.model.GameStatus.Draw -> "Ничья!"
                        com.example.tictacfirebase.model.GameStatus.OpponentLeft -> "Соперник вышел из игры"
                        else -> ""
                    }
                    supportActionBar?.subtitle = resultText
                }
            }
        }
    }
    
    /**
     * Обновление статуса подключения
     */
    private fun updateConnectionStatus(status: String) {
        tvConnectionStatus.text = status
        // Не показываем progressBar, если loading уже false
        progressBar.visibility = View.GONE
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
        // Блокируем все UI элементы когда нет интернета - остальное управляется через setupGameObserver
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

    private fun refreshTokens() {
        // Используем lifecycleScope для корректной работы с корутинами
        lifecycleScope.launch {
            try {
                val newToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "FCM token received: $newToken")
                
                // Обновляем токен в базе данных
                myEmail?.let { email ->
                    try {
                        showLoading(true)
                        gameViewModel.updateUserToken(email.splitEmail(), newToken)
                        updateConnectionStatus(getString(R.string.connected))
                        Log.d(TAG, "Token updated successfully for user: ${email.splitEmail()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating token: ${e.message}")
                        updateConnectionStatus(getString(R.string.error_connection))
                    } finally {
                        showLoading(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetching FCM registration token failed: ${e.message}")
                updateConnectionStatus(getString(R.string.error_connection))
                showLoading(false)
            }
        }
    }

    fun buClick(view: View) {
        val buSelected = view as Button
        
        // Воспроизводим анимацию нажатия
        val scaleUp = AnimationUtils.loadAnimation(this, R.anim.click_scale)
        val scaleDown = AnimationUtils.loadAnimation(this, R.anim.click_scale_back)
        
        buSelected.startAnimation(scaleUp)
        
        // Запускаем обратную анимацию с небольшой задержкой
        buSelected.postDelayed({
            buSelected.startAnimation(scaleDown)
        }, 200)
        
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
        val userDemail = etEmail.text.toString().trim()
        
        // Проверяем, не пустой ли email
        if (userDemail.isBlank()) {
            Toast.makeText(this, "Введите email пользователя", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Валидация email формата
        if (!userDemail.isValidEmail()) {
            Toast.makeText(this, "Некорректный формат email", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Проверяем, не тот же ли это самый пользователь
        if (userDemail.equals(myEmail, ignoreCase = true)) {
            Toast.makeText(this, "Нельзя отправить запрос самому себе", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "Sending request to: $userDemail")
        
        // Показываем индикатор загрузки во время отправки запроса
        showLoading(true)
        
        //unHide player2 icon
        player2TextView.visibility = View.VISIBLE
        imageViewUser2.visibility = View.VISIBLE
        player2TextView.text = getString(R.string.player2_label, splitEmailFull(userDemail))

        // Загружаем аватар противника и отправляем запрос
        lifecycleScope.launch {
            try {
                val opponentAvatarUrl = loadOpponentAvatar(userDemail)
                imageViewUser2.load(opponentAvatarUrl)
                
                // Отправляем событие в ViewModel после загрузки аватара
                gameViewModel.onEvent(UiEvent.SendGameRequest(myEmail!!, userDemail))
            } catch (e: Exception) {
                Log.e(TAG, "Error sending request: ${e.message}")
                Toast.makeText(this@MainActivity, "Ошибка отправки запроса: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // Скрываем индикатор загрузки после завершения
                showLoading(false)
            }
        }
    }


    fun buAcceptEvent(view: View) {
        val userDemail = etEmail.text.toString().trim()
        
        // Валидация email формата
        if (!userDemail.isValidEmail()) {
            Toast.makeText(this, "Некорректный формат email", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Показываем индикатор загрузки во время принятия запроса
        showLoading(true)
        
        //unHide player2 icon
        player2TextView.visibility = View.VISIBLE
        imageViewUser2.visibility = View.VISIBLE
        player2TextView.text = getString(R.string.player2_label, splitEmailFull(userDemail))
        
        // Загружаем аватар противника и принимаем запрос
        lifecycleScope.launch {
            try {
                val opponentAvatarUrl = loadOpponentAvatar(userDemail)
                imageViewUser2.load(opponentAvatarUrl)
                
                // Отправляем событие в ViewModel после загрузки аватара
                gameViewModel.onEvent(UiEvent.AcceptGameRequest(userDemail, myEmail!!))

                // Деактивируем кнопку принятия после успешного принятия запроса
                buAcceptEvent.isEnabled = false
                buAcceptEvent.tag = "disabled"
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting request: ${e.message}")
                Toast.makeText(this@MainActivity, "Ошибка принятия запроса: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // Скрываем индикатор загрузки после завершения
                showLoading(false)
            }
        }
    }
    
    /**
     * Обработчик кнопки выхода (logout)
     * Выходит из аккаунта и перенаправляет на экран регистрации
     */
    fun buLogoutEvent(view: View) {
        Log.d(TAG, "User logged out")
        
        // Выход из Firebase Authentication
        FirebaseAuth.getInstance().signOut()
        
        // Переход на экран регистрации
        val intent = Intent(this, registerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
    
    /**
     * Запуск новой игры - сбрасывает состояние и пересоздает игровое поле
     */
    private fun startNewGame() {
        Log.d(TAG, "Starting new game")
        
        // Сбрасываем доску
        bu1.text = ""
        bu2.text = ""
        bu3.text = ""
        bu4.text = ""
        bu5.text = ""
        bu6.text = ""
        bu7.text = ""
        bu8.text = ""
        bu9.text = ""
        
        // Сбрасываем статус в заголовке
        supportActionBar?.subtitle = getString(R.string.waiting_for_opponent)
        
        // Скрываем аватар противника
        player2TextView.visibility = View.GONE
        imageViewUser2.visibility = View.GONE
        
        // Очищаем поле ввода email
        etEmail.text.clear()
        
        // Деактивируем кнопку принятия
        buAcceptEvent.isEnabled = false
        
        // Показываем toast
        Toast.makeText(this, getString(R.string.restart_game_message), Toast.LENGTH_SHORT).show()
        
        // Отправляем событие в ViewModel для сброса состояния игры
        gameViewModel.onEvent(UiEvent.StartNewGame)
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

        if (!message.messageId.isNullOrEmpty()) {
            Log.e(TAG, "UpstreamMessageId: ${message.messageId}")
        }

        @Suppress("DEPRECATION")
        fm.send(message)
    }

    fun restartGame() {
        Toast.makeText(this, getString(R.string.restart_game_message), Toast.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        
        // Устанавливаем кастомную иконку overflow (три полосочки вместо трех точек)
//        supportActionBar?.setOverflowIcon(getDrawable(R.drawable.ic_menu_overflow))
        
        // Скрываем стандартную кнопку "домой" - стандартный overflow (три точки) будет показан автоматически
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)
        
        return true
    }

    /**
     * Обработка выбора пунктов меню
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_game -> {
                startNewGame()
                true
            }
            R.id.action_logout -> {
                buLogoutEvent(findViewById(android.R.id.content))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}
