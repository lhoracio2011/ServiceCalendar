# Service Calendar

Service Calendar is a work log for Android and iOS. You keep a list of procedures and places, set a price for each procedure at each place, and record how many procedures you did on each day. The app shows the day total immediately, builds per-place reports for any date range, stores everything in a local database and backs it up daily to the user's own Google Drive.

The app is built with Kotlin Multiplatform and Compose Multiplatform: the screens, calculations, database and backup logic are shared, and each platform adds a thin entry point.

## Contents

- [What's new in 1.1](#whats-new-in-11)
- [Install](#install)
- [Build from source](#build-from-source)
- [Project structure](#project-structure)
- [Database structure](#database-structure)
- [Backup and restore](#backup-and-restore)
- [Translations](#translations)
- [Known limitations](#known-limitations)

## What's new in 1.1

- **A / R / % on each day card.** A is the number of scheduled procedures for that place and day. You type it: tap the A box on the card or use the **Scheduled (A)** field in the day editor. R is the sum of procedures done. % = R / A × 100, shown with one decimal, or "—" when A is empty or 0.
- **Report.** Per place, each procedure with quantity and amount, plus an "A · R · %" line per place and for the total. R and % in that line count only days where A was entered. A place with no A shows "A —" and R is all its procedures.
- **Phone language for dates.** Month and weekday names and dates follow the phone's language (for example Portuguese: "NOVEMBRO 2026", "D S T Q Q S S"). Other UI text stays English.
- **New app icon.**
- **Data and backup.** The database schema is now version 4 (new column for A) and the backup file format is version 2, which includes A. Backups from 1.0 (format 1) still restore, with A empty. A 1.1 backup cannot be restored by version 1.0, which reports a newer format.

## Install

### Android

1. Copy `ServiceCalendar-1.1-release.apk` to the phone (Android 7.0 or newer) and open it.
2. Allow installing apps from this source when Android asks, then tap **Install**.
3. Google Drive backup needs Google Play services on the phone and a Google account. Sign-in in the release APK works once the app's Google Cloud project (owned by you) has the Android OAuth client for `app.servicecalendar` with the release SHA-1 and is published **In production** (see [Backup and restore](#backup-and-restore) and [docs/google-cloud-setup.md](docs/google-cloud-setup.md)). Until then **Connect Google Drive** shows "Could not sign in to your Google account".

Version 1.1 (package `app.servicecalendar`) installs over 1.0 and keeps its data. The earlier test build `calendar.apk` used a different package (`app.servicecalendar.dev`), so it installs as a separate app and its data does not carry over; it can be uninstalled after switching. Both find the same Drive backup when connected to the same Google account, so data from a test build can still be moved by connecting the release app and choosing **Restore Backup**.

### iPhone

There is no build that installs on an iPhone yet. That requires signing with an Apple Developer team that includes the iPhone (development or Ad Hoc profile), or distribution through TestFlight. Build it from source with Xcode as described below. `ServiceCalendar-1.1-iOS-Simulator.zip` contains a build for the iOS Simulator on a Mac: boot a simulator, unzip, then run `xcrun simctl install booted service-calendar.app` (or drag the app onto the simulator window).

## Build from source

### Requirements

- macOS, Linux or Windows for Android; macOS with **Xcode 26** or newer for iOS.
- **Android SDK** (for example from Android Studio). Set `ANDROID_HOME` if the SDK is not in the default location.
- Internet access on the first build. The `./kotlin` wrapper (Kotlin Toolchain 0.13.0) downloads its own JDK, Kotlin and all dependencies and needs no Gradle setup. On Windows use `kotlin.bat`. A separate Gradle build exists only for opening the Android app in Android Studio (see [Building with Android Studio](#building-with-android-studio)).

### Commands

```sh
./kotlin build                          # all modules, debug
./kotlin test                           # unit tests (JVM and iOS simulator)
./kotlin build -m androidApp -v release # release APK
./kotlin run -m androidApp              # install and start on a connected Android device or emulator
./kotlin run -m iosApp                  # build and start in the iOS Simulator
```

Outputs:

| Build | File |
| --- | --- |
| Android debug | `build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk` |
| Android release | `build/tasks/_androidApp_buildAndroidRelease/gradle-project-release.apk` (or `…-release-unsigned.apk` without a signing key) |
| iOS Simulator | `build/tasks/_iosApp_buildIosAppIosSimulatorArm64Debug/bin/Debug-iphonesimulator/service-calendar.app` |

### Release signing (Android)

Release signing is enabled in `androidApp/module.yaml` and reads `androidApp/keystore.properties`. This file and the keystore are **not** part of the source package (the developer hands the key over separately); keep them private and backed up, because every update must be signed with the same key or Android refuses to install it over the existing app.

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

To create a new key: `./kotlin tool generate-keystore --keystore-file <path>/release.jks --key-alias <alias>` (or `keytool -genkeypair`). Register the key's SHA-1 in the Google Cloud project (see [docs/google-cloud-setup.md](docs/google-cloud-setup.md), step 7), otherwise Google sign-in fails in the release build. Without `keystore.properties` the release APK is built unsigned (`gradle-project-release-unsigned.apk`) and cannot be installed.

Debug builds use the same package name and are signed with the local Android debug key, so Google sign-in in a debug build also needs an Android client for `app.servicecalendar` with that debug key's SHA-1.

For Google Play, publish an Android App Bundle: `./kotlin package -m androidApp -f aab`, and register the Play app signing SHA-1 as well.

### Building with Android Studio

Android Studio does not read the Kotlin Toolchain files, so the project also has a Gradle build for the **Android app only** (`settings.gradle.kts`, `build.gradle.kts`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, `gradlew`). It uses the same sources, resources and versions as `./kotlin` (`libs.versions.toml`, plus `gradle/toolchain.versions.toml` for the versions the Toolchain sets itself: Kotlin, Compose, AGP, SDK levels). The `./kotlin` build stays the main build; if you change dependencies or settings in a `module.yaml`, change the Gradle files too.

1. In Android Studio choose **File → Open** and select the project folder (the one with `settings.gradle.kts`). Trust the project if asked.
2. Wait for **Gradle sync** to finish. The first sync downloads Gradle 9.5.0 and the dependencies. If Android Studio asks for the SDK location, it writes `local.properties`; that file is local and not committed. If the folder was opened earlier as plain files and no sync starts, click **Load Gradle Project** in the notification, or close the project, delete the local `.idea` folder and open the folder again.
3. Choose the **androidApp** run configuration, pick a device or emulator and press **Run** or **Debug**.
4. **Debug vs release:** the default build variant is `debug`, signed with the local Android debug key (`~/.android/debug.keystore`), like `./kotlin run`. To build a release, open **Build Variants** and set `androidApp` to `release`, or use **Build → Generate Signed App Bundle or APK**.
5. Release signing reads `androidApp/keystore.properties` (same file and keys as in [Release signing](#release-signing-android) above). Without it, Gradle sync still works and the release APK is built unsigned (`androidApp-release-unsigned.apk`).

From the command line (set `JAVA_HOME` to a JDK 17 or newer, for example Android Studio's `/Applications/Android Studio.app/Contents/jbr/Contents/Home`):

```sh
./gradlew :androidApp:assembleDebug        # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :androidApp:assembleRelease      # androidApp/build/outputs/apk/release/androidApp-release.apk
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest   # JVM unit tests
```

Gradle writes to `shared/build/`, `androidApp/build/`, `build-gradle/` and `.gradle/`; the root `build/` folder belongs to `./kotlin`. iOS is not part of the Gradle build: build it with `./kotlin` or Xcode as described below.

### iOS in Xcode

1. Run `./kotlin build -m iosApp` once.
2. Open `iosApp/module.xcodeproj` in Xcode. The **Build Kotlin** phase calls `../kotlin`, so keep the project inside the full source tree.
3. Under **Signing & Capabilities** choose your **Team** (the project ships with an empty team) and keep the bundle ID `app.servicecalendar`, or change it to one your team owns.
4. Select the iPhone and press **Run**. For distribution, use **Product → Archive** with a distribution certificate.

## Project structure

| Path | Contents |
| --- | --- |
| `shared/src/` | Shared UI (Compose), view models, money and date rules, SQLite access, backup and restore logic |
| `shared/src@android/`, `shared/src@ios/` | Platform parts: database location, locale number format, back handling |
| `shared/test/` | Unit tests: totals, reports, database, migrations, snapshot format, backup and restore |
| `androidApp/` | Android entry point, Google sign-in (`auth/AndroidGoogleAuth.kt`), daily backup with WorkManager (`backup/`) |
| `iosApp/` | Xcode project, SwiftUI entry point, background backup task (`BackgroundBackup.swift`) |
| `docs/google-cloud-setup.md` | Google Cloud project setup and handover |
| `docs/translations.md` | Where every UI text lives, how to change it, what adding a language needs |
| `docs/go-live-production.md` | Owner steps to publish the consent screen (In production) |
| `docs/publishing-android.md` | Owner guide: build the .aab and publish the app on Google Play |
| `github-pages/` | Drafts of the public home page and privacy policy required by Google |

## Database structure

One SQLite database per device (`service-calendar.db` in the app's private storage). The schema version is stored in `PRAGMA user_version` (currently **4**). Schema: `shared/src/db/Schema.kt`; migrations: `shared/src/db/Migrations.kt`. Migrations are applied one version at a time, each in its own transaction together with a foreign-key check, so a failed step leaves the previous version intact. A database from a newer app version is never modified (the app refuses to open it). Foreign keys are enabled.

### Tables

| Table | Key | Columns | Rules |
| --- | --- | --- | --- |
| `procedure` | `id` (autoincrement) | `name`, `archived` (0/1) | Name is required |
| `place` | `id` (autoincrement) | `name`, `address` (optional), `color` (ARGB integer), `archived` (0/1) | Name is required |
| `place_rate` | (`placeId`, `procedureId`) | `amountMinor` | Current price of a procedure at a place, integer ≥ 0. Both columns reference their tables |
| `work_entry` | `id` (autoincrement) | `localDate` (`YYYY-MM-DD`), `placeId`, `scheduledCount` (A, optional) | One entry per date and place (unique index on `localDate`, `placeId`) |
| `work_line` | (`entryId`, `procedureId`) | `quantity` (1 to 2147483647), `appliedAmountMinor` (≥ 0) | `entryId` references `work_entry` with `ON DELETE CASCADE`; `procedureId` references `procedure` |
| `dataset_settings` | `id = 1` | `currencyCode` (three capital letters, default `BRL`) | Single row |
| `device_state` | `id = 1` | `dataGeneration` and the backup state: `backupAccountId`, `backupSearchState`, `backupChoice`, `backupDriveFileId`, `backupLastSuccessAt`, `backupBoundGeneration`, `backupDailyAllowed`, `backupRemoteRevision` | Single row. Never part of the backup and never touched by a restore |

Relations: a `work_entry` belongs to one `place`; its `work_line` rows each point to one `procedure`; `place_rate` links a place and a procedure to a price. Indexes exist on the foreign-key columns and on (`localDate`, `placeId`).

### Migrations

| Version | What it does |
| --- | --- |
| 1 | Creates `procedure`, `place`, `place_rate`, `work_entry`, `work_line`, `dataset_settings` (row with `BRL`) and `device_state` (`dataGeneration = 1`), with indexes and `CHECK` constraints |
| 2 | Adds the backup state columns to `device_state` (account, search state, user choice, Drive file ID, last success time, bound data generation, daily-backup permission) |
| 3 | Adds `device_state.backupRemoteRevision` (Drive file revision this device last wrote or restored; NULL for older rows) |
| 4 | Adds `work_entry.scheduledCount` (A). Existing entries get NULL, meaning "not entered" |

A released migration is never edited; a change gets the next version number.

### Prices, amounts and A / R / %

- Money is stored as integers in the smallest currency unit (centavos). `CHECK` constraints reject text and fractions, and totals are checked for overflow.
- **Price per place.** `place_rate` holds the price a procedure has at each place. Different places can have different prices for the same procedure.
- **Historical prices.** When a procedure is first saved on a day card, the place's current price is copied into `work_line.appliedAmountMinor`. An existing line keeps its stored amount when the day is edited, and changing a place's price later does not change saved lines, old day totals or old reports. A line amount is `quantity × appliedAmountMinor`; the day total is the sum of its lines.
- **A, R, %.** Only **A** is stored: `work_entry.scheduledCount`, an integer from 0 up, or NULL when not entered. **R** is not stored; it is the sum of `quantity` over the entry's lines. **%** is not stored; it is R / A × 100, shown with one decimal, or "—" when A is empty or 0.
- Places and procedures that are used in history are archived, not deleted.
- Dates are calendar days without a time zone.
- Limits (`shared/src/domain/CatalogLimits.kt`): 2,000 procedures, 2,000 places, 100,000 prices, 200,000 day entries, 1,000,000 lines; names up to 500 characters, addresses up to 1,000.

## Backup and restore

The backup is optional. Without it the app works fully offline and all data stays on the phone. Android's own cloud backup and device transfer are switched off for the app (`allowBackup="false"` and the rules in `androidApp/res/xml/data_extraction_rules.xml`), so the Google Drive backup is the only way to move data to a new phone.

### What is backed up

| | |
| --- | --- |
| File | `service-calendar-backup.json` (`application/json`), one file per Google account |
| Where | The app's `appDataFolder` on the signed-in Google account's Drive. It is hidden: it does not appear in the Drive file list, and only OAuth clients of the same Google Cloud project can read it. Scope `drive.appdata`, so the app cannot see other Drive files |
| Content | `schemaVersion` (2), `createdAt` (UTC), `currencyCode`, `procedures`, `places`, `placeRates`, `workEntries` (including `scheduledCount`), `workLines`, including archived records |
| Format versions | Version 2 adds `scheduledCount` (A). Version 1 files from app 1.0 still restore, with A empty. App 1.0 rejects version 2 files as newer |
| Not stored | Device state (the `device_state` table) and Google tokens, which are managed by Google Play services or the system |

### Google Cloud project

Sign-in and Drive access go through a Google Cloud project with the Drive API enabled, an OAuth consent screen and one Android OAuth client per signing key (package name and SHA-1). No client ID or secret is stored in the Android app. Steps and handover: [docs/google-cloud-setup.md](docs/google-cloud-setup.md). Before real use the consent screen must be **In production**: in **Testing** only listed test users can sign in and Google expires their authorization after 7 days, which stops the daily backup ([docs/go-live-production.md](docs/go-live-production.md)).

**Backups belong to the Cloud project.** Only OAuth clients of the project that created the backup can see it. If the app is moved to another project (as happened on 2026-11-25, from `testproject-514710` to `service-calendar-515701`), the old backups stay on the user's Drive but the app no longer sees them. Local data on the phone is not affected: after the next sign-in the app looks in the new project, finds nothing and uploads a new initial backup from the local data.

### Connect and first backup

1. **Settings → Backup → Connect Google Drive**, choose the account and allow access.
2. The app first looks for an existing backup of this account. Nothing is uploaded until the search finishes.
   - No backup found: the app uploads an initial backup right away.
   - Backup found: the app asks **Restore Backup**, **Replace Backup with Local Data** or **Decide Later**. Until the user decides, nothing is uploaded.

### When the backup runs

After the first backup it runs **once a day**:

| | Android | iOS |
| --- | --- | --- |
| Mechanism | WorkManager periodic job, every 24 h, only with a network connection (`androidApp/src/backup/BackupScheduler.kt`) | `BGProcessingTask` `app.servicecalendar.backup` (`iosApp/src/BackgroundBackup.swift`) |
| On app open | if the last backup is older than 24 h and the device is online, the backup runs immediately | same |
| Failures | up to 5 attempts per run, with exponential backoff starting at 30 min, then the next daily run or app open | the system picks the time; not guaranteed daily, does not run after the app is force-closed |

Doze mode, battery savers and some manufacturers' restrictions can delay the Android job; the next app open catches up. The automatic backup is never written more often than once per 24 hours. **Back Up Now** on the Backup screen skips that check and uploads immediately.

Before each upload the app compares the Drive file revision with the one this device last wrote or restored. If another device with the same account changed the backup in the meantime, nothing is overwritten and the Backup screen asks again what to do.

To check the schedule on a connected Android device:

```sh
adb shell dumpsys jobscheduler | grep -A20 'pkg=app.servicecalendar'   # job with a ~24 h minimum latency and a network constraint
adb shell cmd jobscheduler run -f app.servicecalendar <job-id>         # ask the system to run it now
```

A forced run still respects the app's rules: WorkManager ignores a run before its own schedule, and the backup is skipped if the last one is less than 24 hours old. The result is logged under the tag `ServiceCalendarBackup` (for example `Background backup: Uploaded → SUCCESS`).

### Restore on a new phone

1. Install the app on the new or reset phone and open **Settings → Backup → Connect Google Drive**. Use the same Google account as before.
2. The app finds the backup and shows **A Backup Already Exists on Google Drive**.
3. Tap **Restore Backup**. The app downloads and fully validates the file first (structure, references, amounts, limits) and shows what it contains and what will be replaced on the device.
4. Tap **Replace and Restore**. Editing is locked for a moment, the local data is replaced in one transaction, and the calendar, reports and catalog reload. Daily backups continue for this account.

Safety rules:

- A damaged or invalid backup is rejected and the local data stays unchanged.
- If anything fails before the restore finishes (network, permission, validation), the previous data and open drafts are kept; try again.
- **Restore** replaces all local data, so entries made on this phone and not in the backup disappear. **Replace Backup with Local Data** does the opposite and deletes the old backup after a separate confirmation. **Decide Later** keeps both and pauses uploads.
- If access to Google is lost, use **Restore Access** on the Backup screen.

### Delete the backup

- Stop backups: **Settings → Backup → Disconnect Account**. Daily backups stop; local data and the file on Drive stay.
- Delete the file: Google Drive on the web → **Settings → Manage apps → Service Calendar → Options → Delete hidden app data**.
- Revoke access: [myaccount.google.com/connections](https://myaccount.google.com/connections).
- Uninstalling the app removes the local data but not the Drive backup.

## Translations

Month and weekday names, dates and the decimal separator follow the phone. All other texts are English and live in Kotlin files. See [docs/translations.md](docs/translations.md) for where each text is, how to change it, and what adding a language requires.

## Known limitations

This list is a draft; finalize it before release.

- **iOS Google Drive backup is not connected yet.** The iOS app works fully offline, but its Backup screen shows "Backup is not available on this device" until the iOS OAuth client and the Google Sign-In SDK are added.
- **English only.** Only dates, month and weekday names and the decimal separator follow the phone's language. Adding a language needs a code change ([docs/translations.md](docs/translations.md)).
- **Automatic backup timing is not exact.** It is about once a day, subject to Doze, battery savers and manufacturer limits on Android, and to the system's scheduling on iOS, which does not run after the app is force-closed.
- On Android, the system Back button in the place editor closes the app without keeping the unsaved form.
- One user per device. If two phones use the same Google account, the app does not merge them; it asks which data to keep.
- A backup made by a newer app version cannot be restored by an older one.
- Backups made through a previous Google Cloud project are not visible after the switch (see above).
- Amounts have no currency symbol or thousands grouping; the currency code is stored (default `BRL`) but not shown.
