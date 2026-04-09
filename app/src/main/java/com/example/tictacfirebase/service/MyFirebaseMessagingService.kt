package com.example.tictacfirebase.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.tictacfirebase.LoginActivity
import com.example.tictacfirebase.R
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.*

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val tag = "FCM_Service"


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(tag, "From: " + remoteMessage.from)
        Log.d(tag, "Notification Message Body: " + remoteMessage.notification?.body)
        
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(tag, "Message data payload: " + remoteMessage.data)
        }
        sendNotification(remoteMessage)
    }


    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val deviceToken = token
        Log.d("FCM_TOKEN", "New token generated")

        if (!token.isNullOrEmpty()) {
            Log.d(tag, "Token saved to database")
            saveTokenToFirebaseDatabase(token)
        }
    }

    fun saveTokenToFirebaseDatabase(newRegistrationToken: String?) {
        if (newRegistrationToken == null || newRegistrationToken.isEmpty()) {
            throw NullPointerException("FCM token is null.")
        }

        val ref = FirebaseDatabase.getInstance().getReference("/Tokens/$newRegistrationToken")

        ref.setValue(newRegistrationToken)
            .addOnSuccessListener {
                Log.d("Register_TOKEN", "Finally save Token to firebasedatabase")
            }
            .addOnFailureListener {
                Log.d(
                    "Register_TOKEN",
                    "Failed set Token value to firebasedatabase ${newRegistrationToken}"
                )
            }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendNotification(remoteMessage: RemoteMessage) {
        val intent = Intent(this, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)


        // Добавляем данные из уведомления в интент
        remoteMessage.notification?.let { notification ->
            intent.putExtra("title", notification.title)
            intent.putExtra("body", notification.body)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        }


        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            pendingIntentFlags
        )
        val channelId = getString(R.string.default_notification_channel_id)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(remoteMessage.notification?.title ?: getString(R.string.fcm_message))
            .setContentText(remoteMessage.notification?.body ?: "")
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_fire_emoji)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Channel human readable title",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Channel for game request notifications"
            channel.enableLights(true)
            channel.lightColor = Color.RED
            channel.enableVibration(true)
            channel.setShowBadge(true)
            channel.lockscreenVisibility = NotificationManager.IMPORTANCE_MAX
            notificationManager.createNotificationChannel(channel)
        } else {
            // Для Android 7 и ниже создаем канал с низким приоритетом
            val channel = NotificationChannel(
                channelId,
                "Game Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Channel for game request notifications"
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(0, notificationBuilder.build())
    }
}