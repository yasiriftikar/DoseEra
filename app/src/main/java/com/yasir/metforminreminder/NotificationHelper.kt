package com.yasir.metforminreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Local notification utilities. No network access or background service is used. */
object NotificationHelper {
    // Preserve IDs so existing user preferences for these channels remain intact.
    const val DOSE_CHANNEL_ID = "dose_reminders"
    const val RESTOCK_CHANNEL_ID = "restock_reminders"
    const val RESTOCK_NOTIFICATION_ID = 2001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val doseChannel = NotificationChannel(
            DOSE_CHANNEL_ID,
            "Dose reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminders for your scheduled pills"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val stockChannel = NotificationChannel(
            RESTOCK_CHANNEL_ID,
            "Restock reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders when your recorded medicine stock is low"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(listOf(doseChannel, stockChannel))
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun areNotificationsEnabled(context: Context): Boolean =
        hasNotificationPermission(context) &&
            context.getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()

    fun isDoseChannelEnabled(context: Context): Boolean {
        if (!areNotificationsEnabled(context)) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(DOSE_CHANNEL_ID)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun isRestockChannelEnabled(context: Context): Boolean {
        if (!areNotificationsEnabled(context)) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(RESTOCK_CHANNEL_ID)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun doseChannelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, DOSE_CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun dismissDose(context: Context, doseNumber: Int) {
        require(doseNumber in 1..2)
        context.getSystemService(NotificationManager::class.java)
            .cancel(if (doseNumber == 1) 1001 else 1002)
    }

    fun dismissRestock(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(RESTOCK_NOTIFICATION_ID)
    }

    /** Foundation for stock tracking; nothing calls this until stock is implemented. */
    fun showRestockReminder(context: Context, remainingTablets: Int): Boolean {
        require(remainingTablets >= 0)
        createChannels(context)
        if (!isRestockChannelEnabled(context)) return false
        val openApp = PendingIntent.getActivity(
            context,
            RESTOCK_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = if (remainingTablets == 0) {
            "Your recorded stock is empty. Check your supply and arrange a refill."
        } else {
            "Your recorded stock has $remainingTablets tablets remaining. Check your supply and plan a refill."
        }
        val notification = NotificationCompat.Builder(context, RESTOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Metformin stock reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java)
                .notify(RESTOCK_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
