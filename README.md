# 📻 Radio Shuffler

<p align="center">
  <strong>One-tap global radio discovery for Android, powered by Radio Garden.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84.svg?logo=android&logoColor=white" alt="Android 8.0+" /></a>
  <img src="https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-brightgreen.svg" alt="Target SDK 35" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg?logo=jetpackcompose&logoColor=white" alt="Material 3" />
  <img src="https://img.shields.io/badge/Exodus%20Privacy-0%20Trackers-success.svg" alt="0 Trackers" />
  <a href="https://github.com/Adiversion/radioshuffler/releases"><img src="https://img.shields.io/github/v/release/Adiversion/radioshuffler?color=00E676" alt="Latest Release" /></a>
</p>

---

## 🌟 Overview

**Radio Shuffler** is a lightweight, privacy-respecting, open-source radio app designed to help you explore thousands of live radio stations across the globe. Tap shuffle to be instantly transported to an independent jazz station in Tokyo, a community station in Chile, or live reggae in Jamaica.

No algorithms. No user tracking. No subscription fees. Just pure, spontaneous live radio.

---

## ✨ Key Features

- **🎲 Global & Targeted Shuffling**:
  - **Worldwide Shuffle**: One tap takes you anywhere on Earth.
  - **Targeted Shuffle**: Type any country, city, or music genre (e.g., `India`, `Tokyo`, `Jazz`, `Reggae`) and tap **Shuffle** to explore random unique stations strictly within that region or style.
  - **Strict Diversity**: Smart sampling prevents country clustering; consecutive worldwide shuffles always uncover fresh stations across different countries.
- **🎵 Real-Time Now Playing Track Discovery**:
  - Live Shoutcast / Icecast ICY metadata extraction for song titles and artists.
  - Synchronized across the main player, persistent mini-player, Android lock screen, and notification shade.
- **🖤 True AMOLED Pure Black Experience**:
  - `#000000` AMOLED theme engineered for maximum battery efficiency on OLED displays.
  - High-contrast, crisp white SystemUI status bar icons and seamless edge-to-edge layout.
- **📻 Modern 3-Tab Material 3 Architecture**:
  - **Radio**: Focused Now Playing screen with a compact 200dp Tuner Card, live status badges, and quick mood pills.
  - **Search & Discover**: Worldwide station search with interactive browseable results and one-tap random pick.
  - **Library**: Dual collection with saved **Favorites** and a vertical **Recent History** (capped at 20 stations).
- **🎶 Persistent Mini-Player**:
  - Unobtrusively floats above the bottom navigation bar when browsing Search or Library tabs.
  - Quick-action play/pause and 44dp favorite heart toggle with one-tap return to the main player.
- **🔒 Lock Screen & Background Media Controls**:
  - Full Android **Media3 / ExoPlayer** integration with lock screen album art, notification shade controls, and Bluetooth/headset button support.
  - Optimized for Xiaomi HyperOS / MIUI Live Updates and battery-restricted Android devices.
- **🔊 Sound Cues & Equalizer**:
  - Auditory feedback cue on shuffle and one-tap access to your device's native system equalizer.
- **⏱ Sleep Timer**:
  - Automatically pause playback after 15, 30, or 60 minutes.
- **🔄 Built-in In-App Updater**:
  - Check GitHub releases, stream APK downloads with real-time percentage progress bars, and install updates with a single tap.
- **🛡 100% Free & Open Source**:
  - Zero Google Firebase, zero analytics, zero ads, zero proprietary tracking SDKs. Complete privacy.

---

## 📲 Installation & Updates

### Option 1: Direct APK Download (Built-in In-App Updates)
<p align="left">
  <a href="https://github.com/Adiversion/radioshuffler/releases/latest/download/RadioShuffler.apk">
    <img src="https://img.shields.io/badge/Download-RadioShuffler.apk-00E676?style=for-the-badge&logo=android&logoColor=white" alt="Download RadioShuffler.apk" />
  </a>
</p>

1. Download **[RadioShuffler.apk](https://github.com/Adiversion/radioshuffler/releases/latest/download/RadioShuffler.apk)**.
2. Open the downloaded file and tap **Install** (allow "Install unknown apps" if prompted).
3. **Automatic In-App Updates**: Radio Shuffler features its own built-in updater! Simply tap the Settings menu (`☰`) in the top right anytime to check for new releases, monitor live download progress, and install updates with a single tap.

### Option 2: Obtainium (Optional FOSS Package Manager)
If you prefer managing updates alongside your other open-source apps using [Obtainium](https://github.com/ImranR98/Obtainium):
1. Open Obtainium $\rightarrow$ Tap **Add App**.
2. Paste `https://github.com/Adiversion/radioshuffler`.
3. Tap **Add** $\rightarrow$ Obtainium can track and update releases seamlessly.

---

## 📋 What's Changed & Version History

Release notes are **automatically generated** from git commits on every build!
- Check out the latest release notes and download links on the **[GitHub Releases](https://github.com/Adiversion/radioshuffler/releases)** page.
- For milestone release summaries, see **[CHANGELOG.md](CHANGELOG.md)**.

---

## 🏗 Building from Source

Radio Shuffler uses standard Gradle with Jetpack Compose:

```bash
# Clone the repository
git clone https://github.com/Adiversion/radioshuffler.git
cd radioshuffler

# Build debug APK
./gradlew assembleDebug

# Build signed release APK
./gradlew assembleRelease
```

---

## 🛡 Privacy & Transparency

- **Permissions Used**:
  - `INTERNET`: Required to stream live radio audio from station servers.
  - `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Required to keep audio playing smoothly when the screen is locked or the app is minimized.
  - `POST_NOTIFICATIONS`: Required on Android 13+ to display media playback controls on the lock screen and notification shade.
- **Zero Telemetry**: No trackers, no cookies, no analytics, no fingerprinting. All listening history and favorites remain strictly on your local device.

---

## 📜 License & Acknowledgments

- **License**: Released under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
- **Streams**: Radio streams and geographical metadata are powered by the [Radio Garden](https://radio.garden) directory.
