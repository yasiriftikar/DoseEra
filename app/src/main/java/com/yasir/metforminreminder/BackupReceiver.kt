package com.yasir.metforminreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val preferences = MedicinePreferences(appContext)
                if (preferences.isDailyBackupEnabled()) {
                    BackupManager.createBackup(appContext, automatic = true)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Daily backup failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BackupReceiver"
    }
}
