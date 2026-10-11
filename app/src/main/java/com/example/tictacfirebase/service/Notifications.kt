package com.example.tictacfirebase.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.tictacfirebase.LoginActivity
import com.example.tictacfirebase.R


class Notifications {

    private val CHANNELSTRING = "TTTTTT"

    private val NOTIFIYTAG = "new request"
    fun Notify(context: Context, message: String, number: Int) {
        val intent = Intent(context, LoginActivity::class.java)
        val channelId = CHANNELSTRING

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val builder = NotificationCompat.Builder(context)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentTitle("New request")
            .setContentText(message)
            .setNumber(number)
            .setSmallIcon(R.drawable.ic_fire_emoji)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, intent, pendingIntentFlags
                )
            )
            .setAutoCancel(true)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Channel human readable title",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "My channel description"
            channel.enableLights(true)
            channel.lightColor = Color.RED
            channel.enableVibration(true)
            nm.createNotificationChannel(channel)
        }

        nm.notify(NOTIFIYTAG, 0, builder.build())

    }

}
