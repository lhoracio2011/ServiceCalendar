# Translations guide

This guide shows where every text in Service Calendar lives, how to change it, and what adding a new language involves. Short answer: **the app has one language, English, written in Kotlin source files, and it has no language switch. Adding a language needs a small code change first** (see [Adding a new language](#adding-a-new-language)). Changing an existing English text needs no code change beyond editing the text.

## What follows the phone and what does not

| Item | Follows the phone's language/region? | Where it comes from |
| --- | --- | --- |
| Month names, weekday names, the medium date format (calendar header, day heading, backup times) | **Yes, automatically** | Android: `Locale.getDefault()` in `shared/src@android/ui/calendar/LocaleDates.android.kt`. iOS: the first entry of the system's preferred languages in `shared/src@ios/ui/calendar/LocaleDates.ios.kt` |
| Decimal separator in amounts and percentages ("12,50" or "12.50") | **Yes, automatically** | `shared/src@android/money/LocaleFormat.android.kt`, `shared/src@ios/money/LocaleFormat.ios.kt`. On iOS the code reads `NSLocale.currentLocale`, which iOS limits to the languages the app declares, so check it on an iPhone set to your language (see [Check the result](#check-the-result)) |
| All other UI text (buttons, titles, hints, dialogs, error messages, accessibility descriptions) | **No**, always English | `*Strings.kt` files below |
| App name under the icon | No, "Service Calendar" | `androidApp/res/values/strings.xml`, `iosApp/src/Info.plist` |

Amounts are shown as plain numbers (whole part, separator, two decimals). There is no currency symbol and no thousands grouping, so there is nothing to translate there. The `D S T Q Q S S` weekday letters in the calendar header are the first letter of each localized weekday name.

## Where every text lives

All UI texts are Kotlin `const val`s and small functions inside `internal object ...Strings` in `shared/src/ui/`. Both platforms use the same files.

| File | Screen |
| --- | --- |
| `shared/src/ui/ShellStrings.kt` | Bottom tabs, Settings list, page titles and subtitles |
| `shared/src/ui/calendar/CalendarStrings.kt` | Calendar, day cards, A / R / % labels, scheduled-count dialog, accessibility descriptions |
| `shared/src/ui/entry/EntryStrings.kt` | Day editor |
| `shared/src/ui/catalog/CatalogStrings.kt` | Places and procedures lists and editors |
| `shared/src/ui/report/ReportStrings.kt` | Reports |
| `shared/src/ui/backup/BackupStrings.kt` | Backup screen, dialogs, status and error messages |
| `shared/src/ui/ErrorStrings.kt` | Error messages for invalid input and limits |
| `shared/src/backup/SnapshotValidator.kt` | Reasons shown when a backup file is rejected |
| `androidApp/res/values/strings.xml` | Android app name (`app_name`) |
| `iosApp/src/Info.plist` | iOS app name (`CFBundleDisplayName`) |

Every visible text, including the symbols on small buttons, is in these files. To find any text, search the project for it, for example `grep -rn "Back Up Now" shared/src`.

Some texts are built from pieces (for example `recordsCount`, `plural`, `countsText`, `cardDescription`), and they contain English grammar: "1 entry" / "5 entries". A language with other plural rules needs those functions rewritten, not only the words replaced.

## Change an existing text

1. Find the text with `grep -rn "the exact words" shared/src androidApp iosApp/src`.
2. Edit the string in the `*Strings.kt` file and keep placeholders such as `$count` or `${...}` as they are.
3. Keep short texts short: buttons and tabs have limited room. Check long texts on a small phone.
4. Rebuild and check (see [Rebuild](#rebuild-after-a-change)).

The app name is changed in two places: `app_name` in `androidApp/res/values/strings.xml` and `CFBundleDisplayName` in `iosApp/src/Info.plist`.

## Translate the app into another language (simplest way)

If you only need the app in one other language (it replaces English), you can do it by editing text only:

1. Open these files and replace the English text with your language:
   - `shared/src/ui/ShellStrings.kt`
   - `shared/src/ui/ErrorStrings.kt`
   - `shared/src/ui/calendar/CalendarStrings.kt`
   - `shared/src/ui/entry/EntryStrings.kt`
   - `shared/src/ui/catalog/CatalogStrings.kt`
   - `shared/src/ui/report/ReportStrings.kt`
   - `shared/src/ui/backup/BackupStrings.kt`
   - `shared/src/backup/SnapshotValidator.kt` (reasons shown when a backup file is rejected)
2. Translate the plural and sentence helpers too (for example `recordsCount`, `plural`, `countsText`, `cardDescription`): they hold English grammar, so rewrite them for your language's rules.
3. Keep every `$name`, `${...}` and `%` placeholder exactly as it is; only move it to where it fits your sentence.
4. Change the app name in `androidApp/res/values/strings.xml` (`app_name`) and in `iosApp/src/Info.plist` (`CFBundleDisplayName`).
5. Rebuild: in Android Studio press Run, or run `./gradlew :androidApp:assembleRelease`. For iOS run `./kotlin build -m iosApp`, then build in Xcode.
6. Check every screen as described in [Check the result](#check-the-result).

The app then shows your language on every phone. To keep English as well and switch by the phone's language, use the next section.

## Adding a new language

**It is not possible with files alone.** Texts are compile-time constants in English, and no code chooses a language. The app has no `values-xx/strings.xml` for UI text, no language setting and no fallback logic.

### Minimal code change (not done yet)

1. Make each `*Strings` object choose its text by language. For example, give every text a small table `mapOf("en" to "...", "pt" to "...")` or, simpler to read, create a second object per language (`CalendarStringsPt`) and have `CalendarStrings` forward to the active one. `const val` has to become `val` or `fun`, because the value is no longer fixed at compile time.
2. Add one function in shared code, `expect fun appLanguage(): String`, returning the phone's first language code (`Locale.getDefault().language` on Android, the first entry of `NSLocale.preferredLanguages` on iOS, the same source the date code already uses). Return English when the code has no translation (**fallback**).
3. Rewrite the plural and sentence-building functions listed above for the new language.
4. Optional: translate the app name with `androidApp/res/values-<code>/strings.xml` on Android and `<code>.lproj/InfoPlist.strings` on iOS, and add the language to `CFBundleLocalizations` in `Info.plist`. iOS also uses the declared languages to decide the number format locale.
5. Alternative: Compose Multiplatform resources (`composeResources/values-<code>/strings.xml` with `stringResource(...)`). The project already includes the resources library, but its texts are read only inside composable functions, and many texts here are produced in view models and helpers, so that route means a larger rewrite.

Once the change exists, the language is picked **from the phone's language, with English as the fallback**. There is no in-app language picker unless you add one.

## Rebuild after a change

Android:

```sh
./kotlin build -m androidApp -v release       # build/tasks/_androidApp_buildAndroidRelease/gradle-project-release.apk
./gradlew :androidApp:assembleRelease         # androidApp/build/outputs/apk/release/androidApp-release.apk (needs JAVA_HOME, see README)
```

Or open the project in Android Studio and press Run (see README, "Building with Android Studio"). Version number: raise `versionCode` and `versionName` in `androidApp/module.yaml` (and `androidApp/build.gradle.kts`) before giving the APK to users, otherwise Android will not update over an installed copy with the same code.

iOS:

```sh
./kotlin build -m iosApp                      # builds the shared code once
```

Then open `iosApp/module.xcodeproj` in Xcode, choose your Team and run on the iPhone or Archive (see README, "iOS in Xcode"). Raise `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` for a new release.

## Check the result

1. Set the phone's language and region to the target language (Android: Settings > System > Languages; iPhone: Settings > General > Language & Region) and open the app.
2. Walk through every screen: Calendar, a day card, the day editor, Settings, Places, Procedures, Reports, Backup. Look for cut-off text, overlapping buttons and leftover English.
3. Open **Settings > Backup** and trigger the dialogs (Disconnect, Replace Backup) to see the long texts and error messages.
4. Check the calendar header, weekday letters and an amount with cents (for example 12,50 on a Portuguese phone) on both Android and iPhone.
5. Run `./kotlin test` to make sure the tests still pass. Some tests refer to the `*Strings` constants and may compare literal English text, so a changed text can make a test fail; update the expected text in that test.
