# Going live: switch the consent screen to In production

Instructions for the owner of the Google Cloud project `service-calendar-515701`. Until these steps are done the project stays in **Testing**: only the accounts under **Audience → Test users** can sign in, and Google asks them to sign in again every 7 days, which stops the daily backup.

Google requires a public home page and a privacy policy page on the consent screen for external apps in production ([source](https://support.google.com/cloud/answer/15549049): "These links are required for all external production apps"). Drafts of both are in `github-pages/` (`index.md`, `privacy.md`). The whole procedure takes about 15 minutes.

1. Fill in your details
   Open both files and replace `[[DEVELOPER_NAME]]` with your name (or business name) and `[[CONTACT_EMAIL]]` with the email users can write to (include the double square brackets in what you replace).

2. Publish the two pages (free, with GitHub Pages)
   - Sign in at https://github.com and create a new public repository named service-calendar: https://github.com/new
   - Click "uploading an existing file" and upload index.md and privacy.md, then "Commit changes".
   - In the repository open Settings > Pages, under "Branch" choose main and / (root), click Save.
   - After a few minutes the pages are live at:
     https://YOUR-GITHUB-NAME.github.io/service-calendar/
     https://YOUR-GITHUB-NAME.github.io/service-calendar/privacy.html
   Open both links to make sure they load.

3. Add the pages to the consent screen
   Open https://console.cloud.google.com/auth/branding?project=service-calendar-515701 and fill in:
   - Application home page: the first link
   - Application privacy policy link: the second link
   - Authorized domains: YOUR-GITHUB-NAME.github.io
   - Developer contact information: your email
   Do not upload a logo. Google's brand verification applies to external, published apps that show a logo or an app name on the consent screen ([source](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification)); without it, users see only your application domain. Click Save.

4. Publish the app
   Open https://console.cloud.google.com/auth/audience?project=service-calendar-515701, click "Publish app", then "Confirm". The status must read "In production".
   What Google's documentation says about review:
   - `drive.appdata` is listed as a **non-sensitive** scope ([Drive scopes table](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)). Google's verification page says apps that use only non-sensitive scopes are not required to complete app verification ([source](https://support.google.com/cloud/answer/13463073)).
   - Sensitive and restricted scopes are what require verification and bring the "unverified app" screen and the 100-new-user limit ([source](https://support.google.com/cloud/answer/7454865)). Google's pages do not state the unverified-app screen and the limit for non-sensitive-only apps in so many words, so after publishing, test with a Google account that is not on the test list and confirm the consent screen has no "Google hasn't verified this app" warning.
   - Without brand verification the consent screen shows your application domain instead of the app name ([source](https://support.google.com/cloud/answer/15549049)).
   - Google may still ask you to verify domain ownership in Search Console for the authorized domain ([source](https://support.google.com/cloud/answer/13464321)). If the Console shows a "Verify Branding" or domain-verification prompt, follow it.

5. Check
   Open the app > Settings > Backup. If it asks, connect Google Drive again and allow access. Check that the "Last backup" time appears.

One note: backups made through the previous test project can't be seen from this project. Your data on the phone is not affected, and the app will create a fresh backup in the new project.

If a page looks different from this description, compare it with the full reference in [google-cloud-setup.md](google-cloud-setup.md), steps 4 and 9.
