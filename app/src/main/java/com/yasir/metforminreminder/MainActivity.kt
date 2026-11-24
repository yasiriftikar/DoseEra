package com.yasir.metforminreminder

import android.Manifest
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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

private val LocalDarkMode = staticCompositionLocalOf { false }
private val Navy: Color
    @Composable get() = if (LocalDarkMode.current) Color(0xFFE8EDF5) else Color(0xFF101A2B)
private val MutedText: Color
    @Composable get() = if (LocalDarkMode.current) Color(0xFFA9B5C5) else Color(0xFF718096)
private val AppBackground: Color
    @Composable get() = if (LocalDarkMode.current) Color(0xFF11161D) else Color.White
private val TakenBackground: Color
    @Composable get() = if (LocalDarkMode.current) Color(0xFF16392F) else Color(0xFFEAF9F4)
private data class DosePalette(val accent: Color, val soft: Color)
private val LocalDosePalette = staticCompositionLocalOf {
    DosePalette(Color(0xFF05855F), Color(0xFFEAF9F4))
}
private val Emerald: Color
    @Composable get() = LocalDosePalette.current.accent
private val SoftGreen: Color
    @Composable get() = LocalDosePalette.current.soft
private val SoftGray: Color
    @Composable get() = if (LocalDarkMode.current) Color(0xFF1D2632) else Color(0xFFF4F7FB)
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
    val stock by preferences.stock.collectAsState(initial = null)
    val savedDarkMode by preferences.darkMode.collectAsState(initial = null)
    val dailyBackupEnabled by preferences.dailyBackupEnabled.collectAsState(initial = false)
    val backupFolderUri by preferences.backupFolderUri.collectAsState(initial = null)
    val darkMode = savedDarkMode == true
    var selectedTab by remember { mutableStateOf("Home") }
    var showSettings by remember { mutableStateOf(false) }
    var stockDialog by remember { mutableStateOf(false) }
    var undoNumber by remember { mutableStateOf<Int?>(null) }
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

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    val backupFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    preferences.saveBackupFolderUri(uri.toString())
                    BackupManager.refreshDailyBackup(context)
                    message("Backup folder saved")
                } catch (_: Exception) {
                    message("Could not save folder access. Select another folder and try again.")
                }
            }
        }
    }

    LaunchedEffect(resumeVersion) {
        try {
            BackupManager.refreshDailyBackup(context)
        } catch (_: Exception) {
            message("Daily backup scheduling needs attention in Settings.")
        }
    }

    fun createBackup() {
        if (busy) return
        if (backupFolderUri == null) {
            message("Select a backup folder first, then tap Create backup now.")
            backupFolderPicker.launch(null)
            return
        }
        busy = true

        scope.launch {
            try {
                val file = BackupManager.createBackup(context)
                message("Backup saved: ${file.name}")
            } catch (error: Exception) {
                message("Could not create backup. Check the selected folder, its access and storage space.")
            } finally {
                busy = false
            }
        }
    }

    fun changeDailyBackup(enabled: Boolean) {
        if (enabled && backupFolderUri == null) {
            message("Select a backup folder first, then enable Daily backup.")
            backupFolderPicker.launch(null)
            return
        }
        scope.launch {
            try {
                preferences.saveDailyBackupEnabled(enabled)
                BackupManager.scheduleDaily(context, enabled)
                message(
                    if (enabled) {
                        "Daily backup enabled. Backups run around 2:05 AM."
                    } else {
                        "Daily backup disabled."
                    }
                )
            } catch (error: Exception) {
                message("Could not save backup setting.")
            }
        }
    }

    suspend fun refreshAfterChange() {
        try { AlarmScheduler.refresh(context) }
        catch (_: Exception) { message("Saved. Reminder scheduling needs attention in Settings.") }
        try { NotificationHelper.syncStockReminder(context, preferences) }
        catch (_: Exception) { message("Saved. Supply alert could not be updated.") }
    }

    fun undo(number: Int, date: LocalDate) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = preferences.undoDoseTaken(number, date)
                if (result == UndoDoseResult.UNDONE) refreshAfterChange()
                message(when (result) {
                    UndoDoseResult.UNDONE -> "Taken record undone. Stock corrected if a pill was deducted."
                    UndoDoseResult.NOT_FOUND -> "That record was already removed."
                    UndoDoseResult.TODAY_ONLY -> "Only today's doses can be undone."
                    UndoDoseResult.UNDO_SECOND_DOSE_FIRST -> "Undo the second dose first."
                    UndoDoseResult.UNDO_THIRD_DOSE_FIRST -> "Undo the third dose first."
                })
            } catch (_: Exception) {
                message("Could not undo. Check Home and stock before retrying.")
            } finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) { today = LocalDate.now(); delay(15_000) }
    }
    LaunchedEffect(schedule, records, resumeVersion, permissionRevision, today) {
        NotificationHelper.createChannels(context)
        today = LocalDate.now()
        notificationsEnabled = NotificationHelper.isDoseChannelEnabled(context)
        exactEnabled = AlarmScheduler.canScheduleExactAlarms(context)
        val loadedSchedule = schedule
        val loadedRecords = records
        if (loadedSchedule != null && loadedRecords != null) {
            try { exactEnabled = AlarmScheduler.scheduleAll(context, loadedSchedule, loadedRecords) }
            catch (_: Exception) { message("Unable to schedule reminders. Check Settings.") }
        }
    }
    LaunchedEffect(stock, resumeVersion, permissionRevision) {
        if (stock != null) {
            try { NotificationHelper.syncStockReminder(context, preferences) }
            catch (_: Exception) { message("Supply alert could not be updated.") }
        }
    }
    val palette = when (stock?.level) {
        StockLevel.AMBER -> DosePalette(Color(0xFF996400), Color(0xFFFFF3D6))
        StockLevel.RED, StockLevel.EMPTY -> DosePalette(Color(0xFFBA2727), Color(0xFFFFEAEA))
        else -> DosePalette(Color(0xFF05855F), Color(0xFFEAF9F4))
    }
    val activePalette = if (darkMode) when (stock?.level) {
        StockLevel.AMBER -> DosePalette(Color(0xFFF1C56B), Color(0xFF3B3020))
        StockLevel.RED, StockLevel.EMPTY -> DosePalette(Color(0xFFFF9999), Color(0xFF3D242A))
        else -> DosePalette(Color(0xFF65D9AF), Color(0xFF16392F))
    } else palette
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        val window = (context as? android.app.Activity)?.window
        if (window != null && !view.isInEditMode) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkMode
            controller.isAppearanceLightNavigationBars = !darkMode
            @Suppress("DEPRECATION")
            window.statusBarColor = if (darkMode) 0xFF11161D.toInt() else android.graphics.Color.WHITE
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (darkMode) 0xFF11161D.toInt() else android.graphics.Color.WHITE
        }
    }
    CompositionLocalProvider(LocalDosePalette provides activePalette, LocalDarkMode provides darkMode) {
        val colors = if (darkMode) darkColorScheme(
            primary = Emerald, onPrimary = Color(0xFF10221B),
            primaryContainer = SoftGreen, onPrimaryContainer = Navy,
            secondary = Emerald, onSecondary = Color(0xFF10221B),
            secondaryContainer = SoftGreen, onSecondaryContainer = Navy,
            background = AppBackground, onBackground = Navy,
            surface = SoftGray, onSurface = Navy,
            surfaceVariant = Color(0xFF293440), onSurfaceVariant = MutedText,
            outline = Color(0xFF7E8B9B), outlineVariant = Color(0xFF344150)
        ) else lightColorScheme(primary = Emerald, background = AppBackground)
        MaterialTheme(colorScheme = colors) {
            Scaffold(
                containerColor = AppBackground,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!showSettings) NavigationBar(containerColor = AppBackground) {
                        listOf("Home", "History", "Stats").forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = { Icon(when (tab) {
                                    "Home" -> Icons.Default.Home
                                    "History" -> Icons.Default.CalendarMonth
                                    else -> Icons.Default.BarChart
                                }, tab) },
                                label = { Text(tab) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Emerald, selectedTextColor = Emerald,
                                    indicatorColor = SoftGreen,
                                    unselectedIconColor = MutedText, unselectedTextColor = MutedText
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                val cs = schedule
                val cr = records
                val st = stock
                if (cs == null || cr == null || st == null) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (showSettings) {
                    SettingsScreen(
                        modifier = Modifier.padding(padding), schedule = cs,
                        notificationsEnabled = notificationsEnabled, exactEnabled = exactEnabled,
                        stock = st,
                        darkMode = darkMode,
                    dailyBackupEnabled = dailyBackupEnabled,
                    backupFolderUri = backupFolderUri,
                    onSelectBackupFolder = {
                        backupFolderPicker.launch(backupFolderUri?.let { Uri.parse(it) })
                    },
                    onCreateBackup = { createBackup() },
                    onDailyBackupChange = { enabled -> changeDailyBackup(enabled) },
                        onDarkModeChange = { enabled -> scope.launch {
                            try { preferences.saveDarkMode(enabled) }
                            catch (_: Exception) { message("Could not save appearance setting") }
                        } },
                        restockEnabled = run {
                            resumeVersion
                            permissionRevision
                            NotificationHelper.isRestockChannelEnabled(context)
                        },
                        onStock = { stockDialog = true },
                        onRestockNotifications = {
                            if (!NotificationHelper.hasNotificationPermission(context) &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                try { context.startActivity(NotificationHelper.restockChannelSettingsIntent(context)) }
                                catch (_: Exception) { message("Open Android notification settings for DoseEra.") }
                            }
                        },
                        onBack = { showSettings = false },
                        onNotifications = {
                            if (!NotificationHelper.hasNotificationPermission(context) &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                try { context.startActivity(NotificationHelper.doseChannelSettingsIntent(context)) }
                                catch (_: Exception) { message("Open Android notification settings for DoseEra.") }
                            }
                        },
                        onExactAlarms = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try { exactPermission.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"))) }
                                catch (_: Exception) { message("Open Android settings to allow alarms.") }
                            }
                        },
                        onSave = { number, day, time -> scope.launch {
                            try {
                                when {
                                    day == 6 && number == 1 -> preferences.saveSaturdayDose1(time)
                                    day == 6 -> preferences.saveSaturdayDose2(time)
                                    day == 7 && number == 1 -> preferences.saveSundayDose1(time)
                                    day == 7 -> preferences.saveSundayDose2(time)
                                    number == 1 -> preferences.saveWeekdayDose1(time)
                                    else -> preferences.saveWeekdayDose2(time)
                                }
                                message("Schedule saved")
                            } catch (_: Exception) { message("Could not save schedule") }
                        } },
                        onThirdDoseChange = { enabled, weekday, saturday, sunday -> scope.launch {
                            try {
                                preferences.configureThirdDose(enabled, weekday, saturday, sunday)
                                message(if (enabled) "Third dose enabled from tomorrow" else "Third dose disabled from tomorrow")
                            } catch (_: Exception) { message("Could not save third-dose setting") }
                        } }
                    )
                } else when (selectedTab) {
                    "History" -> HistoryScreen(Modifier.padding(padding), cs, cr, today)
                    "Stats" -> StatsScreen(Modifier.padding(padding), cr, today)
                    else -> HomeScreen(
                        modifier = Modifier.padding(padding), schedule = cs, records = cr,
                        today = today, busy = busy, remindersReady = notificationsEnabled && exactEnabled,
                        stock = st, onStock = { stockDialog = true },
                        onUndo = { undoNumber = it }, onSettings = { showSettings = true },
                        onTake = { number ->
                            if (!busy) {
                                busy = true
                                val date = today
                                scope.launch {
                                    var inserted = false
                                    try {
                                        inserted = preferences.recordDoseTaken(number, date,
                                            scheduledTime = cs.timeFor(date, number).format(TimeFormat))
                                    } catch (_: Exception) {
                                        message("Could not save. Check History and stock before retrying.")
                                        busy = false
                                        return@launch
                                    }
                                    try { AlarmScheduler.dismissNotification(context, number) }
                                    catch (_: Exception) { message("Saved, but notification dismissal failed.") }
                                    refreshAfterChange()
                                    busy = false
                                    if (inserted) {
                                        val result = snackbar.showSnackbar(
                                            "Dose $number recorded", actionLabel = "Undo",
                                            withDismissAction = true, duration = SnackbarDuration.Long)
                                        if (result == SnackbarResult.ActionPerformed) undo(number, date)
                                    } else message("This dose was already recorded")
                                }
                            }
                        }
                    )
                }
            }
            if (stockDialog && stock != null) StockEditor(
                stock = stock!!, busy = busy,
                onDismiss = { if (!busy) stockDialog = false },
                onSave = { count, add, amber, red ->
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                // Apply count first so threshold changes use the new count.
                                if (count != null) {
                                    if (add) preferences.addStock(count) else preferences.setStockCount(count)
                                }
                                preferences.saveStockThresholds(amber, red)
                                stockDialog = false
                                message("Stock settings saved")
                            } catch (error: Exception) {
                                message(error.message ?: "Could not save. Check stock before retrying.")
                            } finally { busy = false }
                        }
                    }
                }
            )
            val number = undoNumber
            if (number != null) AlertDialog(
                onDismissRequest = { undoNumber = null },
                title = { Text("Undo dose $number?") },
                text = { Text("Use this only to correct an accidental Taken tap, not to record another dose. A deducted pill will be returned to the counter.") },
                confirmButton = { TextButton(onClick = {
                    undoNumber = null; undo(number, today)
                }, enabled = !busy) { Text("Undo Taken") } },
                dismissButton = { TextButton(onClick = { undoNumber = null }) { Text("Cancel") } }
            )
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
    stock: MedicineStock,
    onStock: () -> Unit,
    onUndo: (Int) -> Unit,
    onTake: (Int) -> Unit
) {
    val todayRecords = records.filter { it.date == today.toString() && it.doseNumber in 1..3 }
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
        item(key = "stock") {
            StockBar(stock, todayRecords, schedule.doseCountFor(today), onStock)
        }
        if (!remindersReady) {
            item {
                Card(onClick = onSettings, colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                    Text("Reminders need attention. Tap to review permissions.", Modifier.padding(16.dp), color = Navy)
                }
            }
        }
        val doseCount = schedule.doseCountFor(today)
        for (number in 1..doseCount) {
            item(key = "dose-$number") {
                val taken = todayRecords.firstOrNull { it.doseNumber == number }
                val available = number == 1 ||
                    (1 until number).all { previous ->
                        todayRecords.any { record -> record.doseNumber == previous }
                    }
                DoseCard(
                    title = when (number) {
                        1 -> "First Dose"
                        2 -> "Second Dose"
                        else -> "Third Dose"
                    },
                    time = schedule.timeFor(today, number).format(TimeFormat),
                    primary = number == 1,
                    taken = taken,
                    available = available,
                    busy = busy,
                    remindersReady = remindersReady,
                    onUndo = { onUndo(number) },
                    canUndo = number == doseCount ||
                        (number + 1..doseCount).all { later ->
                            todayRecords.none { record -> record.doseNumber == later }
                        },
                    onTake = { onTake(number) }
                )
            }
        }
        item {
            Text("Today · ${todayRecords.map { it.doseNumber }.distinct().size}/$doseCount recorded",
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
    onUndo: () -> Unit,
    canUndo: Boolean,
    onTake: () -> Unit
) {
    var undoRevealVersion by remember(taken) { mutableStateOf(0) }
    var showUndo by remember(taken) { mutableStateOf(false) }
    var cardOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var takeButtonBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    LaunchedEffect(taken, undoRevealVersion) {
        showUndo = false
        val record = taken ?: return@LaunchedEffect
        val elapsed = record.takenAtEpochMillis?.let { System.currentTimeMillis() - it }
        val visibleMillis = if (undoRevealVersion > 0) {
            5_000L
        } else if (elapsed != null && elapsed in 0L until 5_000L) {
            5_000L - elapsed
        } else {
            return@LaunchedEffect
        }
        showUndo = true
        delay(visibleMillis)
        showUndo = false
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { cardOrigin = it.boundsInRoot().topLeft }
            .pointerInput(taken, busy) {
                detectTapGestures { position ->
                    if (taken != null && !busy &&
                        takeButtonBounds?.contains(cardOrigin + position) != true) {
                        undoRevealVersion++
                    }
                }
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (taken != null) TakenBackground else SoftGray)
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
                        modifier = Modifier.width(108.dp).height(60.dp)
                            .onGloballyPositioned { takeButtonBounds = it.boundsInRoot() },
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
                        Box(Modifier.size(64.dp).clip(CircleShape).background(if (LocalDarkMode.current) Color(0xFF2B3745) else Color(0xFFE5EBF2)),
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
            if (taken != null && showUndo) {
                TextButton(onClick = onUndo, enabled = canUndo && !busy) { Text("Undo Taken") }
                if (!canUndo) Text("Undo the second dose first", color = MutedText, fontSize = 12.sp)
            }
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
    var displayedMonth by remember { mutableStateOf(java.time.YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    val dark = LocalDarkMode.current
    val partialGreen = if (dark) Color(0xFFB5DDC8) else Color(0xFF8EC7AB)
    val completeGreen = if (dark) Color(0xFF65D9AF) else Color(0xFF05855F)
    val historyTakenBackground = if (dark) Color(0xFF16392F) else Color(0xFFEAF9F4)
    val counts = remember(records) {
        records.filter { it.doseNumber in 1..3 }
            .groupBy { it.date }
            .mapValues { (_, doses) -> doses.map { it.doseNumber }.distinct().size }
    }
    val firstDay = displayedMonth.atDay(1)
    val offset = firstDay.dayOfWeek.value - 1
    val weeks = (offset + displayedMonth.lengthOfMonth() + 6) / 7
    LazyColumn(
        modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("History", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("Your dose calendar", fontSize = 18.sp, color = MutedText)
        }
        item {
            Card(shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            displayedMonth = displayedMonth.minusMonths(1)
                            selectedDate = displayedMonth.atDay(minOf(selectedDate.dayOfMonth, displayedMonth.lengthOfMonth()))
                        }) { Icon(Icons.Default.ChevronLeft, "Previous month", tint = Navy) }
                        Text(displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                            modifier = Modifier.weight(1f), color = Navy, fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = {
                            displayedMonth = displayedMonth.plusMonths(1)
                            selectedDate = displayedMonth.atDay(minOf(selectedDate.dayOfMonth, displayedMonth.lengthOfMonth()))
                        }) { Icon(Icons.Default.ChevronRight, "Next month", tint = Navy) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        (1..7).forEach { day ->
                            Text(java.time.DayOfWeek.of(day).getDisplayName(
                                java.time.format.TextStyle.SHORT, Locale.getDefault()),
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                color = MutedText, fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    repeat(weeks) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { weekday ->
                                val number = week * 7 + weekday - offset + 1
                                if (number !in 1..displayedMonth.lengthOfMonth()) {
                                    Spacer(Modifier.weight(1f).height(54.dp))
                                } else {
                                    val date = displayedMonth.atDay(number)
                                    val count = counts[date.toString()] ?: 0
                                    val expected = schedule.doseCountFor(date)
                                    val selected = date == selectedDate
                                    val description = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) +
                                            when (count) {
                                                in 1 until expected -> ", $count of $expected doses recorded"
                                                expected -> ", all $expected doses recorded"
                                                else -> ", no identified dose records"
                                            }
                                    Column(
                                        modifier = Modifier.weight(1f).height(54.dp)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) historyTakenBackground else Color.Transparent)
                                            .then(if (date == today) Modifier.border(
                                                1.dp, completeGreen, RoundedCornerShape(12.dp)) else Modifier)
                                            .clickable { selectedDate = date }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = description
                                                this.selected = selected
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(number.toString(), color = Navy, fontSize = 15.sp,
                                            fontWeight = if (selected || date == today) FontWeight.Bold else FontWeight.Normal)
                                        Spacer(Modifier.height(5.dp))
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(when {
                                            count in 1 until expected -> partialGreen
                                            count >= expected && expected > 0 -> completeGreen
                                            else -> Color.Transparent
                                        }))
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(partialGreen))
                            Spacer(Modifier.width(6.dp))
                            Text("Partially recorded", color = MutedText, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(completeGreen))
                            Spacer(Modifier.width(6.dp))
                            Text("All doses recorded", color = MutedText, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            displayedMonth = java.time.YearMonth.from(today)
                            selectedDate = today
                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                            Text("Today", color = completeGreen, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            val date = selectedDate
            val dayRecords = records.filter { it.date == date.toString() }
            Card(shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                    val expected = schedule.doseCountFor(date)
                    for (number in 1..expected) {
                        val record = dayRecords.firstOrNull { it.doseNumber == number }
                        val planned = record?.scheduledTime ?: schedule.timeFor(date, number).format(TimeFormat)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (record != null) historyTakenBackground else AppBackground).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(36.dp).clip(CircleShape)
                                .background(if (record != null) completeGreen else (if (LocalDarkMode.current) Color(0xFF344150) else Color(0xFFE2E8F0))),
                                contentAlignment = Alignment.Center) {
                                if (record != null) Icon(Icons.Default.Check, "Taken", tint = MaterialTheme.colorScheme.onPrimary)
                                else Text(number.toString(), color = MutedText)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Dose $number · Scheduled $planned", color = Navy, fontWeight = FontWeight.Medium)
                                Text(if (record != null) "Taken at ${record.time}" else "No taken record",
                                    color = if (record != null) completeGreen else MutedText)
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
            Text("Tap a date to view its records. No dot means no identified doses were recorded, not proof of a missed dose. Older entries without a saved schedule use your current settings.",
                color = MutedText, fontSize = 13.sp)
        }
    }
}


@Composable
private fun StatsScreen(modifier: Modifier, records: List<TakenDose>, today: LocalDate) {
    val start = today.minusDays(6)
    val count = records.filter {
        val date = runCatching { LocalDate.parse(it.date) }.getOrNull()
        date != null && !date.isBefore(start) && !date.isAfter(today) && it.doseNumber in 1..3
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
    stock: MedicineStock,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    dailyBackupEnabled: Boolean,
    backupFolderUri: String?,
    onSelectBackupFolder: () -> Unit,
    onCreateBackup: () -> Unit,
    onDailyBackupChange: (Boolean) -> Unit,
    restockEnabled: Boolean,
    onStock: () -> Unit,
    onRestockNotifications: () -> Unit,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    onSave: (Int, Int, String) -> Unit,
    onThirdDoseChange: (Boolean, String?, String?, String?) -> Unit
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)
    fun pickTime(number: Int, day: Int, value: String) {
        val time = runCatching { LocalTime.parse(value, TimeFormat) }.getOrDefault(LocalTime.NOON)
        TimePickerDialog(context,
            if (darkMode) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, hour, minute ->
                onSave(number, day, LocalTime.of(hour, minute).format(TimeFormat))
            }, time.hour, time.minute, false).show()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Navy)
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Manage your supply and reminders", color = MutedText, fontSize = 14.sp)
                }
            }
        }
        item {
            SettingsSection("Appearance", Icons.Default.Settings) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dark mode", color = Navy, fontSize = 16.sp)
                        Text("Use a darker colour palette", color = MutedText, fontSize = 13.sp)
                    }
                    Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                }
            }
        }
        item {
            SettingsSection("Stock management", Icons.Default.Medication) {
                SettingsRow("Current stock", stock.remainingPills?.let { "$it pills" } ?: "Set up", onStock)
                SectionDivider()
                SettingsRow("Low-stock thresholds", "${stock.amberThreshold} / ${stock.redThreshold}", onStock)
                Text("Amber at ${stock.amberThreshold} pills · Red at ${stock.redThreshold} pills",
                    color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                Text("Tap a row to update your count, add a resupply or change thresholds.",
                    color = MutedText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        item {
            SettingsSection("Reminder access", Icons.Default.NotificationsNone) {
                SettingsRow("Dose notifications", if (notificationsEnabled) "Enabled" else "Review", onNotifications)
                SectionDivider()
                SettingsRow("Restock notifications", if (restockEnabled) "Enabled" else "Review", onRestockNotifications)
                SectionDivider()
                SettingsRow("Exact alarms", if (exactEnabled) "Allowed" else "Allow", onExactAlarms)
            }
        }
        item {
            val thirdEnabled = schedule.doseCountFor(LocalDate.now()) == 3
            SettingsSection("Daily doses", Icons.Default.Medication) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Third daily dose", color = Navy, fontSize = 16.sp)
                        Text(if (thirdEnabled) "Enabled from tomorrow" else "Enable from tomorrow", color = MutedText, fontSize = 13.sp)
                    }
                    Switch(checked = thirdEnabled, onCheckedChange = { enabled ->
                        if (!enabled) onThirdDoseChange(false, null, null, null)
                    })
                }
                if (thirdEnabled) {
                    SectionDivider()
                    SettingsRow("Weekday third dose", schedule.weekdayDose3 ?: "Set time") {
                        pickTime(3, 0, schedule.weekdayDose3 ?: "12:00 PM")
                    }
                    SectionDivider()
                    SettingsRow("Saturday third dose", schedule.saturdayDose3 ?: "Set time") {
                        pickTime(3, 6, schedule.saturdayDose3 ?: "12:00 PM")
                    }
                    SectionDivider()
                    SettingsRow("Sunday third dose", schedule.sundayDose3 ?: "Set time") {
                        pickTime(3, 7, schedule.sundayDose3 ?: "12:00 PM")
                    }
                    Text("All three third-dose times must be set before the dose becomes active. Changes apply from tomorrow.",
                        color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            SettingsSection("Weekday schedule", Icons.Default.Schedule) {
                SettingsRow("First dose", schedule.weekdayDose1) { pickTime(1, 0, schedule.weekdayDose1) }
                SectionDivider()
                SettingsRow("Second dose", schedule.weekdayDose2) { pickTime(2, 0, schedule.weekdayDose2) }
            }
        }
        item {
            SettingsSection("Saturday schedule", Icons.Default.CalendarMonth) {
                SettingsRow("First dose", schedule.saturdayDose1) { pickTime(1, 6, schedule.saturdayDose1) }
                SectionDivider()
                SettingsRow("Second dose", schedule.saturdayDose2) { pickTime(2, 6, schedule.saturdayDose2) }
            }
        }
        item {
            SettingsSection("Sunday schedule", Icons.Default.CalendarMonth) {
                SettingsRow("First dose", schedule.sundayDose1) { pickTime(1, 7, schedule.sundayDose1) }
                SectionDivider()
                SettingsRow("Second dose", schedule.sundayDose2) { pickTime(2, 7, schedule.sundayDose2) }
            }
        }
        item {
            SettingsSection("Backup", Icons.Default.Backup) {
                SettingsRow(
                    "Backup folder",
                    if (backupFolderUri == null) "Select folder" else "Change folder"
                ) { onSelectBackupFolder() }
                if (backupFolderUri != null) {
                    Text(
                        Uri.decode(backupFolderUri),
                        color = MutedText, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                SectionDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Daily backup", color = Navy, fontSize = 16.sp)
                        Text(
                            if (dailyBackupEnabled) "Enabled; runs around 2:05 AM"
                            else "Create a local backup once per day",
                            color = MutedText, fontSize = 13.sp
                        )
                    }
                    Switch(
                        checked = dailyBackupEnabled,
                        onCheckedChange = onDailyBackupChange
                    )
                }

                SectionDivider()

                SettingsRow("Create backup now", "Save") { onCreateBackup() }

                Text(
                    "Backups are saved in your selected folder, not app-private storage. The latest 14 automatic backups are kept; manual backups are not automatically deleted. Backup files are unencrypted; choose a trusted location.",
                    color = MutedText, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item {
            SettingsSection("About reminders", Icons.Default.Info) {
                Text("Set times to match your prescription. Schedule changes apply to future alarms, not saved taken timestamps.",
                    color = MutedText, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(12.dp))
                Text("Force-stopping DoseEra can prevent reminders until you open it again.",
                    color = MutedText, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SoftGray)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Emerald, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, color = Navy, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = if (LocalDarkMode.current) Color(0xFF344150) else Color(0xFFE1E7EE), thickness = 1.dp)
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), color = Navy, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = Emerald, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.ChevronRight, null, tint = MutedText, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun StockBar(
    stock: MedicineStock,
    todayRecords: List<TakenDose>,
    doseCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(containerColor = SoftGreen)
    ) {
        if (!stock.isConfigured) {
            Text("Set up stock tracking", Modifier.padding(22.dp), color = Emerald,
                fontWeight = FontWeight.SemiBold)
        } else {
            val count = stock.remainingPills ?: 0
            val onlyDose = todayRecords.distinctBy { it.doseNumber }.singleOrNull()
            val partialDayPill = if (onlyDose?.stockDeducted == true) 1L else 0L
            val daysRemaining = (count + partialDayPill) / doseCount.toLong()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Medication, null, tint = Emerald)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(count.toString(), color = Navy, fontSize = 24.sp,
                            fontWeight = FontWeight.Bold)
                        Text("pills left", color = Navy, fontSize = 13.sp)
                    }
                    Box(Modifier.width(1.dp).height(42.dp).background(Emerald.copy(alpha = 0.2f)))
                    Spacer(Modifier.width(14.dp))
                    Icon(Icons.Default.CalendarMonth, null, tint = Emerald)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1.5f)) {
                        if (count == 0) {
                            Text("Supply status", color = MutedText, fontSize = 12.sp)
                            Text("Out of stock", color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        } else {
                            Text(if (daysRemaining == 1L) "1 day" else "$daysRemaining days",
                                color = Navy, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text("Days remaining", color = Navy, fontSize = 13.sp)
                        }
                    }
                }
                if (stock.level != StockLevel.NORMAL) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Emerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(when (stock.level) {
                            StockLevel.EMPTY -> "Out of stock · tap to update"
                            StockLevel.RED -> "Very low · restock soon"
                            else -> "Running low · plan a restock"
                        }, color = Emerald, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StockEditor(
    stock: MedicineStock,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean, Int, Int) -> Unit
) {
    var count by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var amber by remember { mutableStateOf(stock.amberThreshold.toString()) }
    var red by remember { mutableStateOf(stock.redThreshold.toString()) }
    val number = count.toIntOrNull()
    val a = amber.toIntOrNull()
    val r = red.toIntOrNull()
    val countValid = if (count.isBlank()) stock.isConfigured else
        number != null && number >= (if (add) 1 else 0) &&
                (!add || (stock.remainingPills ?: 0).toLong() + number.toLong() <= Int.MAX_VALUE)
    val valid = countValid && a != null && r != null && r >= 1 && a > r
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pill stock") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Current count: ${stock.remainingPills?.toString() ?: "not set"}. One pill per dose.")
                if (stock.isConfigured) {
                    Row {
                        FilterChip(selected = !add, onClick = { add = false; count = "" },
                            label = { Text("Set total") }, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = add, onClick = { add = true; count = "" },
                            label = { Text("Add resupply") }, enabled = !busy)
                    }
                }
                OutlinedTextField(
                    value = count, onValueChange = { count = it.filter(Char::isDigit).take(10) },
                    label = { Text(if (add) "Pills to add" else "Exact total now") },
                    supportingText = { Text(if (stock.isConfigured) "Leave blank to keep the current count." else "Enter your current total to start tracking.") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(value = amber,
                    onValueChange = { amber = it.filter(Char::isDigit).take(10) },
                    label = { Text("Amber at or below") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = red,
                    onValueChange = { red = it.filter(Char::isDigit).take(10) },
                    label = { Text("Red at or below") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Amber must be higher than red. Red must be at least 1. At 0, Home shows Out of stock.", fontSize = 12.sp)
                Text("The days estimate uses your current daily dose setting. The count of pills updates after each dose; the days estimate counts down after both doses are recorded. It is not dosing advice.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy, onClick = {
            onSave(number, add, a!!, r!!)
        }) { Text(if (busy) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}