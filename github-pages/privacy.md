---
title: Privacy Policy - Service Calendar
---

# Privacy Policy

**Effective date:** November 25, 2026

## Overview

Service Calendar ("the app") is a mobile app for Android and iOS that lets you log work days, places, procedures and rates, and view period reports. The app is published by **[[DEVELOPER_NAME]]** ("we"). This policy explains what data the app handles and what happens to it.

## Information the app stores on your device

Everything you enter is stored locally on your phone in a private database: places (name, optional address, color), procedures, rates, daily work entries with quantities and amounts, and your currency setting. The app also keeps a few technical settings, such as backup status. This data is protected by your phone's operating system and is not sent to us.

On Android, the app excludes its data from Android cloud backup and device-to-device transfer. The only way to restore your data on a new device is the Google Drive backup described below.

The Android app requests only two permissions: internet access and network state (to check whether the device is online before a backup). It does not request access to your location, contacts, camera, microphone, photos or files.

## Google account and Google Drive backup

Connecting Google Drive is optional. If you connect it:

- On Android and on iPhone you sign in with your Google account (on iPhone, Google sign-in is being added and may not be available in every version of the app) and grant one Drive permission, `https://www.googleapis.com/auth/drive.appdata`, plus basic sign-in information (`openid` and `email`). The Drive permission gives the app access only to its own hidden application folder on your Drive. The app cannot see, read or change any other file on your Drive, and other apps cannot see this folder.
- The app saves a single file, `service-calendar-backup.json`, in that folder. It contains a copy of your places, procedures, rates, daily entries and currency setting, and the time the backup was created.
- The backup is updated automatically about once a day in the background (when your device is online; the exact time is chosen by Android or iOS), and also when you open the app. You can also start a backup yourself with "Back Up Now". You can restore it from Settings, Backup, after reinstalling the app or on a new phone.
- The app also receives your Google account identifier and email address, so it can use the right account and show it on the Backup screen. These are kept only on your device. Access tokens are managed by Google's services on the device and are not stored by the app.
- Data travels directly between your device and Google. We never receive it and have no server that could store it.

Service Calendar's use and transfer of information received from Google APIs adheres to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.

## What we do not do

- We do not operate any server, and we do not collect your data.
- We do not sell your data or share it with third parties.
- The app contains no analytics, advertising, crash reporting or tracking.
- The app does not use your location, contacts, camera or microphone.

The only network connections the app makes are to Google, for sign-in and for Google Drive. Google processes data stored in your Drive under [Google's Privacy Policy](https://policies.google.com/privacy).

## Data retention and deletion

- **Data on your device:** stays until you delete it in the app or uninstall the app. Uninstalling removes all local data.
- **Backup on Google Drive:** open Google Drive on the web, then Settings, Manage apps, Service Calendar, Options, Delete hidden app data.
- **Stop backups:** in the app (Android or iPhone), open Settings, Backup, and tap "Disconnect Account". This stops daily backups and forgets the account on your device. Your entries and the existing Drive backup stay unchanged.
- **Revoke access:** at any time at [https://myaccount.google.com/connections](https://myaccount.google.com/connections).

## Security

Your data on the device is protected by the operating system's app sandbox. Data sent to Google Drive is transferred over HTTPS. Please also protect your phone with a screen lock.

## Children

The app is not directed at children under 13, and we do not knowingly collect data from them.

## Changes to this policy

We may update this policy. The effective date above shows the latest version, and the updated policy will be published at this address.

## Contact

Questions about this policy: **[[CONTACT_EMAIL]]**
