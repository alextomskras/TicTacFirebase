package com.example.tictacfirebase.service

import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService


class MyFirebaseInstanceIDService : FirebaseMessagingService() {
    private val tag = "NEW_TOKEN"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("NEW_TOKEN", token)

        if (!token.isNullOrEmpty()) {
            Log.d(tag, "TOKEN_$token")
            Toast.makeText(this, "New Token: $token", Toast.LENGTH_SHORT).show()

            if (FirebaseAuth.getInstance().currentUser != null)
                addTokenToFirestore(token)
            saveTokenToFirebaseDatabase(token)
        }
    }

    fun saveTokenToFirebaseDatabase(newRegistrationToken: String) {
        if (newRegistrationToken.isEmpty()) {
            throw NullPointerException("FCM token is null.")
        }

        val ref = FirebaseDatabase.getInstance().getReference("/Tokens/$newRegistrationToken")

        ref.setValue(newRegistrationToken)
            .addOnSuccessListener {
                Log.d("Register_TOKEN", "Finally save Token to firebasedatabase")
            }
            .addOnFailureListener {
                Log.d("Register", "Failed set Token value to firebasedatabase ${it.message}")
            }
    }


    companion object {
        fun addTokenToFirestore(newRegistrationToken: String?) {
            if (newRegistrationToken == null) throw NullPointerException("FCM token is null.")
        }
    }
}
