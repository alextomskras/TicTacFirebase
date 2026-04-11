package com.example.tictacfirebase

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tictacfirebase.databinding.ActivityLoginBinding
import com.example.tictacfirebase.service.MyFirebaseMessagingService
import com.example.tictacfirebase.utils.ValidationUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    companion object {
        private const val tag = "LoginActivity"
    }
    
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.loginProgressBar2.visibility = View.GONE
        binding.loginProgressBar.visibility = View.GONE

        binding.loginButtonLogin.setOnClickListener {
            binding.loginProgressBar.scaleY = 4f
            binding.loginProgressBar2.visibility = View.VISIBLE
            binding.loginProgressBar.visibility = View.VISIBLE
            performLogin()
        }

        binding.backToRegisterLogin.setOnClickListener {
            finish()
        }
    }

    private fun performLogin() {
        val email = binding.emailEdittextLogin.text.toString().trim()
        val password = binding.passwordEdittextLogin.text.toString()

        // Валидация email
        if (!ValidationUtils.isValidEmail(email)) {
            hideLoading()
            Toast.makeText(this, ValidationUtils.getEmailErrorMessage(email), Toast.LENGTH_SHORT).show()
            return
        }

        // Валидация пароля
        if (!ValidationUtils.isValidPassword(password)) {
            hideLoading()
            Toast.makeText(this, ValidationUtils.getPasswordErrorMessage(password), Toast.LENGTH_SHORT).show()
            return
        }

        val stripEmail = splitString(email)

        if (email.isEmpty() || password.isEmpty()) {
            hideLoading()
            Toast.makeText(this, "Please fill out email/pw.", Toast.LENGTH_SHORT).show()
            return
        }
        
        refreshTokens(stripEmail)

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener {
                if (!it.isSuccessful) {
                    hideLoading()
                    Toast.makeText(this, "Failed to log in: ${it.exception?.message}", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                Log.d("Login", "Successfully logged in")

                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("email", it.result?.user!!.email)
                intent.putExtra("uid", it.result?.user!!.uid)

                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
                hideLoading()
                startActivity(intent)
            }
            .addOnFailureListener {
                hideLoading()
                Toast.makeText(this, "Failed to log in: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Скрыть индикаторы загрузки
     */
    private fun hideLoading() {
        binding.loginProgressBar.visibility = View.GONE
        binding.loginProgressBar2.visibility = View.GONE
    }

    private fun refreshTokens(stripEmail: String) {
        lifecycleScope.launch {
            try {
                val newToken = FirebaseMessaging.getInstance().token.await()
                Log.d("FCM_TOKEN", "Token fetched successfully: $newToken")

                if (newToken != null) {
                    withContext(Dispatchers.IO) {
                        MyFirebaseMessagingService().saveTokenToFirebaseDatabase(newToken)
                    }
                    
                    val ref = FirebaseDatabase.getInstance().getReference("/users/$stripEmail/newToken")
                    ref.setValue(newToken)
                        .addOnSuccessListener {
                            Log.d(tag, "Successfully saved Token to Firebase Database")
                        }
                        .addOnFailureListener {
                            Log.d(tag, "Failed to save token to database: ${it.message}")
                        }
                }
            } catch (e: Exception) {
                Log.w(tag, "Fetching FCM registration token failed", e)
                hideLoading()
                Toast.makeText(this@LoginActivity, "Failed to get token: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun splitString(str: String): String {
        val split = str.split("@")
        return split[0]
    }

}

