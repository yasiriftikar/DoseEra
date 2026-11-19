package com.yasir.metforminreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores future dose alarms after system events.
 * Register in AndroidManifest.xml; see the manifest instructions supplied later.
 * Uses credential-protected DataStore, so it is NOT a direct-boot receiver.
 */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationHelper.createChannels(appContext)
                val restored = AlarmScheduler.refresh(appContext)
                if (restored) {
                    Log.i(TAG, "Upcoming reminders restored after $action")
                } else {
                    Log.w(TAG, "Reminders not restored: exact alarm access unavailable")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to restore reminders after $action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderRestore"

        // Literal for the Android 12+ action keeps the allowlist simple across APIs.
        private const val EXACT_ALARM_ACCESS_GRANTED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_USER_UNLOCKED,
            EXACT_ALARM_ACCESS_GRANTED
        )
    }
}
