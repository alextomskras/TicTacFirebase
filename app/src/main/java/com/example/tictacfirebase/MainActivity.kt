package com.example.tictacfirebase


import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import com.google.firebase.analytics.FirebaseAnalytics

class MainActivity : AppCompatActivity() {


lateinit var mFirebaseAnalytics:FirebaseAnalytics
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)
    }
}
