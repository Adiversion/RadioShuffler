# Radio Shuffler GitHub-only update/release setup

Use this guide if you do not want to install Java, Android Studio, Gradle, or any keystore tools on your PC.

Everything happens inside GitHub Actions.

## What this setup does

The app can update itself only if every APK is signed with the same release key.

This repo now has two workflows:

1. `Generate Encrypted Release Keystore`
   - creates the Android signing keystore once inside GitHub Actions
   - encrypts it
   - commits only the encrypted file:
     `keystore/radioshuffler-release.keystore.enc`

2. `Build & Release App`
   - decrypts that encrypted keystore during the build
   - builds a signed release APK
   - uploads `RadioShuffler.apk` to a GitHub Release

No raw `.keystore` file is committed.
No Base64 keystore is printed in logs.
No local install is needed.

## Step 1: Add three GitHub Secrets

Open your real repo:

`Adiversion/radioshuffler`

Go to:

`Settings > Secrets and variables > Actions > New repository secret`

Create these three secrets:

| Secret name | What to enter |
|---|---|
| `RELEASE_STORE_PASSWORD` | A strong password for the Android keystore |
| `RELEASE_KEY_PASSWORD` | A strong password for the signing key |
| `KEYSTORE_ENCRYPTION_PASSWORD` | A different strong password used to encrypt the keystore file in the repo |

You can make the first two the same if you want it simpler, but `KEYSTORE_ENCRYPTION_PASSWORD` should be different.

Save these passwords somewhere safe. If you lose them, you may not be able to build updates for already installed apps.

## Step 2: Push/pull this repo update

The new files must be in GitHub before you can run the workflows:

- `.github/workflows/generate-keystore.yml`
- updated `.github/workflows/build.yml`
- `keystore/.gitignore`

After these files are pushed, go to the next step.

## Step 3: Generate the encrypted keystore once

Go to:

`Actions > Generate Encrypted Release Keystore > Run workflow`

Run it once.

It should commit this file back to the repo:

`keystore/radioshuffler-release.keystore.enc`

Do not run this workflow again after the file exists.

The workflow is designed to stop if the encrypted keystore already exists, because generating a new keystore would break app updates.

## Step 4: Build a release APK

Go to:

`Actions > Build & Release App > Run workflow`

For normal releases, leave `version_tag` empty and keep `version_bump` as `patch`.

The workflow will automatically look at existing release tags and create the next version.

Examples:

| Latest existing tag | Bump type | New tag |
|---|---:|---|
| `v1.0.8` | `patch` | `v1.0.9` |
| `v1.0.8` | `minor` | `v1.1.0` |
| `v1.0.8` | `major` | `v2.0.0` |

If you want to choose manually, enter a numeric version tag such as:

```text
v1.0.9
v1.1.0
v1.1.1
```

Manual tags should always be higher than the currently installed app version, otherwise the in-app updater may say no update is available.

The workflow will create a GitHub Release containing:

`RadioShuffler.apk`

## Step 5: In-app update behavior

Inside the app, tap:

`Check for app update`

The app checks the latest GitHub Release from:

`Adiversion/radioshuffler`

Then it downloads:

`RadioShuffler.apk`

Then it opens Android’s installer.

Android does not allow silent self-updates, so the user still has to approve installation.

If Android asks for permission:

- allow “Install unknown apps” for Radio Shuffler
- return to the app
- tap `Check for app update` again

## Very important

Android will update the existing app only when all of these stay true:

- `applicationId` stays `com.example.radioshuffle`
- new APK has a higher `versionCode`
- every release APK is signed with the same keystore

The old workflow built debug APKs. If you installed one of those old APKs, moving to the new release-signed APK may require one uninstall/reinstall. After that, future in-app updates should install over the previous version normally.

## Common problems

### “App not installed” or “Conflicting package”

Cause: the installed app and new APK were signed with different keys.

Fix:

- uninstall the old app once
- install the new release APK
- after that, keep using the same encrypted keystore and secrets forever

### Keystore generation workflow fails

Check that these secrets exist:

- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_PASSWORD`
- `KEYSTORE_ENCRYPTION_PASSWORD`

Also check whether this file already exists:

`keystore/radioshuffler-release.keystore.enc`

If it exists, do not generate again.

### Build workflow says no encrypted keystore exists

Run:

`Actions > Generate Encrypted Release Keystore > Run workflow`

Then pull the repo or check that GitHub now contains:

`keystore/radioshuffler-release.keystore.enc`

### App says no update is available

Check that:

- the GitHub Release tag is higher than the installed app version
- example: installed `1.0.8`, release `v1.0.9`
- the release has an APK asset named `RadioShuffler.apk`
- the release is not a draft

## Never expose these

Never share:

- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_PASSWORD`
- `KEYSTORE_ENCRYPTION_PASSWORD`
- any raw `.keystore` file
