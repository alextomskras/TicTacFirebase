package com.example.tictacfirebase

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tictacfirebase.databinding.ActivityLoginBinding
import com.example.tictacfirebase.service.MyFirebaseMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class LoginActivity : AppCompatActivity() {
    companion object {
        val TAG = "LoginActivity"
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
        val email = binding.emailEdittextLogin.text.toString()
        val stripEmail = splitString(email)
        val password = binding.passwordEdittextLogin.text.toString()


//        val ref = FirebaseDatabase.getInstance().getReference("/users/$stripEmail/newToken")
//        ref.setValue(newTokens)
//            .addOnSuccessListener {
//                Log.d(TAG, "Finally we saved the user to Firebase Database")
//            }
//            .addOnFailureListener {
//                Log.d(TAG, "Failed to set value to database: ${it.message}")
//            }

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill out email/pw.", Toast.LENGTH_SHORT).show()
            return
        }
        refreshTokens(stripEmail)

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener {
                if (!it.isSuccessful) return@addOnCompleteListener

                Log.d("Login", "Successfully logged in: ${it.result?.user!!.uid}")

                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("email", it.result?.user!!.email)
                Log.d(TAG, "putExtraEmail: ${it.result?.user!!.email}")
                intent.putExtra("uid", it.result?.user!!.uid)
                Log.d(TAG, "putExtraUid: ${it.result?.user!!.uid}")

                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
                binding.loginProgressBar.visibility = View.GONE
                binding.loginProgressBar2.visibility = View.GONE
                startActivity(intent)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to log in: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshTokens(stripEmail: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            val newToken = task.result
            Log.d("newToken", newToken)
            Toast.makeText(this, "Token: $newToken", Toast.LENGTH_SHORT).show()

            if (newToken != null) {
                MyFirebaseMessagingService().saveTokenToFirebaseDatabase(newToken)
                val ref = FirebaseDatabase.getInstance().getReference("/users/$stripEmail/newToken")
                ref.setValue(newToken)
                    .addOnSuccessListener {
                        Log.d(TAG, "Finally we saved the Token to Firebase Database")
                    }
                    .addOnFailureListener {
                        Log.d(TAG, "Failed to set value to database: ${it.message}")
                    }

            }
        }
    }

    private fun splitString(str: String): String {
        val split = str.split("@")
        return split[0]
    }

}

