[README.md](https://github.com/user-attachments/files/35092691/README.md)
<div align="center">

# DoseEra

### Stay consistent. Stay healthy.

A thoughtful Android pill reminder with discreet notifications, daily dose tracking, and a clear view of your remaining supply.

**Two daily doses. One calmer routine.**

[Features](#features) · [Getting started](#getting-started) · [Stock tracking](#stock-tracking) · [Build from source](#build-from-source) · [Important notes](#important-notes)

</div>

---

## A little structure for your everyday routine

DoseEra brings your schedule, taken records, and pill stock into one simple interface. Set separate weekday and weekend times, record doses after taking them, and see when your supply needs attention—without medication names appearing in reminder messages.

The interface starts in soft green, shifts to amber when stock runs low, and turns red when it is time to restock. Text warnings accompany the colour changes so you do not have to rely on colour alone.

## Features

| Feature | What it does |
| :--- | :--- |
| Daily dose tracking | Separate First Dose and Second Dose cards, with saved taken times. |
| Flexible schedules | Independent times for both doses on weekdays and weekends. |
| Discreet reminders | Neutral notification wording, without medication names. |
| Notification actions | Mark a dose Taken directly from its reminder. |
| Correct accidental taps | Use the immediate Undo action or undo today's records from Home. |
| Pill stock counter | Set an exact total or add newly supplied pills to your existing count. |
| Automatic deductions | Deduct one pill for each newly recorded dose; duplicate taps do not deduct again. |
| Low-stock warnings | Editable amber and red thresholds, plus an empty-stock state. |
| Supply estimate | See the estimated first day with insufficient pills for the remaining daily doses. |
| History | Review saved dose records by date. |
| Simple statistics | See the number of recorded doses across the last seven calendar dates. |

## Designed to stay out of your way

### Home

Your remaining supply is placed near the top, followed by the two daily dose cards and today's recording summary. Tap the stock bar to update your supply or adjust the warning thresholds.

Dose 2 becomes available on Home after Dose 1 has been recorded for that date. This is an interface sequence, not a recommendation about when it is medically safe to take a dose.

### Settings

Settings groups supply management, notification access, exact-alarm access, and weekday/weekend schedules into dedicated sections.

### History and Stats

History shows saved records and their taken times. Stats counts recorded doses, rather than guessing whether an unrecorded dose was missed.

## Getting started

1. Build and install the app using the instructions below.
2. Open Settings and set both weekday and weekend dose times to match your prescription.
3. Review dose-notification permissions and exact-alarm access.
4. Enable restock notifications if you want supply alerts.
5. Tap the stock bar and enter the number of pills you currently have.
6. Mark a dose Taken only after you have actually taken it.

> Stock tracking starts when you enter a count. Previously saved dose records do not reduce that opening balance.

## Stock tracking

DoseEra currently tracks one supply, with one pill deducted per recorded dose and two scheduled doses each day.

### Default warning levels

| Remaining pills | Appearance | Meaning |
| :--- | :--- | :--- |
| More than 14 | Green | Supply is above both warning thresholds. |
| 11–14 | Amber | Running low; plan a restock. |
| 1–10 | Red | Very low; arrange a restock soon. |
| 0 | Red with an empty-stock message | Check your actual supply and update the count. |

Both warning thresholds are editable. The amber threshold must be higher than the red threshold, and the red threshold must be at least one pill.

### Updating your supply

- Set total: replace the counter with the exact number of pills you have now.
- Add resupply: add newly obtained pills to an already configured counter.
- Correct the count: use Set total if pills were lost, spilled, or taken without being logged.

The counter never drops below zero. Dose recording remains available at zero because the recorded balance may not match your physical supply.

### How restock alerts behave

DoseEra tracks whether each warning level has already been announced. If stock is first entered below several thresholds, only the most severe outstanding warning is posted rather than a stack of alerts.

Restocking above a threshold makes that level eligible for a future warning. Changing thresholds also resets warning tracking. An Undo alone does not reset the sent-alert flags, helping avoid repeated alerts from Take/Undo taps.

Notification permissions and channel settings must allow the alert. A pending alert can be retried when the app opens or another stock-related action occurs.

### Understanding the date estimate

“First short day (est.)” means the first calendar date on which the recorded stock will not cover every remaining scheduled dose that day. It accounts for today's recorded doses and assumes two one-pill doses every day thereafter.

This is a planning estimate—not a prescription, a guarantee of physical stock, or advice to change your medication routine.

## Undoing an accidental Taken tap

- Use Undo immediately after recording a dose on Home, or use Undo Taken on today's dose card.
- If both doses are recorded, undo the second dose before the first.
- A pill is restored only when that record actually deducted one.
- Records created before stock tracking, or recorded while stock was zero, do not restore a pill.
- Older dates remain read-only through this undo feature.

Undo corrects the app's record. It does not reverse a dose you physically took.

## Under the hood

| Component | Technology or responsibility |
| :--- | :--- |
| Language | Kotlin |
| Interface | Jetpack Compose and Material 3 |
| Persistence | Jetpack Preferences DataStore |
| Asynchronous updates | Kotlin coroutines and Flow |
| Reminder scheduling | Android AlarmManager |
| Notification actions | BroadcastReceiver and PendingIntent |
| Date and time handling | java.time |

### Key source files

```text
MainActivity.kt          Home, Settings, History, Stats, and stock editor
MedicinePreferences.kt   Schedules, dose records, stock, thresholds, and undo
NotificationHelper.kt    Notification channels and supply alerts
TakenDoseReceiver.kt     Taken actions from notifications
ReminderReceiver.kt      Scheduled dose reminders
AlarmScheduler.kt        Alarm scheduling and refresh logic
AndroidManifest.xml      Android permissions and component declarations
```

Dose insertion and the corresponding stock deduction are saved together in a single DataStore edit. Existing record formats are retained for compatibility; older records without a known dose number are shown as unspecified.

## Build from source

### Requirements

- Android Studio with Android SDK support.
- The JDK, SDK versions, and Gradle configuration specified by the project.
- A compatible Android device or emulator.

### Android Studio

1. Clone or download this repository.
2. Open the project root in Android Studio.
3. Let Gradle sync and install any SDK components it requests.
4. Select the app run configuration and a device.
5. Build and run the app.

### Command line

From the project root, using the repository's Gradle wrapper:

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
.\gradlew.bat assembleDebug
```

For the standard app module, the debug APK is generated under `app/build/outputs/apk/debug/`.

## Important notes

### Reminder delivery

- Notification permission, notification-channel settings, and exact-alarm access affect reminder behaviour.
- Force-stopping the app can prevent reminders until you open it again.
- Check reminder behaviour on your own device, including after a restart and across midnight.
- Not all date-boundary and device-specific delivery scenarios have been verified. Do not treat a successful build as proof that every reminder condition has been tested.

### Records are not medical evidence

- A Taken record means the action was recorded in the app; it does not independently verify ingestion.
- An unrecorded slot does not prove a missed dose.
- Stock counts reflect entries and recorded actions, not a physical count of your pills.
- DoseEra does not decide what to do after a missed, delayed, or extra dose. Follow your prescription and seek appropriate professional advice.

## Privacy-minded by design

Schedules, dose records, and stock are stored locally using Android DataStore. Notification messages avoid medication names, and restock alerts use a generic public lock-screen preview.

These design choices do not imply encrypted storage, a security audit, or a guarantee about device backup behaviour. Review the repository and your device settings before relying on particular privacy guarantees.

## Feedback and contributions

Bug reports and focused improvements are welcome through the repository's Issues and pull requests, where enabled.

For a useful bug report, include:

- Android version and device model.
- Steps to reproduce the issue.
- Expected and actual behaviour.
- Relevant permission settings.
- A screenshot or error output with personal information removed.

Please do not include medication details or identifiable health records in public reports.

## License

Refer to the repository's license file for usage and distribution terms. If no license has been added, do not assume that public availability grants permission to reuse or redistribute the code.

---

<div align="center">

DoseEra · Stay consistent. Stay healthy.

Built around a simpler daily routine.

</div>
