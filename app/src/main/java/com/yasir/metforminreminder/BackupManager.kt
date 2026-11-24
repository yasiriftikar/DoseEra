package com.yasir.metforminreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object BackupManager {
    private const val BACKUP_PREFIX = "doseera-backup-"
    private const val BACKUP_EXTENSION = ".json"
    private const val MAX_AUTOMATIC_BACKUPS = 14

    const val DAILY_BACKUP_REQUEST_CODE = 2001
    private const val DAILY_BACKUP_HOUR = 2
    private const val DAILY_BACKUP_MINUTE = 5

    private val fileNameFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.US)

    data class BackupFile(val uri: Uri, val name: String)

    suspend fun createBackup(
        context: Context,
        automatic: Boolean = false
    ): BackupFile = withContext(Dispatchers.IO) {
        val preferences = MedicinePreferences(context.applicationContext)
        val folder = requireNotNull(preferences.getBackupFolderUri()) {
            "Select a backup folder in Settings first"
        }
        val treeUri = Uri.parse(folder)
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val json = preferences.createBackupJson()
        val timestamp = LocalDateTime.now().format(fileNameFormatter)
        val kind = if (automatic) "auto-" else "manual-"
        val name = "$BACKUP_PREFIX$kind$timestamp-${UUID.randomUUID()}$BACKUP_EXTENSION"
        val uri = requireNotNull(
            DocumentsContract.createDocument(resolver, parent, "application/json", name)
        ) { "Could not create backup in the selected folder" }

        try {
            val stream = requireNotNull(resolver.openOutputStream(uri, "w")) {
                "Could not open backup for writing"
            }
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString(2)) }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }

        if (automatic) {
            // Retention failure must not turn a successfully written backup into a failure.
            runCatching { cleanOldBackups(context, treeUri) }
        }
        BackupFile(uri, name)
    }

    private fun cleanOldBackups(context: Context, treeUri: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val backups = mutableListOf<Pair<String, Uri>>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name.startsWith("${BACKUP_PREFIX}auto-") &&
                    name.endsWith(BACKUP_EXTENSION)
                ) {
                    backups.add(name to DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(0)
                    ))
                }
            }
        }
        backups.sortedByDescending { it.first }
            .drop(MAX_AUTOMATIC_BACKUPS)
            .forEach { (_, uri) -> DocumentsContract.deleteDocument(resolver, uri) }
    }

    fun scheduleDaily(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val pendingIntent = dailyBackupPendingIntent(appContext)

        if (!enabled) {
            pendingIntent.cancel()
            return
        }

        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val nextRun = nextDailyBackupTime()
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextRun,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    suspend fun refreshDailyBackup(context: Context) {
        val appContext = context.applicationContext
        val preferences = MedicinePreferences(appContext)
        scheduleDaily(
            appContext,
            preferences.isDailyBackupEnabled() && preferences.getBackupFolderUri() != null
        )
    }

    private fun nextDailyBackupTime(): Long {
        val zone = ZoneId.systemDefault()
        var candidate = LocalDate.now()
            .atTime(DAILY_BACKUP_HOUR, DAILY_BACKUP_MINUTE)
            .atZone(zone)

        if (!candidate.toInstant().isAfter(Instant.now())) {
            candidate = candidate.plusDays(1)
        }

        return candidate.toInstant().toEpochMilli()
    }

    private fun dailyBackupPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BackupReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            DAILY_BACKUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
