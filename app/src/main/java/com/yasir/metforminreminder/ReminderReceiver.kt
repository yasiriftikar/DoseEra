package com.yasir.metforminreminder

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives a scheduled dose, displays it if still outstanding, and re-arms alarms. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // Snapshot extras before refreshing: refresh updates the alarm PendingIntents.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val doseNumber = intent.getIntExtra(
            AlarmScheduler.EXTRA_DOSE_NUMBER,
            when (notificationId) {
                1001 -> 1
                1002 -> 2
                1003 -> 3
                else -> 0
            }
        )
        if (doseNumber !in 1..3) {
            Log.w(TAG, "Ignoring alarm without a valid dose identity")
            return
        }
        val rawDate = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULED_DATE)
        val scheduledDate = if (rawDate == null) {
            // Compatibility for alarms created by the old scheduler.
            LocalDate.now()
        } else {
            runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return
        }
        val plannedTime = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationHelper.createChannels(appContext)
                val preferences = MedicinePreferences(appContext)
                val alreadyTaken = preferences.isDoseTaken(scheduledDate, doseNumber)
                val today = LocalDate.now()

                // Do not present stale reminders from previous days as a current dose.
                if (!alreadyTaken && scheduledDate == today && canNotify(appContext)) {
                    val schedule = preferences.getSchedule()
                    val time = plannedTime ?: schedule.timeFor(scheduledDate, doseNumber)
                        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                    postReminder(appContext, doseNumber, scheduledDate, time)

                    // Close the small race where Home saves while the notification posts.
                    if (preferences.isDoseTaken(scheduledDate, doseNumber)) {
                        AlarmScheduler.dismissNotification(appContext, doseNumber)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to process dose reminder", error)
            } finally {
                try {
                    // Always re-arm, even if notifications are denied or this dose was taken.
                    if (!AlarmScheduler.refresh(appContext)) {
                        Log.w(TAG, "Exact alarm access is unavailable; reminders not re-armed")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to re-arm reminders", error)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled()
    }

    private fun postReminder(
        context: Context,
        doseNumber: Int,
        date: LocalDate,
        scheduledTime: String
    ) {
        val notificationId = AlarmScheduler.notificationIdFor(doseNumber)
        val actionIntent = Intent(context, TakenDoseReceiver::class.java).apply {
            action = ACTION_MARK_TAKEN
            // Each date/dose has its own action token, unaffected by tomorrow's alarm.
            data = Uri.parse("metformin-reminder://taken/$date/$doseNumber")
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(AlarmScheduler.EXTRA_DOSE_NUMBER, doseNumber)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_DATE, date.toString())
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, scheduledTime)
        }
        val takenAction = PendingIntent.getBroadcast(
            context,
            notificationId,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "Scheduled for $scheduledTime. Mark Taken after taking your pill."
        val notification = NotificationCompat.Builder(context, NotificationHelper.DOSE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time to take your pill")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Taken", takenAction)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val ACTION_MARK_TAKEN = "com.yasir.metforminreminder.MARK_TAKEN"
        private const val TAG = "ReminderReceiver"
    }
}
