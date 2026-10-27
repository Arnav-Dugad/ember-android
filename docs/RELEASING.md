# Releasing Ember

Releases are GitHub Releases built by `.github/workflows/release.yml`. The in-app updater reads
`https://api.github.com/repos/Arnav-Dugad/ember-android/releases/latest`, so every published,
non-prerelease release is offered to every installed copy.

## One-time: the signing key

Android only installs an update over an existing app if both are signed with **the same key**.
Lose the key and every user has to uninstall and reinstall. So:

1. Generate it once:
   ```bash
   keytool -genkeypair -v -keystore ember-release.jks -alias ember \
     -keyalg RSA -keysize 4096 -validity 36500
   ```
2. Back it up (password manager plus offline copy). Never commit it.
3. Add these repository secrets (Settings > Secrets and variables > Actions), or with `gh`:
   ```bash
   base64 -w0 ember-release.jks | gh secret set KEYSTORE_BASE64
   gh secret set KEYSTORE_PASSWORD
   gh secret set KEY_ALIAS          # e.g. ember
   gh secret set KEY_PASSWORD
   ```
4. Optional, to ship Firebase in release builds:
   ```bash
   base64 -w0 app/google-services.json | gh secret set GOOGLE_SERVICES_JSON
   ```
   Without it, release APKs run in offline Preview mode.

## Cutting a release

```bash
git tag -a v1.2.0 -m "Faster deck and new prompts" -m "Longer notes go here."
git push origin v1.2.0
```

The workflow then:

- turns `v1.2.0` into `versionName 1.2.0` and `versionCode 10200` (`major*10000 + minor*100 + patch`);
- builds `:app:assembleRelease` with the secrets and fails if the APK ends up debug-signed;
- publishes `Ember-v1.2.0.apk` with the tag message, auto-generated notes and a hidden
  `sha256:` line. The app verifies the download against that line, then checks the APK's package
  name and signing certificate against the installed app.

### Mandatory updates

Put `[mandatory]` anywhere in the tag message (or edit it into the release notes). The update
sheet then can't be dismissed or snoozed.

### Remind me later

Snoozes that version for 24 hours. The daily WorkManager check posts at most one notification per
version.

## Local signed build

```bash
cp keystore.properties.example keystore.properties   # fill in the paths and passwords
./gradlew assembleRelease -PversionName=1.2.0 -PversionCode=10200
```
