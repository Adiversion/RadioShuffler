# Radio Shuffler

One-tap global radio shuffle for Android, powered by Radio Garden streams.

## Features

- Global random station shuffle
- Search by station, city, country, or keyword
- Background playback through Media3 / ExoPlayer
- Bluetooth/headset next/previous reshuffles station
- Short audio cue when a Bluetooth/headset reshuffle completes
- Auto-skip for failed, ended, stuck, or long-buffering streams
- Sleep timer: off, 15, 30, or 60 minutes
- In-app GitHub Release updater

## In-app updates

The app checks the latest GitHub Release from `Adiversion/radioshuffler`, downloads the `RadioShuffler.apk` asset, and opens Android's package installer.

Use numeric release tags such as `v1.0.9` or `v1.1.0`; the in-app updater compares that tag against the installed `versionName`.

The release workflow can generate the next version automatically. Leave `version_tag` empty for an automatic bump, or enter a manual tag when you need a specific version.

Android will only update the existing installed app when all of these stay true:

- `applicationId` stays `com.example.radioshuffle`
- the new APK has a higher `versionCode`
- every release APK is signed with the same keystore

The GitHub Actions workflows expect these repository secrets:

- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_PASSWORD`
- `KEYSTORE_ENCRYPTION_PASSWORD`

The `Generate Encrypted Release Keystore` workflow creates the signing keystore inside GitHub Actions, encrypts it, and commits only `keystore/radioshuffler-release.keystore.enc`.

If the signing key changes, Android will show an install conflict and the old app must be uninstalled first.

Important: APKs that were previously produced by the old debug-release workflow may have been signed with a different debug key. Moving from those old APKs to the new release-signed APK can require one uninstall/reinstall. After that, in-app updates will work as long as the same release keystore is reused.
