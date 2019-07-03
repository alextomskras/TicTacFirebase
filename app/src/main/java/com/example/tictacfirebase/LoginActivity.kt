package com.example.tictacfirebase

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tictacfirebase.service.MyFirebaseMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.iid.FirebaseInstanceId
import kotlinx.android.synthetic.main.activity_login.*

class LoginActivity : AppCompatActivity() {
    companion object {
        val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        login_progressBar2.visibility = View.GONE
        login_progressBar.visibility = View.GONE



        login_button_login.setOnClickListener {
            login_progressBar.scaleY = 4f
            login_progressBar2.visibility = View.VISIBLE
            login_progressBar.visibility = View.VISIBLE
            performLogin()
        }

        back_to_register_login.setOnClickListener {
            finish()
        }
    }

    private fun performLogin() {
        val email = email_edittext_login.text.toString()
        val stripEmail = SplitString(email)
        val password = password_edittext_login.text.toString()


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
                login_progressBar.visibility = View.GONE
                login_progressBar2.visibility = View.GONE
                startActivity(intent)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to log in: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshTokens(stripEmail: String): String? {
        val newToken = FirebaseInstanceId.getInstance().token
        Log.d("newToken", (newToken))
        Toast.makeText(this, "Please fill out $newToken", Toast.LENGTH_SHORT).show()


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
        return newToken
    }

    fun SplitString(str: String): String {
        var split = str.split("@")
        return split[0]
    }

}

