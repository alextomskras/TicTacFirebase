package com.example.tictacfirebase.service

import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService


class MyFirebaseInstanceIDService : FirebaseMessagingService() {
    val TAG = "NEW_TOKEN"

    override fun onNewToken(s: String?) {
        super.onNewToken(s)
        Log.d("NEW_TOKEN", s)

        if (s != null) {
            Log.d(TAG, "TOKEN_$s")
            Toast.makeText(this, "New Token: ${s}", Toast.LENGTH_SHORT).show()

            if (FirebaseAuth.getInstance().currentUser != null)
                addTokenToFirestore(s)
            saveTokenToFirebaseDatabase(s)
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
