# Google Cloud setup for the Drive backup

Service Calendar signs in with Google and stores its backup in the app's hidden folder on the user's own Google Drive. For that, the app needs a Google Cloud project with the Drive API enabled, an OAuth consent screen and one OAuth client per app signing key. This guide covers both cases: taking over the existing project and creating a new one.

The Drive feature is free. You do **not** need a billing account. No client secret or JSON key file is used anywhere: Android clients are matched by package name and SHA-1, iOS clients by bundle ID.

## Current project

| Item | Value |
| --- | --- |
| Project ID / number | `service-calendar-515701` / `974472550973` (owners: your Google account and the developer's; replaced `testproject-514710` on 2026-11-25) |
| Google Drive API | enabled |
| Consent screen | External, status **Testing**, scopes `drive.appdata`, `openid`, `userinfo.email`; one test user (your account). Going live: [go-live-production.md](go-live-production.md) |
| Android client (release APK) | package `app.servicecalendar`, SHA-1 `88:2F:25:CF:45:09:FD:BF:34:A1:F7:E2:C3:67:59:B8:95:7F:0F:55` (release key) |
| Android client (debug builds) | package `app.servicecalendar`, SHA-1 `13:D0:E2:47:D5:35:10:66:48:D9:0B:6D:BA:73:DB:41:2A:70:F9:52` (developer's debug key) |
| Android client (dev test APK) | package `app.servicecalendar.dev`, SHA-1 `13:D0:E2:47:D5:35:10:66:48:D9:0B:6D:BA:73:DB:41:2A:70:F9:52` (developer's debug key) |
| iOS client | not created yet |

While the status is **Testing**, only accounts listed under **Audience → Test users** can sign in, and Google expires the sign-in after 7 days, which stops the daily backup. Switch to **In production** (step 9) before real use.

## Switch to `service-calendar-515701` (2026-11-25)

Option B below was carried out with your own project:

1. `gcloud config configurations create service-calendar` with the developer's account (Owner in both projects) and project `service-calendar-515701`; `gcloud services enable drive.googleapis.com`. The rest is console-only: Google has no CLI or API for the consent screen or OAuth clients.
2. In `testproject-514710` → [Clients](https://console.cloud.google.com/auth/clients?project=testproject-514710) the Android client `app.servicecalendar.dev` / debug SHA-1 was deleted to free the pair.
3. In `service-calendar-515701`: [Get started](https://console.cloud.google.com/auth/overview?project=service-calendar-515701) (External), [Data access](https://console.cloud.google.com/auth/scopes?project=service-calendar-515701) (three scopes from step 5), [Test users](https://console.cloud.google.com/auth/audience?project=service-calendar-515701), and three Android clients in [Create client](https://console.cloud.google.com/auth/clients/create?project=service-calendar-515701) as listed in the table above.
4. No app code changed: Android clients are matched by package name and SHA-1.
5. Still open: you publish the consent screen ([go-live-production.md](go-live-production.md)).

Backups made through `testproject-514710` are not visible to the app any more; after the next sign-in the app creates a fresh backup from local data.

## Two rules that decide how to hand over the project

1. **Backups belong to the Cloud project.** The backup file lives in `appDataFolder`, and only OAuth clients of the project that created it can see that folder. If the app moves to another project, old backups stay on the user's Drive but the app can no longer find or restore them.
2. **A "package + SHA-1" pair can be registered in only one project.** While an Android client with a given package and SHA-1 exists in one project, Google will not let you create the same client in another one.

### Option A: take over the existing project

Nothing in the app changes and all existing backups stay available.

1. The developer opens **IAM & Admin → IAM → Grant access**, enters the owner's Google account and gives the **Basic → Owner** role. The owner accepts the invitation from the email.
2. The owner updates **Google Auth Platform → Branding**: support email, developer contact, and optionally the app name. These are shown to users on the consent screen.
3. The owner checks **Audience** (status **In production**) and **Clients** (all clients from the table in step 7).
4. The developer removes their own access in **IAM**. Then sign in on a phone again and confirm that a backup is created.

### Option B (recommended): the owner creates a new project

You create and own your Cloud project and add the developer as **Owner** to configure it (the developer removes their access afterwards). Do this only **before real use starts**: backups made through the previous project become invisible to the app.

1. The developer deletes the Android OAuth clients in the old project to free the "package + SHA-1" pairs.
2. You follow steps 1–9 below on your own account.
3. The new iOS client ID and URL scheme are added to `iosApp/src/Info.plist` and the iOS app is rebuilt. The Android build does not change if the signing key stays the same.
4. Check on both platforms: sign-in, initial backup, restore on a clean install.

If the switch happens after backups already exist, local data on the phones is not affected: the app searches the new project, finds nothing and creates a new initial backup from local data. Only the old backup can no longer be restored.

## Creating a project step by step

Have ready: the Google account that will own the project, a separate test Google account, two public web pages on a domain you can verify (home page and privacy policy), the SHA-1 fingerprints of the Android signing keys, and an Apple Developer account for iOS. Make sure your project is selected in the project picker on every page.

### Step 1: Create the project

Open [Create project](https://console.cloud.google.com/projectcreate), enter the name `service-calendar` and click **Create**. Write down the **Project ID** and **Project number** from the [Dashboard](https://console.cloud.google.com/home/dashboard).

### Step 2: Enable the Google Drive API

Open [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com) and click **Enable**.

### Step 3: Consent screen

Open [Google Auth platform](https://console.cloud.google.com/auth/overview) and click **Get started**:

| Wizard page | Value |
| --- | --- |
| App information | App name `Service Calendar`, user support email |
| Audience | **External** |
| Contact information | your email |
| Finish | accept the policy, **Continue**, **Create** |

### Step 4: Branding, home page and privacy policy

Google requires a home page and a privacy policy link for external apps in production ([Manage OAuth App Branding](https://support.google.com/cloud/answer/15549049): "These links are required for all external production apps"). Requirements from [Google's verification requirements](https://support.google.com/cloud/answer/13464321):

- the home page must be hosted on a domain you own and verified, must describe the app, and must not be only a login page;
- the privacy policy must be hosted on the same domain as the home page, linked from the home page and from the consent screen;
- every domain in **Authorized domains** must be verified with [Google Search Console](https://search.google.com/search-console).

1. Publish both pages publicly. Ready drafts are in `github-pages/` (`index.md`, `privacy.md`); replace `CONTACT_EMAIL` and `DEVELOPER_NAME` first. With GitHub Pages: create a public repository, upload both files to the root of `main`, then **Settings → Pages → Deploy from a branch → main / (root)**. The pages appear at `https://<user>.github.io/<repo>/` and `…/privacy.html`.
2. Open [Branding](https://console.cloud.google.com/auth/branding) and fill in the app name, support email, home page URL, privacy policy URL, the authorized domain of those pages (for example `<user>.github.io`) and the developer contact. **Leave the logo empty**: [brand verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification) applies to external published apps that show a logo or app name, and without it users see only your application domain ([source](https://support.google.com/cloud/answer/15549049)). If you do want your app name and logo shown, click **Verify Branding** on the Branding page; Google says the automated check usually completes in a few minutes.
3. Domain ownership: verify the authorized domain with the same account in [Search Console](https://search.google.com/search-console). A `github.io` address is a shared domain, so verification there can be awkward; a domain of your own (for example a cheap `.com` pointing to GitHub Pages) is the safer choice if Google does not accept `github.io`. Google's pages do not say whether `github.io` is accepted, so check what the Console reports.

### Step 5: Data access (scopes)

Open [Data access](https://console.cloud.google.com/auth/scopes) → **Add or remove scopes** → **Manually add scopes**, paste
`https://www.googleapis.com/auth/drive.appdata, openid, https://www.googleapis.com/auth/userinfo.email`
and click **Add to table**, **Update**, **Save**. `drive.appdata` is listed as a **non-sensitive** scope ([Drive scopes table](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)); `openid` and `email` are basic sign-in scopes. Google says apps that use only non-sensitive scopes do not have to complete app verification ([source](https://support.google.com/cloud/answer/13463073)); the verification, the "unverified app" screen and the 100-new-user limit are tied to sensitive and restricted scopes ([source](https://support.google.com/cloud/answer/7454865)). `drive.appdata` gives access only to the app's own hidden folder, not to other Drive files.

### Step 6: Test users (only while in Testing)

Open [Audience](https://console.cloud.google.com/auth/audience) → **Test users** → **Add users** and add the Google accounts that will test the app.

### Step 7: Android OAuth clients

Create one client per signing key at [Create client](https://console.cloud.google.com/auth/clients/create), type **Android**:

| Client | Package name | SHA-1 |
| --- | --- | --- |
| Release APK | `app.servicecalendar` | `keytool -list -v -keystore <release.jks> -alias <alias>` |
| Google Play (if published there) | `app.servicecalendar` | Play Console → app → **Test and release → App integrity → Play app signing → App signing key certificate** |
| Debug builds (developers only, optional) | `app.servicecalendar` | `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` |

The Android client ID is not stored in the app; nothing has to be copied back into the code. Without the Google Play client, sign-in fails in the version installed from Google Play.

### Step 8: iOS OAuth client

At [Create client](https://console.cloud.google.com/auth/clients/create) choose type **iOS**, bundle ID `app.servicecalendar`. Copy the **Client ID** and **iOS URL scheme** (`com.googleusercontent.apps.…`). They go into `iosApp/src/Info.plist` as `GIDClientID` and a `CFBundleURLTypes` URL scheme when Google Sign-In is added to the iOS app.

### Step 9: Publish to production

In [Audience](https://console.cloud.google.com/auth/audience) click **Publish app → Confirm**. The status must read **In production**; the app is then available to any user with a Google account ([source](https://support.google.com/cloud/answer/15549945)). With only non-sensitive scopes and no logo, Google's documentation does not call for a review, but it does not explicitly promise that no warning is shown either. Confirm by signing in with an account that is not a test user.

### Check that it works

1. Install the app, open **Settings → Backup → Connect Google Drive**, choose the account and allow access.
2. Add some entries; the **Last backup** time appears after the first backup.
3. Uninstall the app, install it again, connect the same account and choose **Restore Backup**. The entries return.

## What the privacy policy must say

The draft in `github-pages/privacy.md` already covers this. Keep it true to the app:

- Entries are stored in a local database on the phone.
- The optional backup is one file, `service-calendar-backup.json`, in the app's hidden Drive folder (`appDataFolder`); the app cannot see other Drive files.
- The only Drive scope is `drive.appdata`; `openid` and `email` identify the account, which stays on the device.
- There is no developer server, no analytics, no advertising and no data sharing with third parties.
- How to delete data: uninstall the app; delete the backup in Google Drive → **Settings → Manage apps → Service Calendar → Delete hidden app data**; revoke access at [myaccount.google.com/connections](https://myaccount.google.com/connections).
- A contact email.
