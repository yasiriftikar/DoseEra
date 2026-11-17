package com.yasir.metforminreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Persists a notification's original scheduled dose, then refreshes reminders. */
class TakenDoseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderReceiver.ACTION_MARK_TAKEN) return

        val notificationId = intent.getIntExtra(
            ReminderReceiver.EXTRA_NOTIFICATION_ID,
            -1
        )
        val doseNumber = intent.getIntExtra(
            AlarmScheduler.EXTRA_DOSE_NUMBER,
            when (notificationId) {
                AlarmScheduler.DOSE_1_REQUEST_CODE -> 1
                AlarmScheduler.DOSE_2_REQUEST_CODE -> 2
                else -> 0
            }
        )
        if (doseNumber !in 1..2) {
            Log.w(TAG, "Ignoring Taken action without a valid dose identity")
            return
        }

        val rawDate = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULED_DATE)
        // Legacy actions did not include a scheduled date. New actions always do.
        val scheduledDate = if (rawDate == null) {
            LocalDate.now()
        } else {
            runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: run {
                Log.w(TAG, "Ignoring Taken action with an invalid scheduled date")
                return
            }
        }
        if (scheduledDate.isAfter(LocalDate.now())) {
            Log.w(TAG, "Ignoring Taken action for a future scheduled date")
            return
        }

        val scheduledTime = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME)
        val tappedAt = System.currentTimeMillis()
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = MedicinePreferences(appContext)
                preferences.recordDoseTaken(
                    doseNumber = doseNumber,
                    scheduledDate = scheduledDate,
                    takenAtEpochMillis = tappedAt,
                    scheduledTime = scheduledTime
                )

                // Stable notification IDs are reused daily. Do not dismiss a newer
                // notification if the user activates an old action after midnight.
                dismissMatchingNotification(appContext, doseNumber, scheduledDate)

                // The fresh snapshot skips the recorded date/dose and uses the
                // appropriate weekday or weekend schedule for the next occurrence.
                if (!AlarmScheduler.refresh(appContext)) {
                    Log.w(TAG, "Dose saved, but exact alarm access is unavailable")
                }
            } catch (error: Exception) {
                // Keep the reminder visible if persistence fails; do not imply success.
                Log.e(TAG, "Unable to complete Taken action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun dismissMatchingNotification(
        context: Context,
        doseNumber: Int,
        scheduledDate: LocalDate
    ) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val expectedUri = android.net.Uri.parse(
            "metformin-reminder://taken/$scheduledDate/$doseNumber"
        )
        val id = AlarmScheduler.notificationIdFor(doseNumber)

        // Notification extras carry the original date in newer receiver versions.
        // For the current receiver, the immutable action token identifies that date.
        val expectedAction = android.app.PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, TakenDoseReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MARK_TAKEN
                data = expectedUri
            },
            android.app.PendingIntent.FLAG_NO_CREATE or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        manager.activeNotifications.filter { it.id == id && it.tag == null }
            .forEach { active ->
                val matchesAction = expectedAction != null &&
                    active.notification.actions?.any {
                        it.actionIntent == expectedAction
                    } == true
                val legacyToday = scheduledDate == LocalDate.now() &&
                    active.notification.actions?.any {
                        it.actionIntent == expectedAction
                    } != true
                if (matchesAction || legacyToday) {
                    manager.cancel(id)
                }
            }
    }

    companion object {
        private const val TAG = "TakenDoseReceiver"
    }
}
