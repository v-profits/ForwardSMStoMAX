package com.example.forwardsmstomax  // или forwardsmstotelegram

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Фоновая служба для приёма SMS при закрытом приложении
 * Показывает уведомление в статус-баре, чтобы система не убила процесс
 */
class ForegroundService : NotificationListenerService() {

    private val CHANNEL_ID = "foreground_service_channel"

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()      // создаём канал уведомлений
        val notification = buildNotification()

        // Показываем уведомление (чтобы служба жила)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
        // startForeground(1, notification)  // можно включить, если нужно
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Здесь можно обрабатывать уведомления других приложений
        // В данном проекте не используется
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Уведомление удалено
    }

    /**
     * Создаёт канал уведомлений (Android 8+)
     * Без звука и вибрации, чтобы не беспокоить пользователя
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновая служба",                     // имя канала
            NotificationManager.IMPORTANCE_HIGH   // важность
        ).apply {
            setSound(null, null)      // без звука
            enableVibration(false)    // без вибрации
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Создаёт уведомление для статус-бара
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.outline_notifications_24)  // иконка
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSubText("Фоновый режим")                        // текст
            .setShowWhen(false)                                 // не показывать время
            .setOngoing(true)                                   // нельзя смахнуть
            .build()
    }
}