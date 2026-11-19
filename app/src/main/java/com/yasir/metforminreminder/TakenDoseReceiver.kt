package com.yasir.metforminreminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Saves notification Taken actions, including the stock deduction. */
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
        val scheduledDate = if (rawDate == null) {
            // Retain compatibility with notifications created by older versions.
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
                try {
                    // The preferences transaction handles duplicate actions and
                    // deducts one pill only when it inserts a new dose record.
                    preferences.recordDoseTaken(
                        doseNumber = doseNumber,
                        scheduledDate = scheduledDate,
                        takenAtEpochMillis = tappedAt,
                        scheduledTime = scheduledTime
                    )
                } catch (error: Exception) {
                    // Leave the notification visible if saving fails.
                    Log.e(TAG, "Unable to save Taken action", error)
                    return@launch
                }

                // These follow-up operations are independent: failure of one
                // must not prevent the others after the dose was saved.
                try {
                    dismissMatchingNotification(appContext, doseNumber, scheduledDate)
                } catch (error: Exception) {
                    Log.e(TAG, "Dose saved, but notification dismissal failed", error)
                }

                try {
                    if (!AlarmScheduler.refresh(appContext)) {
                        Log.w(TAG, "Dose saved, but exact alarm access is unavailable")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Dose saved, but reminder refresh failed", error)
                }

                try {
                    NotificationHelper.syncStockReminder(appContext, preferences)
                } catch (error: Exception) {
                    // Keep the saved dose. A pending stock alert can be retried
                    // when Home opens or another stock-changing action occurs.
                    Log.e(TAG, "Dose saved, but stock notification update failed", error)
                }
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
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = AlarmScheduler.notificationIdFor(doseNumber)
        val expectedUri = Uri.parse(
            "metformin-reminder://taken/$scheduledDate/$doseNumber"
        )
        val expectedAction = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, TakenDoseReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MARK_TAKEN
                data = expectedUri
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        manager.activeNotifications
            .filter { it.id == id && it.tag == null }
            .forEach { active ->
                val actions = active.notification.actions
                val matchesAction = expectedAction != null &&
                    actions?.any { it.actionIntent == expectedAction } == true

                // Only use the legacy fallback for today's record and an
                // action token with no date URI. Never dismiss a newer dated
                // notification merely because its stable ID was reused.
                val legacyAction = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, TakenDoseReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_MARK_TAKEN
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                val matchesLegacyToday = scheduledDate == LocalDate.now() &&
                    legacyAction != null &&
                    actions?.any { it.actionIntent == legacyAction } == true

                if (matchesAction || matchesLegacyToday) {
                    manager.cancel(id)
                }
            }
    }

    companion object {
        private const val TAG = "TakenDoseReceiver"
    }
}
