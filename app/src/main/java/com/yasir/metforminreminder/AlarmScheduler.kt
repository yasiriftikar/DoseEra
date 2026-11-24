package com.yasir.metforminreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schedules one upcoming occurrence per dose. The receiver must re-arm after delivery. */
object AlarmScheduler {
    const val DOSE_1_REQUEST_CODE = 1001
    const val DOSE_2_REQUEST_CODE = 1002
    const val DOSE_3_REQUEST_CODE = 1003

    const val EXTRA_DOSE_NUMBER = "extra_dose_number"
    const val EXTRA_SCHEDULED_DATE = "extra_scheduled_date"
    const val EXTRA_SCHEDULED_TIME = "extra_scheduled_time"
    const val EXTRA_SCHEDULED_EPOCH = "extra_scheduled_epoch"

    // Keep the original broadcast identity so old alarms can be replaced/cancelled.
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_MESSAGE = "extra_message"
    private const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    private val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    data class UpcomingDose(
        val doseNumber: Int,
        val scheduledDate: LocalDate,
        val scheduledTime: String,
        val triggerAtMillis: Long
    )

    fun notificationIdFor(doseNumber: Int): Int {
        require(doseNumber in 1..3) { "Dose number must be between 1 and 3" }
        return when (doseNumber) {
            1 -> DOSE_1_REQUEST_CODE
            2 -> DOSE_2_REQUEST_CODE
            else -> DOSE_3_REQUEST_CODE
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
    }

    /** Compatibility overload for the existing Home screen. */
    fun scheduleAll(context: Context, schedule: MedicineSchedule): Boolean =
        scheduleAll(context, schedule, emptyList())

    /** New callers should use this overload with the current taken-dose snapshot. */
    fun scheduleAll(
        context: Context,
        schedule: MedicineSchedule,
        takenDoses: List<TakenDose>
    ): Boolean {
        if (!canScheduleExactAlarms(context)) return false
        val appContext = context.applicationContext
        val now = ZonedDateTime.now()

        val candidates = buildList {
            add(nextOccurrence(schedule, takenDoses, 1, now))
            add(nextOccurrence(schedule, takenDoses, 2, now))

            // Only schedule the third dose when all three of its times are configured.
            // This prevents an incomplete Third Dose configuration from preventing the
            // first two reminders from being scheduled.
            val thirdDoseConfigured =
                schedule.weekdayDose3 != null &&
                        schedule.saturdayDose3 != null &&
                        schedule.sundayDose3 != null

            if (thirdDoseConfigured) {
                runCatching {
                    add(nextOccurrence(schedule, takenDoses, 3, now))
                }
            }
        }

        return try {
            candidates.forEach { scheduleOccurrence(appContext, it) }
            true
        } catch (_: SecurityException) {
            // Access may have been revoked between the check and scheduling.
            cancelAll(appContext)
            false
        }
    }

    /** Reads fresh persistent state; use from action/boot/alarm receivers. */
    suspend fun refresh(context: Context): Boolean {
        val preferences = MedicinePreferences(context.applicationContext)
        return scheduleAll(
            context,
            preferences.getSchedule(),
            preferences.getTakenDoses()
        )
    }

    fun nextOccurrence(
        schedule: MedicineSchedule,
        takenDoses: List<TakenDose>,
        doseNumber: Int,
        now: ZonedDateTime = ZonedDateTime.now()
    ): UpcomingDose {
        require(doseNumber in 1..3)
        val takenDates = takenDoses.asSequence()
            .filter { it.doseNumber == doseNumber }
            .map { it.date }
            .toHashSet()
        var date = now.toLocalDate()
        while (true) {
            // Evaluate each candidate date independently, including Fri/Sat and Sun/Mon.
            val time = schedule.timeFor(date, doseNumber)
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.toInstant().isAfter(now.toInstant()) &&
                date.toString() !in takenDates
            ) {
                return UpcomingDose(
                    doseNumber,
                    date,
                    time.format(formatter),
                    candidate.toInstant().toEpochMilli()
                )
            }
            date = date.plusDays(1)
        }
    }

    fun cancelAll(context: Context) {
        cancelDose(context, 1)
        cancelDose(context, 2)
        cancelDose(context, 3)
    }

    /** Cancels this dose's pending alarm, not its visible notification. */
    fun cancelDose(context: Context, doseNumber: Int) {
        val id = notificationIdFor(doseNumber)
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    /** Safe to call after saving a dose, before refresh(). */
    fun dismissNotification(context: Context, doseNumber: Int) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(notificationIdFor(doseNumber))
    }

    private fun scheduleOccurrence(context: Context, dose: UpcomingDose) {
        val id = notificationIdFor(dose.doseNumber)
        val ordinal = when (dose.doseNumber) {
            1 -> "first"
            2 -> "second"
            else -> "third"
        }
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, "Metformin reminder")
            putExtra(EXTRA_MESSAGE, "Scheduled $ordinal dose: ${dose.scheduledTime}.")
            putExtra(EXTRA_NOTIFICATION_ID, id)
            putExtra(EXTRA_DOSE_NUMBER, dose.doseNumber)
            putExtra(EXTRA_SCHEDULED_DATE, dose.scheduledDate.toString())
            putExtra(EXTRA_SCHEDULED_TIME, dose.scheduledTime)
            putExtra(EXTRA_SCHEDULED_EPOCH, dose.triggerAtMillis)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                dose.triggerAtMillis,
                pending
            )
    }
}