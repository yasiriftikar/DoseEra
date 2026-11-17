package com.yasir.metforminreminder

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Navy = Color(0xFF101A2B)
private val MutedText = Color(0xFF718096)
private val Emerald = Color(0xFF05855F)
private val SoftGreen = Color(0xFFEAF9F4)
private val SoftGray = Color(0xFFF4F7FB)
private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

class MainActivity : ComponentActivity() {
    // Compose observes this on return from notification/exact-alarm Settings.
    private var resumeVersion by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MetforminReminderApp(resumeVersion) }
    }

    override fun onResume() {
        super.onResume()
        resumeVersion++
    }
}

@Composable
private fun MetforminReminderApp(resumeVersion: Int) {
    val context = LocalContext.current
    val preferences = remember { MedicinePreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val schedule by preferences.schedule.collectAsState(initial = null)
    val records by preferences.takenDoses.collectAsState(initial = null)
    var selectedTab by remember { mutableStateOf("Home") }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    var exactEnabled by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableStateOf(0) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionRevision++ }
    val exactPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionRevision++ }

    fun requestExactPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                exactPermission.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Exception) {
                scope.launch { snackbar.showSnackbar("Open app Settings to allow alarms and reminders.") }
            }
        }
    }

    LaunchedEffect(Unit) {
        NotificationHelper.createChannels(context)
        while (isActive) {
            today = LocalDate.now()
            delay(15_000)
        }
    }

    LaunchedEffect(schedule, records, resumeVersion, permissionRevision, today) {
        NotificationHelper.createChannels(context)
        today = LocalDate.now()
        notificationsEnabled = NotificationHelper.isDoseChannelEnabled(context)
        exactEnabled = AlarmScheduler.canScheduleExactAlarms(context)
        val loadedSchedule = schedule
        val loadedRecords = records
        if (loadedSchedule != null && loadedRecords != null) {
            try {
                exactEnabled = AlarmScheduler.scheduleAll(context, loadedSchedule, loadedRecords)
            } catch (_: Exception) {
                snackbar.showSnackbar("Unable to schedule reminders. Check Settings.")
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Emerald, background = Color.White)) {
        Scaffold(
            containerColor = Color.White,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!showSettings) {
                    NavigationBar(containerColor = Color.White) {
                        listOf("Home", "History", "Stats").forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        when (tab) {
                                            "Home" -> Icons.Default.Home
                                            "History" -> Icons.Default.CalendarMonth
                                            else -> Icons.Default.BarChart
                                        }, tab
                                    )
                                },
                                label = { Text(tab) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Emerald,
                                    selectedTextColor = Emerald,
                                    indicatorColor = SoftGreen,
                                    unselectedIconColor = MutedText,
                                    unselectedTextColor = MutedText
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val currentSchedule = schedule
            val currentRecords = records
            if (currentSchedule == null || currentRecords == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Emerald)
                }
            } else if (showSettings) {
                SettingsScreen(
                    modifier = Modifier.padding(padding),
                    schedule = currentSchedule,
                    notificationsEnabled = notificationsEnabled,
                    exactEnabled = exactEnabled,
                    onBack = { showSettings = false },
                    onNotifications = {
                        if (!NotificationHelper.hasNotificationPermission(context) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            try {
                                context.startActivity(NotificationHelper.doseChannelSettingsIntent(context))
                            } catch (_: Exception) {
                                context.startActivity(NotificationHelper.notificationSettingsIntent(context))
                            }
                        }
                    },
                    onExactAlarms = { requestExactPermission() },
                    onSave = { number, weekend, time ->
                        scope.launch {
                            try {
                                when {
                                    weekend && number == 1 -> preferences.saveWeekendDose1(time)
                                    weekend -> preferences.saveWeekendDose2(time)
                                    number == 1 -> preferences.saveWeekdayDose1(time)
                                    else -> preferences.saveWeekdayDose2(time)
                                }
                                snackbar.showSnackbar("Schedule saved")
                            } catch (_: Exception) {
                                snackbar.showSnackbar("Could not save schedule")
                            }
                        }
                    }
                )
            } else {
                when (selectedTab) {
                    "History" -> HistoryScreen(Modifier.padding(padding), currentSchedule, currentRecords, today)
                    "Stats" -> StatsScreen(Modifier.padding(padding), currentRecords, today)
                    else -> HomeScreen(
                        modifier = Modifier.padding(padding),
                        schedule = currentSchedule,
                        records = currentRecords,
                        today = today,
                        busy = busy,
                        remindersReady = notificationsEnabled && exactEnabled,
                        onSettings = { showSettings = true },
                        onTake = { number ->
                            if (!busy) {
                                busy = true
                                // Capture the visible scheduled day before asynchronous work.
                                val date = today
                                val plannedTime = currentSchedule.timeFor(date, number).format(TimeFormat)
                                scope.launch {
                                    try {
                                        val inserted = preferences.recordDoseTaken(
                                            doseNumber = number,
                                            scheduledDate = date,
                                            scheduledTime = plannedTime
                                        )
                                        AlarmScheduler.dismissNotification(context, number)
                                        val armed = AlarmScheduler.refresh(context)
                                        snackbar.showSnackbar(
                                            when {
                                                !inserted -> "This dose was already recorded"
                                                !armed -> "Dose saved. Enable alarms in Settings for reminders."
                                                else -> "Dose $number recorded as taken"
                                            }
                                        )
                                    } catch (_: Exception) {
                                        snackbar.showSnackbar("Could not complete the action. Check History before retrying.")
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    schedule: MedicineSchedule,
    records: List<TakenDose>,
    today: LocalDate,
    busy: Boolean,
    remindersReady: Boolean,
    onSettings: () -> Unit,
    onTake: (Int) -> Unit
) {
    val todayRecords = records.filter { it.date == today.toString() && it.doseNumber in 1..2 }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.app_name),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy
                    )
                    Text("Stay consistent. Stay healthy.", fontSize = 18.sp, color = MutedText)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, "Settings", tint = Navy, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (!remindersReady) {
            item {
                Card(onClick = onSettings, colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                    Text("Reminders need attention. Tap to review permissions.", Modifier.padding(16.dp), color = Navy)
                }
            }
        }
        items(listOf(1, 2)) { number ->
            val taken = todayRecords.firstOrNull { it.doseNumber == number }
            val available = number == 1 || todayRecords.any { it.doseNumber == 1 }
            DoseCard(
                title = if (number == 1) "First Dose" else "Second Dose",
                time = schedule.timeFor(today, number).format(TimeFormat),
                primary = number == 1,
                taken = taken,
                available = available,
                busy = busy,
                remindersReady = remindersReady,
                onTake = { onTake(number) }
            )
        }
        item {
            Text("Today · ${todayRecords.map { it.doseNumber }.distinct().size}/2 recorded",
                fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(12.dp))
            Text("Mark Taken only after taking your prescribed dose. Button availability is not dosing advice.",
                color = MutedText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DoseCard(
    title: String,
    time: String,
    primary: Boolean,
    taken: TakenDose?,
    available: Boolean,
    busy: Boolean,
    remindersReady: Boolean,
    onTake: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (primary) SoftGreen else SoftGray)
    ) {
        Column(Modifier.padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (primary) Icons.Default.NotificationsNone else Icons.Default.NotificationsNone,
                    null, tint = if (primary) Emerald else MutedText, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = if (primary) Emerald else MutedText)
            }
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = Navy,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (available || taken != null) {
                    Button(
                        onClick = onTake,
                        enabled = taken == null && !busy,
                        modifier = Modifier.width(108.dp).height(60.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Emerald,
                            disabledContainerColor = Emerald.copy(alpha = 0.15f),
                            disabledContentColor = Emerald
                        )
                    ) {
                        Text(if (taken != null) "Taken" else "Take", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFE5EBF2)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.NotificationsNone, null, tint = Navy)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (remindersReady) "Reminder On" else "Check Settings", color = MutedText, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(if (taken != null) "Taken at ${taken.time}" else "Scheduled dose",
                color = if (taken != null) Emerald else MutedText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier,
    schedule: MedicineSchedule,
    records: List<TakenDose>,
    today: LocalDate
) {
    val dates = (records.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        + (0L..6L).map { today.minusDays(it) }).distinct().sortedDescending()
    LazyColumn(
        modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("History", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("Your medication record", fontSize = 18.sp, color = MutedText)
            Spacer(Modifier.height(16.dp))
        }
        items(dates, key = { it.toString() }) { date ->
            val dayRecords = records.filter { it.date == date.toString() }
            Card(shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                    for (number in 1..2) {
                        val record = dayRecords.firstOrNull { it.doseNumber == number }
                        val planned = record?.scheduledTime ?: schedule.timeFor(date, number).format(TimeFormat)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (record != null) SoftGreen else Color.White).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(36.dp).clip(CircleShape)
                                .background(if (record != null) Emerald else Color(0xFFE2E8F0)),
                                contentAlignment = Alignment.Center) {
                                if (record != null) Icon(Icons.Default.Check, "Taken", tint = Color.White)
                                else Text(number.toString(), color = MutedText)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Dose $number · Scheduled $planned", color = Navy, fontWeight = FontWeight.Medium)
                                Text(if (record != null) "Taken at ${record.time}" else "No taken record",
                                    color = if (record != null) Emerald else MutedText)
                            }
                        }
                    }
                    dayRecords.filter { it.doseNumber == 0 }.forEach {
                        Text("Legacy record · ${it.time} · dose unspecified", color = MutedText, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Text("Last 7 dates plus dates with saved records. Unrecorded slots are not proof of missed doses. Older entries without a saved schedule use your current settings.",
                color = MutedText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatsScreen(modifier: Modifier, records: List<TakenDose>, today: LocalDate) {
    val start = today.minusDays(6)
    val count = records.filter {
        val date = runCatching { LocalDate.parse(it.date) }.getOrNull()
        date != null && !date.isBefore(start) && !date.isAfter(today) && it.doseNumber in 1..2
    }.distinctBy { it.date to it.doseNumber }.size
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Stats", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Navy)
        Card(colors = CardDefaults.cardColors(containerColor = SoftGreen)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Last 7 calendar dates", color = MutedText)
                Text("$count recorded doses", color = Emerald, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("This counts saved records only. It does not infer missed doses or calculate medication adherence.", color = MutedText)
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    schedule: MedicineSchedule,
    notificationsEnabled: Boolean,
    exactEnabled: Boolean,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    onSave: (Int, Boolean, String) -> Unit
) {
    val context = LocalContext.current
    fun pickTime(number: Int, weekend: Boolean, value: String) {
        val time = runCatching { LocalTime.parse(value, TimeFormat) }.getOrDefault(LocalTime.NOON)
        TimePickerDialog(context, { _, hour, minute ->
            onSave(number, weekend, LocalTime.of(hour, minute).format(TimeFormat))
        }, time.hour, time.minute, false).show()
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Navy) }
                Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Navy)
            }
        }
        item {
            Text("Reminder access", color = Navy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            SettingsRow("Dose notifications", if (notificationsEnabled) "Enabled" else "Review", onNotifications)
            SettingsRow("Exact alarms", if (exactEnabled) "Allowed" else "Allow", onExactAlarms)
        }
        item {
            Text("Weekday schedule", color = Navy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            SettingsRow("Dose 1", schedule.weekdayDose1) { pickTime(1, false, schedule.weekdayDose1) }
            SettingsRow("Dose 2", schedule.weekdayDose2) { pickTime(2, false, schedule.weekdayDose2) }
        }
        item {
            Text("Weekend schedule", color = Navy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            SettingsRow("Dose 1", schedule.weekendDose1) { pickTime(1, true, schedule.weekendDose1) }
            SettingsRow("Dose 2", schedule.weekendDose2) { pickTime(2, true, schedule.weekendDose2) }
        }
        item {
            Text("Set times to match your prescription. Schedule changes apply to future alarms, not saved taken timestamps. Force-stopping the app can prevent reminders until you open it again.",
                color = MutedText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Navy, fontSize = 17.sp)
        Text(value, color = Emerald, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
