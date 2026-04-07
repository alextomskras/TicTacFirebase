package com.example.tictacfirebase

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * SplashScreen проверяет, авторизован ли пользователь
 * и перенаправляет либо на главный экран, либо на регистрацию
 */
class SplashScreen : AppCompatActivity() {

    companion object {
        private const val TAG = "SplashScreen"
        private const val SPLASH_DELAY: Long = 300 // Небольшая задержка для проверки
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        Log.d(TAG, "SplashScreen started, checking auth status...")
        
        // Проверяем, авторизован ли пользователь
        val currentUser = FirebaseAuth.getInstance().currentUser
        
        if (currentUser != null) {
            // Пользователь авторизован - переходим на главный экран
            Log.d(TAG, "User is signed in: ${currentUser.email}")
            
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("email", currentUser.email)
                putExtra("uid", currentUser.uid)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        } else {
            // Пользователь не авторизован - переходим на регистрацию
            Log.d(TAG, "User is not signed in, redirecting to registration")
            
            val intent = Intent(this, registerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        }
    }
}
