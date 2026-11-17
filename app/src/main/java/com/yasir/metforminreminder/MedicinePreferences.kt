package com.yasir.metforminreminder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Keep this name unchanged to preserve existing on-device settings.
private val Context.medicineDataStore by preferencesDataStore(
    name = "medicine_preferences"
)

private val displayTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

data class MedicineSchedule(
    val weekdayDose1: String = "12:00 AM",
    val weekdayDose2: String = "12:00 PM",
    val weekendDose1: String = "12:00 AM",
    val weekendDose2: String = "12:00 PM"
) {
    fun timeFor(date: LocalDate, doseNumber: Int): LocalTime {
        require(doseNumber in 1..2)
        val weekend = date.dayOfWeek.value >= 6
        val text = when {
            weekend && doseNumber == 1 -> weekendDose1
            weekend -> weekendDose2
            doseNumber == 1 -> weekdayDose1
            else -> weekdayDose2
        }
        return LocalTime.parse(text, displayTimeFormatter)
    }
}

data class TakenDose(
    // Date of the scheduled dose, which may differ from the day it was logged.
    val date: String,
    val time: String,
    // Zero means an old timestamp-only record with unknown dose identity.
    val doseNumber: Int = 0,
    val takenAtEpochMillis: Long? = null,
    val scheduledTime: String? = null
)

class MedicinePreferences(context: Context) {
    private val store = context.applicationContext.medicineDataStore

    private object Keys {
        val WEEKDAY_DOSE_1 = stringPreferencesKey("weekday_dose_1")
        val WEEKDAY_DOSE_2 = stringPreferencesKey("weekday_dose_2")
        val WEEKEND_DOSE_1 = stringPreferencesKey("weekend_dose_1")
        val WEEKEND_DOSE_2 = stringPreferencesKey("weekend_dose_2")
        val TAKEN_DOSES = stringSetPreferencesKey("taken_doses")
    }

    val schedule: Flow<MedicineSchedule> = store.data.map { readSchedule(it) }

    val takenDoses: Flow<List<TakenDose>> = store.data.map { prefs ->
        readDoses(prefs)
    }

    suspend fun getSchedule(): MedicineSchedule = schedule.first()

    suspend fun getTakenDoses(): List<TakenDose> = takenDoses.first()

    suspend fun saveWeekdayDose1(value: String) = saveTime(Keys.WEEKDAY_DOSE_1, value)
    suspend fun saveWeekdayDose2(value: String) = saveTime(Keys.WEEKDAY_DOSE_2, value)
    suspend fun saveWeekendDose1(value: String) = saveTime(Keys.WEEKEND_DOSE_1, value)
    suspend fun saveWeekendDose2(value: String) = saveTime(Keys.WEEKEND_DOSE_2, value)

    suspend fun saveSchedule(value: MedicineSchedule) {
        val weekday1 = normalizeTime(value.weekdayDose1)
        val weekday2 = normalizeTime(value.weekdayDose2)
        val weekend1 = normalizeTime(value.weekendDose1)
        val weekend2 = normalizeTime(value.weekendDose2)
        store.edit { prefs ->
            prefs[Keys.WEEKDAY_DOSE_1] = weekday1
            prefs[Keys.WEEKDAY_DOSE_2] = weekday2
            prefs[Keys.WEEKEND_DOSE_1] = weekend1
            prefs[Keys.WEEKEND_DOSE_2] = weekend2
        }
    }

    // Compatibility entry point for the current Home screen and action receiver.
    suspend fun markDoseTaken(doseNumber: Int) {
        recordDoseTaken(doseNumber = doseNumber)
    }

    // Future notification actions pass the original scheduled date explicitly.
    // Returns false if already recorded; the first timestamp is never overwritten.
    suspend fun recordDoseTaken(
        doseNumber: Int,
        scheduledDate: LocalDate = LocalDate.now(),
        takenAtEpochMillis: Long = System.currentTimeMillis(),
        scheduledTime: String? = null
    ): Boolean {
        require(doseNumber in 1..2) { "Dose number must be 1 or 2" }
        require(takenAtEpochMillis >= 0)
        val dateKey = scheduledDate.toString()
        val time = Instant.ofEpochMilli(takenAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(displayTimeFormatter)
        val explicitTime = scheduledTime?.let { normalizeTime(it) }
        var inserted = false

        store.edit { prefs ->
            val current = prefs[Keys.TAKEN_DOSES] ?: emptySet()
            val exists = current.any { raw ->
                val dose = decodeDose(raw)
                dose?.date == dateKey && dose.doseNumber == doseNumber
            }
            if (!exists) {
                val planned = explicitTime ?: readSchedule(prefs)
                    .timeFor(scheduledDate, doseNumber)
                    .format(displayTimeFormatter)
                val encoded = listOf(
                    dateKey,
                    time,
                    doseNumber.toString(),
                    takenAtEpochMillis.toString(),
                    planned
                ).joinToString("|")
                prefs[Keys.TAKEN_DOSES] = current + encoded
                inserted = true
            }
        }
        return inserted
    }

    suspend fun isDoseTaken(date: LocalDate, doseNumber: Int): Boolean {
        require(doseNumber in 1..2)
        return getTakenDoses().any {
            it.date == date.toString() && it.doseNumber == doseNumber
        }
    }

    private suspend fun saveTime(key: Preferences.Key<String>, value: String) {
        val normalized = normalizeTime(value)
        store.edit { it[key] = normalized }
    }

    private fun readSchedule(prefs: Preferences): MedicineSchedule {
        fun read(key: Preferences.Key<String>, fallback: String): String =
            prefs[key]?.let { runCatching { normalizeTime(it) }.getOrNull() }
                ?: fallback
        return MedicineSchedule(
            weekdayDose1 = read(Keys.WEEKDAY_DOSE_1, "12:00 AM"),
            weekdayDose2 = read(Keys.WEEKDAY_DOSE_2, "12:00 PM"),
            weekendDose1 = read(Keys.WEEKEND_DOSE_1, "12:00 AM"),
            weekendDose2 = read(Keys.WEEKEND_DOSE_2, "12:00 PM")
        )
    }

    private fun readDoses(prefs: Preferences): List<TakenDose> =
        (prefs[Keys.TAKEN_DOSES] ?: emptySet())
            .mapNotNull { decodeDose(it) }
            .sortedWith(
                compareByDescending<TakenDose> { it.date }
                    .thenBy { it.doseNumber }
                    .thenBy { it.takenAtEpochMillis ?: 0L }
            )

    // Reads both legacy formats and the extended format, without deleting any data.
    private fun decodeDose(raw: String): TakenDose? = runCatching {
        val parts = raw.split('|')
        require(parts.size == 2 || parts.size == 3 || parts.size == 5)
        val date = LocalDate.parse(parts[0]).toString()
        val time = normalizeTime(parts[1])
        val number = if (parts.size == 2) 0 else parts[2].toInt()
        require(number in 0..2)
        val epoch = if (parts.size == 5) parts[3].toLong() else null
        val planned = if (parts.size == 5) normalizeTime(parts[4]) else null
        TakenDose(date, time, number, epoch, planned)
    }.getOrNull()

    private fun normalizeTime(value: String): String =
        LocalTime.parse(value.trim().uppercase(Locale.US), displayTimeFormatter)
            .format(displayTimeFormatter)
}
