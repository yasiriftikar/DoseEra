package com.yasir.metforminreminder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

// Keep this name unchanged to preserve existing on-device data.
private val Context.medicineDataStore by preferencesDataStore(
    name = "medicine_preferences"
)

private val displayTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)

data class MedicineSchedule(
    val weekdayDose1: String = "12:00 AM",
    val weekdayDose2: String = "12:00 PM",
    val weekendDose1: String = "12:00 AM",
    val weekendDose2: String = "12:00 PM",
    val saturdayDose1: String = weekendDose1,
    val saturdayDose2: String = weekendDose2,
    val sundayDose1: String = weekendDose1,
    val sundayDose2: String = weekendDose2
) {
    fun timeFor(date: LocalDate, doseNumber: Int): LocalTime {
        require(doseNumber in 1..2)

        val day = date.dayOfWeek.value
        val text = when {
            day == 6 && doseNumber == 1 -> saturdayDose1
            day == 6 -> saturdayDose2
            day == 7 && doseNumber == 1 -> sundayDose1
            day == 7 -> sundayDose2
            doseNumber == 1 -> weekdayDose1
            else -> weekdayDose2
        }

        return LocalTime.parse(text, displayTimeFormatter)
    }
}

data class TakenDose(
    val date: String,
    val time: String,
    val doseNumber: Int = 0,
    val takenAtEpochMillis: Long? = null,
    val scheduledTime: String? = null,
    // Only true when this record actually reduced the counter.
    val stockDeducted: Boolean = false
)

enum class StockLevel {
    NOT_SET,
    NORMAL,
    AMBER,
    RED,
    EMPTY
}

enum class StockAlert {
    AMBER,
    RED,
    EMPTY
}

data class MedicineStock(
    // Null means stock tracking has not been set up.
    val remainingPills: Int? = null,
    val amberThreshold: Int = 14,
    val redThreshold: Int = 10,
    val amberAlertSent: Boolean = false,
    val redAlertSent: Boolean = false,
    val emptyAlertSent: Boolean = false
) {
    val isConfigured: Boolean
        get() = remainingPills != null

    val level: StockLevel
        get() {
            val count = remainingPills ?: return StockLevel.NOT_SET
            return when {
                count == 0 -> StockLevel.EMPTY
                count <= redThreshold -> StockLevel.RED
                count <= amberThreshold -> StockLevel.AMBER
                else -> StockLevel.NORMAL
            }
        }

    // Only the most important outstanding alert is returned.
    val pendingAlert: StockAlert?
        get() = when (level) {
            StockLevel.EMPTY ->
                if (!emptyAlertSent) StockAlert.EMPTY else null

            StockLevel.RED ->
                if (!redAlertSent) StockAlert.RED else null

            StockLevel.AMBER ->
                if (!amberAlertSent) StockAlert.AMBER else null

            else -> null
        }
}

enum class UndoDoseResult {
    UNDONE,
    NOT_FOUND,
    TODAY_ONLY,
    UNDO_SECOND_DOSE_FIRST
}

class MedicinePreferences(context: Context) {
    private val store = context.applicationContext.medicineDataStore

    private object Keys {
        val WEEKDAY_DOSE_1 = stringPreferencesKey("weekday_dose_1")
        val WEEKDAY_DOSE_2 = stringPreferencesKey("weekday_dose_2")
        val WEEKEND_DOSE_1 = stringPreferencesKey("weekend_dose_1")
        val WEEKEND_DOSE_2 = stringPreferencesKey("weekend_dose_2")
        val SATURDAY_DOSE_1 = stringPreferencesKey("saturday_dose_1")
        val SATURDAY_DOSE_2 = stringPreferencesKey("saturday_dose_2")
        val SUNDAY_DOSE_1 = stringPreferencesKey("sunday_dose_1")
        val SUNDAY_DOSE_2 = stringPreferencesKey("sunday_dose_2")
        val TAKEN_DOSES = stringSetPreferencesKey("taken_doses")

        val STOCK_COUNT = intPreferencesKey("stock_count")
        val STOCK_AMBER_THRESHOLD =
            intPreferencesKey("stock_amber_threshold")
        val STOCK_RED_THRESHOLD =
            intPreferencesKey("stock_red_threshold")

        val STOCK_AMBER_ALERT_SENT =
            booleanPreferencesKey("stock_amber_alert_sent")
        val STOCK_RED_ALERT_SENT =
            booleanPreferencesKey("stock_red_alert_sent")
        val STOCK_EMPTY_ALERT_SENT =
            booleanPreferencesKey("stock_empty_alert_sent")
    }

    val schedule: Flow<MedicineSchedule> = store.data.map {
        readSchedule(it)
    }

    val takenDoses: Flow<List<TakenDose>> = store.data.map {
        readDoses(it)
    }

    val stock: Flow<MedicineStock> = store.data.map {
        readStock(it)
    }

    suspend fun getSchedule(): MedicineSchedule = schedule.first()

    suspend fun getTakenDoses(): List<TakenDose> = takenDoses.first()

    suspend fun getStock(): MedicineStock = stock.first()

    suspend fun saveWeekdayDose1(value: String) =
        saveTime(Keys.WEEKDAY_DOSE_1, value)

    suspend fun saveWeekdayDose2(value: String) =
        saveTime(Keys.WEEKDAY_DOSE_2, value)

    suspend fun saveWeekendDose1(value: String) =
        saveTime(Keys.WEEKEND_DOSE_1, value)

    suspend fun saveWeekendDose2(value: String) =
        saveTime(Keys.WEEKEND_DOSE_2, value)

    suspend fun saveSaturdayDose1(value: String) =
        saveTime(Keys.SATURDAY_DOSE_1, value)

    suspend fun saveSaturdayDose2(value: String) =
        saveTime(Keys.SATURDAY_DOSE_2, value)

    suspend fun saveSundayDose1(value: String) =
        saveTime(Keys.SUNDAY_DOSE_1, value)

    suspend fun saveSundayDose2(value: String) =
        saveTime(Keys.SUNDAY_DOSE_2, value)

    suspend fun saveSchedule(value: MedicineSchedule) {
        val weekday1 = normalizeTime(value.weekdayDose1)
        val weekday2 = normalizeTime(value.weekdayDose2)
        val weekend1 = normalizeTime(value.weekendDose1)
        val weekend2 = normalizeTime(value.weekendDose2)
        val saturday1 = normalizeTime(value.saturdayDose1)
        val saturday2 = normalizeTime(value.saturdayDose2)
        val sunday1 = normalizeTime(value.sundayDose1)
        val sunday2 = normalizeTime(value.sundayDose2)

        store.edit { prefs ->
            prefs[Keys.WEEKDAY_DOSE_1] = weekday1
            prefs[Keys.WEEKDAY_DOSE_2] = weekday2
            prefs[Keys.WEEKEND_DOSE_1] = weekend1
            prefs[Keys.WEEKEND_DOSE_2] = weekend2
            prefs[Keys.SATURDAY_DOSE_1] = saturday1
            prefs[Keys.SATURDAY_DOSE_2] = saturday2
            prefs[Keys.SUNDAY_DOSE_1] = sunday1
            prefs[Keys.SUNDAY_DOSE_2] = sunday2
        }
    }

    // Compatibility entry point for existing callers.
    suspend fun markDoseTaken(doseNumber: Int) {
        recordDoseTaken(doseNumber = doseNumber)
    }

    /**
     * Records the dose and deducts one pill in the same edit.
     * Duplicate records never deduct stock again.
     * Logging remains available when stock is unset or zero.
     */
    suspend fun recordDoseTaken(
        doseNumber: Int,
        scheduledDate: LocalDate = LocalDate.now(),
        takenAtEpochMillis: Long = System.currentTimeMillis(),
        scheduledTime: String? = null
    ): Boolean {
        require(doseNumber in 1..2) {
            "Dose number must be 1 or 2"
        }
        require(takenAtEpochMillis >= 0)

        val dateKey = scheduledDate.toString()
        val time = Instant.ofEpochMilli(takenAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(displayTimeFormatter)

        val explicitTime = scheduledTime?.let(::normalizeTime)
        var inserted = false

        store.edit { prefs ->
            val current = prefs[Keys.TAKEN_DOSES] ?: emptySet()
            val exists = current.any { raw ->
                val dose = decodeDose(raw)
                dose?.date == dateKey &&
                        dose.doseNumber == doseNumber
            }

            if (!exists) {
                val planned = explicitTime ?: readSchedule(prefs)
                    .timeFor(scheduledDate, doseNumber)
                    .format(displayTimeFormatter)

                val count = prefs[Keys.STOCK_COUNT]
                val deducted = count != null && count > 0

                if (deducted) {
                    prefs[Keys.STOCK_COUNT] = count!! - 1
                }

                val encoded = listOf(
                    dateKey,
                    time,
                    doseNumber.toString(),
                    takenAtEpochMillis.toString(),
                    planned,
                    deducted.toString()
                ).joinToString("|")

                prefs[Keys.TAKEN_DOSES] = current + encoded
                inserted = true
            }
        }

        return inserted
    }

    suspend fun isDoseTaken(
        date: LocalDate,
        doseNumber: Int
    ): Boolean {
        require(doseNumber in 1..2)

        return getTakenDoses().any {
            it.date == date.toString() &&
                    it.doseNumber == doseNumber
        }
    }

    /**
     * Corrects an accidental Taken tap for today's scheduled doses.
     * Dose 2 must be undone before Dose 1.
     *
     * Restores a pill only if this record actually deducted one.
     * Reminder rescheduling is handled by the caller.
     */
    suspend fun undoDoseTaken(
        doseNumber: Int,
        scheduledDate: LocalDate = LocalDate.now()
    ): UndoDoseResult {
        require(doseNumber in 1..2)

        var result = UndoDoseResult.NOT_FOUND

        store.edit { prefs ->
            if (scheduledDate != LocalDate.now()) {
                result = UndoDoseResult.TODAY_ONLY
                return@edit
            }

            val dateKey = scheduledDate.toString()
            val current = prefs[Keys.TAKEN_DOSES] ?: emptySet()

            val matching = current.filter { raw ->
                val dose = decodeDose(raw)
                dose?.date == dateKey &&
                        dose.doseNumber == doseNumber
            }

            if (matching.isEmpty()) {
                return@edit
            }

            val secondDoseTaken = current.any { raw ->
                val dose = decodeDose(raw)
                dose?.date == dateKey && dose.doseNumber == 2
            }

            if (doseNumber == 1 && secondDoseTaken) {
                result = UndoDoseResult.UNDO_SECOND_DOSE_FIRST
                return@edit
            }

            val restorePill = matching.any { raw ->
                decodeDose(raw)?.stockDeducted == true
            }

            val count = prefs[Keys.STOCK_COUNT]
            if (restorePill && count != null) {
                check(count < Int.MAX_VALUE) {
                    "Stock count is too large to restore a pill"
                }
                prefs[Keys.STOCK_COUNT] = count + 1
            }

            prefs[Keys.TAKEN_DOSES] =
                current - matching.toSet()

            // Do not re-arm alerts merely because of Undo.
            // This prevents repeated Take/Undo taps causing alert spam.
            result = UndoDoseResult.UNDONE
        }

        return result
    }

    /**
     * Sets the exact current count, for initial setup or correction.
     * This is not an amount to add.
     */
    suspend fun setStockCount(count: Int) {
        require(count >= 0) {
            "Stock cannot be negative"
        }

        store.edit { prefs ->
            prefs[Keys.STOCK_COUNT] = count
            resetRecoveredAlerts(prefs)
        }
    }

    /**
     * Adds newly supplied pills to an already configured counter.
     */
    suspend fun addStock(amount: Int) {
        require(amount > 0) {
            "Enter at least one pill"
        }

        store.edit { prefs ->
            val count = prefs[Keys.STOCK_COUNT]
            requireNotNull(count) {
                "Set your current stock before adding a resupply"
            }

            require(count <= Int.MAX_VALUE - amount) {
                "Stock count is too large"
            }

            prefs[Keys.STOCK_COUNT] = count + amount
            resetRecoveredAlerts(prefs)
        }
    }

    suspend fun saveStockThresholds(
        amberThreshold: Int,
        redThreshold: Int
    ) {
        require(redThreshold >= 1) {
            "The red threshold must be at least 1"
        }
        require(amberThreshold > redThreshold) {
            "The amber threshold must be higher than red"
        }

        store.edit { prefs ->
            val previous = readStock(prefs)
            val changed =
                previous.amberThreshold != amberThreshold ||
                        previous.redThreshold != redThreshold

            prefs[Keys.STOCK_AMBER_THRESHOLD] = amberThreshold
            prefs[Keys.STOCK_RED_THRESHOLD] = redThreshold

            if (changed) {
                prefs[Keys.STOCK_AMBER_ALERT_SENT] = false
                prefs[Keys.STOCK_RED_ALERT_SENT] = false
                prefs[Keys.STOCK_EMPTY_ALERT_SENT] = false
            }
        }
    }

    /**
     * Call only after a restock notification was successfully posted.
     * A more severe alert also covers less severe levels.
     */
    suspend fun markStockAlertSent(alert: StockAlert) {
        store.edit { prefs ->
            val current = readStock(prefs)

            // Ignore a stale notification result after stock changes.
            if (current.pendingAlert == alert) {
                when (alert) {
                    StockAlert.AMBER -> {
                        prefs[Keys.STOCK_AMBER_ALERT_SENT] = true
                    }

                    StockAlert.RED -> {
                        prefs[Keys.STOCK_AMBER_ALERT_SENT] = true
                        prefs[Keys.STOCK_RED_ALERT_SENT] = true
                    }

                    StockAlert.EMPTY -> {
                        prefs[Keys.STOCK_AMBER_ALERT_SENT] = true
                        prefs[Keys.STOCK_RED_ALERT_SENT] = true
                        prefs[Keys.STOCK_EMPTY_ALERT_SENT] = true
                    }
                }
            }
        }
    }

    private fun resetRecoveredAlerts(
        prefs: androidx.datastore.preferences.core.MutablePreferences
    ) {
        val current = readStock(prefs)
        val count = current.remainingPills ?: return

        if (count > current.amberThreshold) {
            prefs[Keys.STOCK_AMBER_ALERT_SENT] = false
        }
        if (count > current.redThreshold) {
            prefs[Keys.STOCK_RED_ALERT_SENT] = false
        }
        if (count > 0) {
            prefs[Keys.STOCK_EMPTY_ALERT_SENT] = false
        }
    }

    private suspend fun saveTime(
        key: Preferences.Key<String>,
        value: String
    ) {
        val normalized = normalizeTime(value)
        store.edit { it[key] = normalized }
    }

    private fun readSchedule(
        prefs: Preferences
    ): MedicineSchedule {
        fun read(
            key: Preferences.Key<String>,
            fallback: String
        ): String =
            prefs[key]?.let {
                runCatching { normalizeTime(it) }.getOrNull()
            } ?: fallback

        return MedicineSchedule(
            weekdayDose1 = read(Keys.WEEKDAY_DOSE_1, "12:00 AM"),
            weekdayDose2 = read(Keys.WEEKDAY_DOSE_2, "12:00 PM"),
            weekendDose1 = read(Keys.WEEKEND_DOSE_1, "12:00 AM"),
            weekendDose2 = read(Keys.WEEKEND_DOSE_2, "12:00 PM"),
            saturdayDose1 = read(Keys.SATURDAY_DOSE_1, read(Keys.WEEKEND_DOSE_1, "12:00 AM")),
            saturdayDose2 = read(Keys.SATURDAY_DOSE_2, read(Keys.WEEKEND_DOSE_2, "12:00 PM")),
            sundayDose1 = read(Keys.SUNDAY_DOSE_1, read(Keys.WEEKEND_DOSE_1, "12:00 AM")),
            sundayDose2 = read(Keys.SUNDAY_DOSE_2, read(Keys.WEEKEND_DOSE_2, "12:00 PM"))
        )
    }

    private fun readStock(prefs: Preferences): MedicineStock {
        val red = (prefs[Keys.STOCK_RED_THRESHOLD] ?: 10)
            .coerceIn(1, Int.MAX_VALUE - 1)

        val amber = (prefs[Keys.STOCK_AMBER_THRESHOLD] ?: 14)
            .coerceAtLeast(red + 1)

        return MedicineStock(
            remainingPills = prefs[Keys.STOCK_COUNT]
                ?.coerceAtLeast(0),
            amberThreshold = amber,
            redThreshold = red,
            amberAlertSent =
                prefs[Keys.STOCK_AMBER_ALERT_SENT] ?: false,
            redAlertSent =
                prefs[Keys.STOCK_RED_ALERT_SENT] ?: false,
            emptyAlertSent =
                prefs[Keys.STOCK_EMPTY_ALERT_SENT] ?: false
        )
    }

    private fun readDoses(
        prefs: Preferences
    ): List<TakenDose> =
        (prefs[Keys.TAKEN_DOSES] ?: emptySet())
            .mapNotNull(::decodeDose)
            .sortedWith(
                compareByDescending<TakenDose> { it.date }
                    .thenBy { it.doseNumber }
                    .thenBy { it.takenAtEpochMillis ?: 0L }
            )

    // Supports the original 2-, 3- and 5-field records,
    // plus the new 6-field records with stock deduction metadata.
    private fun decodeDose(raw: String): TakenDose? =
        runCatching {
            val parts = raw.split('|')
            require(parts.size in setOf(2, 3, 5, 6))

            val date = LocalDate.parse(parts[0]).toString()
            val time = normalizeTime(parts[1])
            val number =
                if (parts.size == 2) 0 else parts[2].toInt()

            require(number in 0..2)

            val epoch = if (parts.size >= 5) {
                parts[3].toLong().also { require(it >= 0) }
            } else {
                null
            }

            val planned = if (parts.size >= 5) {
                normalizeTime(parts[4])
            } else {
                null
            }

            val deducted = if (parts.size == 6) {
                when (parts[5]) {
                    "true" -> true
                    "false" -> false
                    else -> error("Invalid stock deduction flag")
                }
            } else {
                false
            }

            TakenDose(
                date = date,
                time = time,
                doseNumber = number,
                takenAtEpochMillis = epoch,
                scheduledTime = planned,
                stockDeducted = deducted
            )
        }.getOrNull()

    private fun normalizeTime(value: String): String =
        LocalTime.parse(
            value.trim().uppercase(Locale.US),
            displayTimeFormatter
        ).format(displayTimeFormatter)
}