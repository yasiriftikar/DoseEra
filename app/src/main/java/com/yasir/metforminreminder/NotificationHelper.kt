package com.yasir.metforminreminder

import android.Manifest
import android.app.Notification
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local notification utilities shared by Home and notification actions. */
object NotificationHelper {
    const val DOSE_CHANNEL_ID = "dose_reminders"
    const val RESTOCK_CHANNEL_ID = "restock_reminders"
    const val RESTOCK_NOTIFICATION_ID = 2001

    private val stockAlertMutex = Mutex()

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val doseChannel = NotificationChannel(
            DOSE_CHANNEL_ID,
            "Dose reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminders for your scheduled pills"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val stockChannel = NotificationChannel(
            RESTOCK_CHANNEL_ID,
            "Restock reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Discreet reminders to check your supply"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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

    fun isDoseChannelEnabled(context: Context): Boolean =
        isChannelEnabled(context, DOSE_CHANNEL_ID)

    fun isRestockChannelEnabled(context: Context): Boolean =
        isChannelEnabled(context, RESTOCK_CHANNEL_ID)

    private fun isChannelEnabled(context: Context, channelId: String): Boolean {
        if (!areNotificationsEnabled(context)) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channelId)
        return channel != null &&
            channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun doseChannelSettingsIntent(context: Context): Intent =
        channelSettingsIntent(context, DOSE_CHANNEL_ID)

    fun restockChannelSettingsIntent(context: Context): Intent =
        channelSettingsIntent(context, RESTOCK_CHANNEL_ID)

    private fun channelSettingsIntent(context: Context, channelId: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
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

    /**
     * Call after recording, undoing, changing stock or changing thresholds,
     * and when returning to the app after notification permission changes.
     * Disabled notifications leave the alert pending for a later attempt.
     */
    suspend fun syncStockReminder(
        context: Context,
        preferences: MedicinePreferences
    ): Boolean = stockAlertMutex.withLock {
        val stock = preferences.getStock()
        if (!stock.isConfigured || stock.level == StockLevel.NORMAL) {
            dismissRestock(context)
            return@withLock false
        }

        // Remove a visible warning if an undo or adjustment changed its level.
        val manager = context.getSystemService(NotificationManager::class.java)
        val visible = manager.activeNotifications.firstOrNull {
            it.id == RESTOCK_NOTIFICATION_ID && it.tag == null
        }
        val visibleLevel = visible?.notification?.extras
            ?.getString(EXTRA_STOCK_LEVEL)
        if (visible != null && visibleLevel != stock.level.name) {
            dismissRestock(context)
        }

        val alert = stock.pendingAlert ?: return@withLock false
        val posted = showRestockReminder(context, stock)
        if (posted) {
            preferences.markStockAlertSent(alert)
        }
        posted
    }

    /** Uses the user's configured thresholds, not hard-coded defaults. */
    fun showRestockReminder(context: Context, stock: MedicineStock): Boolean {
        if (!stock.isConfigured || stock.level == StockLevel.NORMAL) return false
        return postRestockReminder(context, stock.level)
    }

    /** Compatibility overload; new callers should pass MedicineStock. */
    fun showRestockReminder(context: Context, remainingTablets: Int): Boolean {
        require(remainingTablets >= 0)
        return showRestockReminder(
            context,
            MedicineStock(remainingPills = remainingTablets)
        )
    }

    private fun postRestockReminder(context: Context, level: StockLevel): Boolean {
        createChannels(context)
        if (!isRestockChannelEnabled(context)) return false

        val message = when (level) {
            StockLevel.AMBER -> "Your supply is running low. Plan a restock."
            StockLevel.RED -> "Your supply is very low. Arrange a restock soon."
            StockLevel.EMPTY -> "Your recorded supply is empty. Check and update it in DoseEra."
            else -> return false
        }

        val openApp = PendingIntent.getActivity(
            context,
            RESTOCK_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extras = android.os.Bundle().apply {
            putString(EXTRA_STOCK_LEVEL, level.name)
        }
        val publicVersion = NotificationCompat.Builder(context, RESTOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("DoseEra")
            .setContentText("Open DoseEra to review an update.")
            .build()

        val notification = NotificationCompat.Builder(context, RESTOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Supply check")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            // A newly reached level may alert even if the old card is still visible.
            // Persistent alert flags prevent normal repeat posts.
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addExtras(extras)
            .build()

        return try {
            context.getSystemService(NotificationManager::class.java)
                .notify(RESTOCK_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private const val EXTRA_STOCK_LEVEL = "doseera_stock_level"
}
