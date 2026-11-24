# 💊 DoseEra

A simple Android app for **dose reminders, taken-dose tracking, pill-stock management, and JSON backups**.

Built with **Kotlin** and **Jetpack Compose**.

> DoseEra records doses you mark as taken. It does not verify that medication was taken or provide medical or dosing advice.

## ✨ Features

### ⏰ Reminders & schedules
- Separate schedules for weekdays, Saturdays, and Sundays.
- Set first and second daily doses, with an optional third dose.
- Get dose notifications with a **Taken** action.
- Reminder schedules refresh after changes and supported device events.

### ✅ Dose tracking
- See today's scheduled doses and recorded-dose count on the Home screen.
- Record doses in order, with protection against duplicate entries.
- View saved dates and taken times.
- Undo accidental entries, starting with the most recently recorded dose.

### 💊 Pill stock
- Set your current pill count or add a resupply.
- Automatically deduct one pill for each recorded dose when stock tracking is configured.
- Set low-stock and empty-stock alert thresholds.
- See stock status and an estimated number of days remaining.

### 📅 History & stats
- Browse a monthly calendar with daily dose indicators.
- Select a date to view recorded doses and times.
- See the number of identified doses recorded over the last seven calendar dates.

### ☁️ JSON backups
- Choose where backups are saved using Android's folder picker.
- Create manual backups or enable daily automatic backups.
- Automatic backups use timestamped filenames and retain the latest 14 files.
- Backups include dose records, pill stock, schedules, and app preferences.

### 🎨 Appearance
- Light and dark themes.
- Accent colours that reflect normal, low, or empty pill stock.

## 🚀 Getting started

1. Install and open DoseEra.
2. Set your dose schedule in **Settings** to match your prescription.
3. Allow notifications and exact alarms where required by your Android version.
4. Optionally enter your pill count and configure low-stock alerts.
5. Choose a backup folder and create your first backup.
6. Mark a dose as **Taken** only after taking your prescribed dose.

The optional third-dose workflow is still being refined. Configure all three times and test it in your build before relying on it.

## 🛠️ Build from source

1. Clone this repository.
2. Open the project in **Android Studio**.
3. Allow Gradle sync to finish.
4. Select an Android device or emulator.
5. Build and run the app.

Use the SDK, JDK, and dependency versions specified in the project's Gradle files.

## 🧩 Project structure

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | Compose UI, settings, dose actions, and backup folder selection |
| `MedicinePreferences.kt` | App preferences, dose records, stock tracking, and backup data |
| `AlarmScheduler.kt` | Dose reminder scheduling |
| `ReminderReceiver.kt` | Dose alarm handling |
| `TakenDoseReceiver.kt` | Notification **Taken** actions |
| `NotificationHelper.kt` | Dose and restock notifications |
| `ReminderRestoreReceiver.kt` | Restores reminders after supported system events |
| `BackupManager.kt` | Backup creation, retention, and scheduling |
| `BackupReceiver.kt` | Runs scheduled backups |

## ⚠️ Important notes

- **Backup restore is not available yet.** Backups currently export data only.
- Backup JSON files are **not encrypted**. Save them somewhere you trust.
- Automatic backups use inexact alarms, so Android may delay them.
- The selected backup folder must remain available and writable. Cloud-folder syncing depends on the provider you choose.
- Keep a separate copy of important backups; a backup on the same device will not protect against device loss.
- Force-stopping the app or Android device restrictions may interrupt reminders and scheduled backups.
- Stock tracking currently deducts one pill per recorded dose; variable tablet quantities are not supported.
- Test notifications and backups on your device before relying on them.

## 🧭 Development status

DoseEra is under active development. User-selected backup storage and separate manual/automatic backup handling have been added, but background delivery and backup behaviour still need end-to-end testing on target devices.

Planned improvements include backup import, clearer backup status and error reporting, and further refinements to the third-dose workflow.

## 🩺 Medical disclaimer

DoseEra is a personal reminder and record-keeping tool, **not a medical device** and not a substitute for professional medical advice. Follow your prescription. Ask your pharmacist or clinician about dosing, missed doses, or treatment changes. Do not rely on app history or stock estimates to make medication decisions.
