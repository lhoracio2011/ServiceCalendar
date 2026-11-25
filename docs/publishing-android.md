# Publishing Service Calendar on Google Play

A step-by-step guide for the app owner. It goes from "I have the source code" to "the app is on Google Play". You do not need to be a developer. Follow the steps in order and do not skip the checks.

State of the Google Play rules in this guide: **25 November 2026**. Google changes its rules from time to time. If a screen looks different from this text, trust the screen and use the Help links given in each step.

Out of scope: the iPhone version (App Store). Google Sign-In is not implemented in the iOS app yet, so the Drive backup does not work there.

## Contents

- [0. What you need](#0-what-you-need)
- [1. Set up the computer](#1-set-up-the-computer)
- [2. Put the signing key in place](#2-put-the-signing-key-in-place)
- [3. Set the version](#3-set-the-version)
- [4. Build the .aab file](#4-build-the-aab-file)
- [5. Create the Google Play developer account](#5-create-the-google-play-developer-account)
- [6. Create the app in Play Console](#6-create-the-app-in-play-console)
- [7. App content forms](#7-app-content-forms)
- [8. Store listing](#8-store-listing)
- [9. Testing and release](#9-testing-and-release)
- [10. Fix Google Drive sign-in for the Play version](#10-fix-google-drive-sign-in-for-the-play-version)
- [11. Publishing updates later](#11-publishing-updates-later)
- [12. Troubleshooting](#12-troubleshooting)

## 0. What you need

**Accounts**

- A Google account for the Play developer account. Use a long-term account that you will keep. It is best to use the same account that owns the Google Cloud project `service-calendar-515701`, but this is not required.
- A GitHub account (only if the privacy page is not online yet, see [go-live-production.md](go-live-production.md)).

**Money**

- A one-time fee of **US$ 25** for the Google Play developer account, paid by credit or debit card. Prepaid cards are not accepted. ([Help: Create a Play Console developer account](https://support.google.com/googleplay/android-developer/answer/6112435))

**Time**

- About half a day of your own work, spread over several days.
- Google checks your identity first. This can take a few days.
- A new personal account must run a closed test with at least **12 testers for 14 days in a row** before the app can go public (see [step 9](#9-testing-and-release)). Plan about 3 to 4 weeks in total.

**Files and information from the developer**

- The source code (a ZIP file, or a GitHub link).
- The signing key file `release.jks` (the "key"), and its passwords. The developer sends these separately, not together with the code.
- A phone with Android 7.0 or newer for testing. The Play Console also needs an Android phone to confirm your account (see step 5).
- 12 or more friends or colleagues with Google accounts and Android phones, for the closed test.

**Before you start: the Google Cloud side must be ready**

- The Google Cloud project `service-calendar-515701` must have its consent screen **In production**. If not, follow [go-live-production.md](go-live-production.md) first. That guide also gives you the public **privacy policy web address**. You need that address again in step 7.
- Open https://console.cloud.google.com/auth/audience?project=service-calendar-515701 and check that the status says **In production**.

## 1. Set up the computer

Works on a Mac or a Windows PC.

1. Install **Android Studio**: https://developer.android.com/studio . Use the default options. Open it once and finish the first-start wizard. It downloads the Android SDK.
2. Get the source code.
   - **ZIP file:** unzip it to a folder without special characters, for example `Documents/service-calendar` (Mac) or `C:\service-calendar` (Windows). The folder must contain the files `settings.gradle.kts` and `gradlew`.
   - **Git (only if the developer gave you a repository link):** in Android Studio choose **File → New → Project from Version Control**, paste the link and click **Clone**.
3. Open the project: **File → Open**, select the folder with `settings.gradle.kts`, click **OK**, and choose **Trust Project** if asked.
4. Wait for **Gradle sync** to finish (a progress bar at the bottom). The first time it downloads a lot and can take 10 minutes or more. It is finished when the bar disappears and there are no red errors in the **Build** panel.
5. If Android Studio says the SDK is missing, click the link in the message to install it.

Java (JDK 17 or newer) is needed. Android Studio already contains one, so you do not install Java yourself when you build inside Android Studio.

## 2. Put the signing key in place

The key proves that updates come from you. Treat it like the key to your house.

1. Copy the file `release.jks` that the developer sent into a safe folder outside the project, for example `Documents/keys/release.jks` (Mac) or `C:\keys\release.jks` (Windows).
2. **Make a backup copy right now**: put the file and the passwords in at least two separate places (for example a USB stick and a password manager). Never send them by open email or chat, and never upload them to GitHub.
3. In the project folder open the folder `androidApp`. Create a text file named exactly `keystore.properties` (no `.txt` at the end). On Windows, turn on **View → File name extensions** in File Explorer to be sure of the name. Write these four lines. Replace the parts after `=` with the values from the developer:

   ```properties
   storeFile=/Users/YOUR-MAC-USER/Documents/keys/release.jks
   storePassword=PASSWORD_FROM_THE_DEVELOPER
   keyAlias=servicecalendar
   keyPassword=PASSWORD_FROM_THE_DEVELOPER
   ```

   - `storeFile` is the full path of the key file. On Windows write it with forward slashes, for example `storeFile=C:/keys/release.jks`.
   - `keyAlias` is `servicecalendar`.
   - Do not put spaces around `=` and do not use quotes.
4. This file stays on your computer only. It is never uploaded anywhere.

**Why this key is important**

- Your app uses **Play App Signing**: Google keeps the real signing key. The key you have is the **upload key**. You use it to prove to Google that an upload comes from you. ([Help: Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756))
- If you lose the upload key you can ask Google to reset it, but it takes time. If someone else gets it, they could upload apps in your name. Keep it safe.
- Its fingerprint (SHA-1) is `88:2F:25:CF:45:09:FD:BF:34:A1:F7:E2:C3:67:59:B8:95:7F:0F:55`. You can use it to check that the file is the right one (see the check in step 4).

## 3. Set the version

Every upload to Google Play needs a **higher `versionCode`** than the one before. The version name (`1.1`) is the text people see. The current values are `versionCode 2` and `versionName "1.1"`, and the package name (application ID) is `app.servicecalendar`.

For the first upload you can keep version 1.1 (code 2). Change the version only when you publish a new update (see [step 11](#11-publishing-updates-later)).

The numbers are set in two files that must always say the same thing:

| File | Lines |
| --- | --- |
| `androidApp/build.gradle.kts` (used by Android Studio and `./gradlew`) | `versionCode = 2` and `versionName = "1.1"` |
| `androidApp/module.yaml` (used by `./kotlin`) | `versionCode: 2` and `versionName: "1.1"` |

The iPhone app has its own, separate version number. Changing the Android version does not change it.

## 4. Build the .aab file

An **.aab** (Android App Bundle) is the file you upload to Google Play. It is not installed directly on a phone.

### Option A: with Android Studio (recommended)

1. Menu **Build → Generate Signed App Bundle or APK**.
2. Choose **Android App Bundle** and click **Next**.
3. Module: **androidApp**.
4. Under **Key store path** click **Choose existing** and select your `release.jks`. Type the store password, choose the alias `servicecalendar`, type the key password. Click **Next**. (Tip: tick **Remember passwords** only on your own private computer.)
5. Choose build variant **release** and click **Create** (or **Finish**).
6. When the message "Generate Signed Bundle" appears, click **locate**. The file is here:

   `androidApp/build/outputs/bundle/release/androidApp-release.aab`

### Option B: command line

The project is set up so that the signing happens by itself when `androidApp/keystore.properties` exists (step 2).

Mac (Terminal), in the project folder:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :androidApp:bundleRelease
```

Windows (Command Prompt), in the project folder:

```bat
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
gradlew.bat :androidApp:bundleRelease
```

Result: `androidApp/build/outputs/bundle/release/androidApp-release.aab`. The first build is slow because it downloads things. Later builds take under a minute.

The developer's own tool `./kotlin package -m androidApp -f aab` (Windows: `kotlin.bat`) builds the same bundle at `build/tasks/_androidApp_bundleAndroid/gradle-project-release.aab`. Use only one of the two ways; Android Studio or `./gradlew` is the simplest for you.

### Check that the file is signed with the right key

Mac, in the project folder (the same `JAVA_HOME` as above):

```sh
"$JAVA_HOME/bin/keytool" -printcert -jarfile androidApp/build/outputs/bundle/release/androidApp-release.aab
```

Windows:

```bat
"%JAVA_HOME%\bin\keytool" -printcert -jarfile androidApp\build\outputs\bundle\release\androidApp-release.aab
```

The line `SHA1:` must read `88:2F:25:CF:45:09:FD:BF:34:A1:F7:E2:C3:67:59:B8:95:7F:0F:55`. If it says something else, or the command says the file is not signed, the file `keystore.properties` is missing or wrong. Do not upload that file.

### Optional: an .apk for installing directly on a phone

```sh
./gradlew :androidApp:assembleRelease
```

The file is `androidApp/build/outputs/apk/release/androidApp-release.apk`. Copy it to a phone and open it to install. This is useful to test before uploading. Note that Google sign-in in this APK works because the release key is already registered in Google Cloud. In the version installed from Google Play it needs step 10.

## 5. Create the Google Play developer account

1. Open https://play.google.com/console/signup and sign in with the Google account you chose in step 0.
2. Choose the account type. ([Help: Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885))
   - **Personal**: for one person. Simple. Subject to the testing requirement in step 9. The developer name you enter is shown on the store page.
   - **Organization**: for a company. Needs a registered business and a D-U-N-S number. Requirements for new apps are lighter in some cases, but the setup takes longer.
   - If you publish as a private person (not a registered company), choose **Personal**.
3. Enter your name, address and contact details, and accept the Developer Distribution Agreement.
4. Pay the one-time **US$ 25** fee.
5. **Verify your identity.** Google may ask for a government ID and a card issued in your legal name. The name must match exactly. Upload clear photos. If the data is wrong, the fee is not refunded. ([Help: Verify your developer identity](https://support.google.com/googleplay/android-developer/answer/10841920))
6. **Confirm an Android phone** (personal accounts): install the **Google Play Console** app from Google Play on your Android phone, sign in with the same account and follow the prompts.
7. Wait for the email that your account is verified. This can take a few days. Until then you cannot publish.

## 6. Create the app in Play Console

Open https://play.google.com/console and click **Create app**.

| Field | Enter |
| --- | --- |
| App name | `Service Calendar` |
| Default language | English (United States) |
| App or game | **App** |
| Free or paid | **Free** (you cannot change a free app into a paid one later) |
| Declarations | Tick the developer program policies and the US export laws boxes |

Click **Create app**. The Dashboard now shows a to-do list ("Set up your app"). The next steps work through it.

## 7. App content forms

Open **Monitor and improve → Policy and programs → App content** (in older layouts: **Policy → App content**). Fill in each item. ([Help: Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469), [Help: App content](https://support.google.com/googleplay/android-developer/answer/9859455))

Facts about the app, so that your answers are true: it has no server, no ads, no analytics and no tracking. Everything stays on the phone. The optional Google Drive backup is one file in the app's hidden Drive folder. The app asks only for the Internet and network state permissions.

| Form | Suggested answer |
| --- | --- |
| **Privacy policy** | Paste the public address of the privacy page from [go-live-production.md](go-live-production.md), for example `https://YOUR-GITHUB-NAME.github.io/service-calendar/privacy.html`. Open it in a browser first. It must load and show your name and email (no `DEVELOPER_NAME` or `CONTACT_EMAIL` left in the text). |
| **Ads** | **No, my app does not contain ads.** |
| **App access** | **All functionality is available without special access.** Signing in to Google is optional (only for backup), so the reviewer does not need a login. |
| **Content rating** | Start the questionnaire. Category: **Utility, Productivity, Communication, or Other**. Answer **No** to all questions about violence, sex, drugs, gambling, user-generated content shared with others, and location sharing. You should get the lowest age rating. |
| **Target audience and content** | Choose **18 and over**. Do not choose any age group under 18: younger groups add extra rules, and the app is a work tool for adults. Answer **No** to "Could your app unintentionally appeal to children?" |
| **Data safety** | See below. |
| **Government apps** | **No** |
| **Financial features** | **No financial features.** (The app counts prices of your own work. It does not handle payments, loans, banking or crypto.) |
| **Health** | **No health features** (if the form is shown). |
| **News app, COVID-19, other declarations** | **No** |

### Data safety (suggested answers)

1. Does your app collect or share any of the required user data types? **Yes** (because of the optional Drive backup). Be honest here. A mismatch with the privacy policy can cause a rejection.
2. Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS to Google).
3. Do you provide a way for users to request that their data is deleted? **Yes.** Describe: uninstall the app, or delete the backup in Google Drive under **Settings → Manage apps → Service Calendar → Delete hidden app data** (same text as the privacy policy). Add the privacy policy link as the deletion address if asked for a web link.
4. Data types. Tick only this one:
   - **App activity → Other user-generated content**: the user's own work entries (places, procedures, prices, daily counts) are sent to the backup file in the user's Google Drive. Collected: yes. Shared: **no**. Processed ephemerally: **no**. Required or optional: **optional** (users can choose not to connect Drive). Purpose: **App functionality** (backup and restore).
   - Do **not** tick **Personal info → Email address**. The app gets the email of the signed-in Google account from Google to show it on the Backup screen, but it never sends the email anywhere and it is not part of the backup file. Google counts data as "collected" only when the app sends it off the phone.
   - Do not tick location, contacts, photos, audio, files and docs, health, financial info, device or other IDs, or diagnostics.
5. "Shared" is **No** for everything: data goes only to the user's own Google Drive. Google acting as the user's own storage service is not counted as sharing.
6. Save, then **Submit**.

If you are unsure how to answer one question, choose the answer that matches the privacy policy text. Both must say the same.

## 8. Store listing

Open **Grow users → Store presence → Main store listing** (older layout: **Store presence → Main store listing**). ([Help: Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151))

| Item | Requirement |
| --- | --- |
| App name | `Service Calendar` (up to 30 characters) |
| Short description | Up to **80** characters. Example: `Log procedures per place and day, see totals and reports.` |
| Full description | Up to 4000 characters. Use the text in the next section as a base. |
| App icon | **512 × 512 px**, PNG, up to 1 MB. Ask the developer for the file, or export the app icon. |
| Feature graphic | **1024 × 500 px**, JPEG or PNG without transparency. The developer can prepare it. |
| Phone screenshots | **2 to 8**, PNG or JPEG, each side between 320 and 3840 px, long side no more than 2.3 times the short side. Recommended: at least 4 screenshots of 1080 × 1920 px. Take them on a phone with some sample data. |
| Category | **Productivity** (or **Business**) |
| Contact details | Your email (public). A website and phone are optional. |

Suggested full description:

> Service Calendar is a simple work log. Keep a list of procedures and places, set a price for each procedure at each place, and record how many procedures you did every day. The day total appears immediately.
>
> - Calendar with a card for each day and place
> - A (scheduled), R (done) and % for each day
> - Reports for any date range, per place and in total
> - Works offline. Your data is stored on your phone.
> - Optional daily backup to your own Google Drive, in a hidden folder that only this app can see. Restore on a new phone.
>
> No ads. No analytics. No account with us.

Then click **Save**.

## 9. Testing and release

Use the left menu **Test and release**. ([Help: Set up an open, closed, or internal test](https://support.google.com/googleplay/android-developer/answer/9845334))

### 9.1 Internal testing (quick check, up to 100 testers)

1. **Test and release → Testing → Internal testing → Create new release**.
2. If asked for **Play App Signing**, accept it. It is required to upload an app bundle. Google then keeps the app signing key and your `release.jks` becomes the upload key. ([Help: Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756))
3. Upload `androidApp-release.aab` (drag and drop). Wait until it is processed.
4. Release name: leave the suggested one. Release notes: `First test version.`
5. **Next → Save → Go to publishing**, and publish. No long review for internal tests, usually minutes.
6. On the **Testers** tab create an email list with your own Google account and copy the **opt-in link**. Open it on your phone, accept, and install the app from Google Play.
7. **Right now Google Drive sign-in in this version will fail.** This is expected. Do step 10 first, then test again.

The target API level: new apps and updates must target **Android 16 (API 36) or higher** since 31 August 2026. The app targets API 37, so it meets this. ([Help: Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878))

### 9.2 Closed testing (required for a new personal account)

New **personal** developer accounts must run a closed test before they can apply for production. As of today the rule is: at least **12 testers** opted in, continuously for **14 days** before you apply. If testers leave and rejoin, the days do not count. ([Help: App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)) Organization accounts do not have this requirement.

1. **Test and release → Testing → Closed testing → Create track** (the default track name is fine). Create a new release and upload the same `.aab` (or the newest one).
2. On the **Testers** tab create an email list with **12 or more** Google accounts (use 15 to 20 to be safe, some people drop out). Save, and copy the **opt-in link**.
3. Send the link to the testers. Ask each one to open it on an Android phone, tap **Become a tester**, and install the app from Google Play. Ask them to keep the app installed and to open it now and then.
4. Publish the closed test release. It goes through a Google review first (usually under a few days).
5. Wait **14 days** with at least 12 testers opted in.
6. On the Play Console **Dashboard** click **Apply for production**. Fill the three sections: "About your closed test", "About your app/game" and "About your production readiness". Describe honestly what testers did and what you changed because of feedback.
7. Google usually replies in **7 days or less**. If it says you need more testing, keep the closed test running and apply again.

### 9.3 Production release

1. **Test and release → Production → Create new release**.
2. Choose the same or a newer `.aab`. The version code must not be used already.
3. Add release notes (for example `First release.`), then **Next → Save → Go to publishing**.
4. Under **Publishing overview** click **Send changes for review**.
5. Google reviews the app. New apps and some accounts can take **up to 7 days, sometimes longer**. Updates are often faster. ([Help: Publishing overview and review](https://support.google.com/googleplay/android-developer/answer/6334282))
6. After approval the app is live on Google Play. It can take a few hours to show up in search.

## 10. Fix Google Drive sign-in for the Play version

With Play App Signing, Google signs the apps that users download with its own key, not with yours. Sign-in with Google checks the key. So Google Cloud needs one more entry. Do this as soon as the first bundle is uploaded (step 9.1). It has no cost.

1. In Play Console open your app, then **Test and release → App integrity** (older layout: **Release → Setup → App integrity**). Open the **Play app signing** tab.
2. In the box **App signing key certificate** copy the **SHA-1 certificate fingerprint** (a line like `AA:BB:CC:...`). This is not the same as the **Upload key certificate** shown below it (that one should be `88:2F:25:CF:...:0F:55`). Be careful to copy the **app signing** one.
3. Open https://console.cloud.google.com/auth/clients/create?project=service-calendar-515701 . Make sure the project name at the top is `service-calendar-515701`.
4. Fill in:
   - **Application type:** Android
   - **Name:** `Google Play app signing`
   - **Package name:** `app.servicecalendar`
   - **SHA-1 certificate fingerprint:** the one you copied in step 2
5. Click **Create**. Nothing has to be copied into the app.
6. Check that the consent screen is **In production**: https://console.cloud.google.com/auth/audience?project=service-calendar-515701 . If it says Testing, follow [go-live-production.md](go-live-production.md).
7. Wait a few minutes. On your phone, uninstall any version installed from a file, and install the app from the Play opt-in link (step 9.1).
8. Open the app: **Settings → Backup → Connect Google Drive**, choose the account and allow access. The **Last backup** time must appear. This confirms the fix.

More details: [google-cloud-setup.md](google-cloud-setup.md), step 7.

## 11. Publishing updates later

1. Change the version in both files (see [step 3](#3-set-the-version)): increase `versionCode` by 1 (2 becomes 3) and change `versionName` (for example `1.2`). Never reuse a `versionCode`.
2. Use the **same** `release.jks` and `keystore.properties` as before.
3. Build the new `.aab` (step 4) and check the SHA-1.
4. In Play Console choose **Test and release → Production → Create new release**, upload the `.aab`, write release notes, and send it for review. (Optional: test it first in internal testing.)
5. If you changed the data the app collects, update the **Data safety** form and the privacy policy first.
6. If the app keeps working but the Google Drive backup stops after an update, check that step 10 is still in place and the consent screen is **In production**.

The database and backup format are handled by the app. Users update from Google Play and keep their data.

## 12. Troubleshooting

| Problem | Cause and fix |
| --- | --- |
| In the app: "Could not sign in to your Google account", or `UNREGISTERED_ON_API_CONSOLE` or error code 10 | The app signing certificate SHA-1 of Google Play is not registered as an Android client. Do [step 10](#10-fix-google-drive-sign-in-for-the-play-version). Make sure the package name is exactly `app.servicecalendar` and the SHA-1 is the **App signing key certificate**, not the upload key. Wait a few minutes after saving. |
| Google shows "Access blocked: this app has not completed the Google verification process" or "…is in testing" | The consent screen is still in **Testing**. Publish it: [go-live-production.md](go-live-production.md). |
| Sign-in works for a week, then stops | Same cause: the consent screen is in **Testing**, where Google ends the sign-in after 7 days. |
| Play Console: "Version code 2 has already been used" | Increase `versionCode` in both files ([step 3](#3-set-the-version)), rebuild and upload again. |
| Play Console: "Your Android App Bundle is signed with the wrong key" (the certificate does not match the upload key) | You signed with a different key. Use the right `release.jks` (SHA-1 `88:2F:25:CF:...:0F:55`) and check `keystore.properties`. If the key is lost, use **App integrity → Request upload key reset**. |
| The built `.aab` is unsigned or the SHA-1 is missing | `androidApp/keystore.properties` is missing or the path in `storeFile` is wrong. Fix it and build again. |
| "You uploaded an APK. Use an Android App Bundle" | Upload the `.aab`, not the `.apk`. |
| "Your app targets API level …, must target API level 36 or higher" | The project targets 37, so this should not appear. If it does, contact the developer. |
| `./gradlew` says "JAVA_HOME is not set" or "Unsupported class file" | Set `JAVA_HOME` to the JDK of Android Studio (step 4, option B). Java 17 or newer is required. |
| Gradle sync fails with no internet or SDK errors | Check your internet connection. In Android Studio open **Settings → Languages & Frameworks → Android SDK** and install the suggested SDK. |
| "Apply for production" button not available | You have fewer than 12 opted-in testers or fewer than 14 days. Wait and keep testers opted in. |
| App rejected for privacy policy or Data safety mismatch | Open the rejection message, compare the **Data safety** answers with the privacy policy text, correct them, and send the update for review again. |

If you get stuck, send the developer a screenshot of the error and the step number. Never send the passwords or the key file.
