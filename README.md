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
  - **Non-Repeating History**: Remembers your recent stations so consecutive shuffles always uncover new frequencies.
- **📻 Modern 3-Tab Material 3 Architecture**:
  - **Radio**: Clean, distraction-free player screen featuring a compact Tuner Card, live status badges, and quick exploration chips.
  - **Search & Discover**: Worldwide station search with interactive browseable results and one-tap random pick.
  - **Library**: Dual collection with saved **Favorites** and a vertical **Recent History** (capped at 20 stations).
- **🎵 Persistent Mini-Player**:
  - Unobtrusively floats above the bottom navigation bar when browsing Search or Library tabs.
  - Quick-action play/pause and favorite heart buttons with one-tap return to the main player.
- **🔒 Lock Screen & Background Media Controls**:
  - Full Android **Media3 / ExoPlayer** integration with lock screen album art, notification shade controls, and Bluetooth/headset button support.
  - Optimized for MIUI / HyperOS and battery-restricted Android devices.
- **⏱ Sleep Timer**:
  - Automatically fade and pause playback after 15, 30, or 60 minutes.
- **🔄 Built-in In-App Updater**:
  - Check GitHub releases, stream APK downloads with real-time percentage progress bars, and install updates with a single tap.
- **🛡 100% Free & Open Source**:
  - Zero Google Firebase, zero analytics, zero ads, zero proprietary tracking SDKs. Complete privacy.

---

## 📲 Installation & Updates

### Option 1: Direct APK Download (Built-in In-App Updates)
Download the latest signed APK directly from the [GitHub Releases](https://github.com/Adiversion/radioshuffler/releases) page:
1. Download `RadioShuffler.apk`.
2. Open the file and tap **Install** (allow "Install unknown apps" if prompted).
3. **Built-in In-App Updates**: Radio Shuffler features its own built-in updater! Simply tap the Settings menu (`☰`) in the top right anytime to check for new releases, monitor live download progress, and install updates with a single tap.

### Option 2: Obtainium (Optional FOSS Package Manager)
If you prefer managing updates alongside your other open-source apps using [Obtainium](https://github.com/ImranR98/Obtainium):
1. Open Obtainium $\rightarrow$ Tap **Add App**.
2. Paste `https://github.com/Adiversion/radioshuffler`.
3. Tap **Add** $\rightarrow$ Obtainium can track and update releases seamlessly.

---

## 📋 What's Updated, Added & Optimized

See the full [CHANGELOG.md](CHANGELOG.md) for detailed version history.

### Highlights in v1.1.0:
- 🚀 **Added**: 3-Tab Bottom Navigation (Radio, Search, Library) + Persistent Mini-Player.
- 🚀 **Added**: Targeted Regional/Genre Shuffling (e.g. `🎲 Shuffle “India”`).
- 🚀 **Added**: Material 3 Settings Bottom Sheet (`☰`) with Sleep Timer & In-App Updates.
- 🛠 **Fixed**: Live stream disconnection cycles (`STATE_ENDED`) reconnect automatically instead of skipping.
- 🛠 **Fixed**: Favorite heart button synchronization with ExoPlayer active stream.
- 🛠 **Fixed**: Lock screen playback widget visibility on Xiaomi / HyperOS / MIUI.
- ⚡ **Optimized**: Virtualized `LazyColumn` rendering (zero scrolling lag at 60 FPS).
- ⚡ **Optimized**: Compact 200dp Tuner Card eliminating empty space, and 44dp enlarged favorite heart button.
- ⚡ **Optimized**: In-app updater with chunked streaming and live percentage progress bar.

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
