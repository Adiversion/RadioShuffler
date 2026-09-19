# Changelog

All notable changes to **Radio Shuffler** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.0] - 2026-09-19

### 🚀 Added
- **3-Tab Bottom Navigation**: Divided the app into three dedicated screens to prevent clutter and eliminate scrolling lag:
  - 📻 **Radio**: Focused Now Playing screen with compact Tuner Card, big shuffle action, and quick mood pills.
  - 🔍 **Search & Discover**: Worldwide search with instant query filtering and browseable results.
  - 📚 **Library**: Dual-view collection for saved Favorites and Recent listening history.
- **Targeted Random Shuffle**:
  - Shuffle by country, city, or music genre (e.g. `🎲 Shuffle “India”`, `🎲 Shuffle “Jazz”`).
  - Persistent query filter on the Radio screen keeps discovering unique stations within that query on every shuffle tap.
  - One-tap "Random Pick" card right inside search results.
- **Persistent Mini-Player**: Unobtrusive playback bar anchored above the bottom navigation on Search and Library tabs with live status indicator, track title, location, quick play/pause, and favorite heart toggle.
- **Material 3 Settings Bottom Sheet (`☰`)**: Clean slide-up menu housing the Sleep Timer, Lock Screen media controls guide, and in-app GitHub updater.
- **Recent Stations Limit**: Clean vertical history capped at 20 stations with direct play and favorite toggles.

### 🛠 Fixed
- **Live Stream Dropping**: Fixed an issue where cycling stream connections (e.g. KIIS FM / iHeartRadio streams) sent `STATE_ENDED` and skipped stations. ExoPlayer now automatically reconnects live streams without auto-skipping.
- **Favorite Desynchronization**: Bound `channelId` directly into `MediaItem.mediaId` and `MediaMetadata.description`. In `onMediaItemTransition`, the active station is synchronized with `currentStation` and `isCurrentFavorite`, ensuring the heart button always favorites the exact stream playing.
- **Lock Screen & HyperOS 3 Super Island Player**:
  - Implemented `CustomMediaNotificationProvider` to ensure every media notification is stamped with `NotificationCompat.CATEGORY_TRANSPORT` and `NotificationCompat.VISIBILITY_PUBLIC`.
  - Upgraded notification channel to `radioshuffler_playback_channel_v3` with public lockscreen visibility.
  - Added rasterized `artworkData` (PNG bytes) and `artworkUri` via `StationArtwork` along with `MEDIA_TYPE_RADIO_STATION`, ensuring Xiaomi HyperOS 3 Super Island (Dynamic Island / Live Updates) and Android SystemUI display the station badge properly.
- **Bluetooth Headset & Hardware Shuffling (`SoundFeedback`)**:
  - Added `<action android:name="android.intent.action.MEDIA_BUTTON" />` to `PlaybackService` and implemented `onMediaButtonEvent` in `MediaSession.Callback` for Bluetooth headset AVRCP controls (e.g. volume button long-press).
  - Created `SoundFeedback` using `STREAM_MUSIC`: plays an immediate crisp auditory beep (130ms) the millisecond a shuffle is triggered, giving instant confirmation for visually impaired and hands-free users before network streaming begins, followed by an ACK chime on stream connection.
- **Update Button Polish & Settings Streamlining**:
  - Redesigned "Check for app update" button with high-contrast styling and embedded progress spinner, preventing the button text from disappearing while checking releases.
  - Removed redundant "Lock Screen Player" card from the Settings & Controls bottom sheet.
- **ResolvedStation Constructor**: Removed invalid `streamUrl` constructor parameter to ensure clean Kotlin compilation.
- **Double Status Bar Top Padding**: Removed redundant `statusBarsPadding()` from the Radio, Search, and Library tabs because `Scaffold` already consumed the status bar inset, eliminating the oversized blank space at the top of every screen.
- **Outlined Heart Vector Sizing**: Replaced the thin unicode `"♡"` glyph with a standard Material 2dp-stroke `ic_favorite_border` vector drawable, giving the unfavorited heart a bold, normal size consistent with Material 3.

### ⚡ Optimized
- **Native Vector Icons Overhaul**: Replaced all unicode characters and emojis across the app (navigation tabs, shuffle actions, search browse buttons, settings hamburger menu, and player controls) with 17 standalone Google Material XML vector drawables.
- **Virtualized List Rendering**: Converted unbounded vertical scrolling into Jetpack Compose `LazyColumn`, recycling station cards and ensuring smooth 60 FPS performance.
- **Tuner Card Geometry**: Reduced Tuner Card container height from 290dp to a balanced 200dp, tightening padding and eliminating excessive empty void.
- **Favorite Heart Touch Target**: Enlarged the favorite button to 44dp (WCAG AA compliant) with a bold 26sp `#FF2D55` active crimson indicator.
- **Real-Time Download Progress**: Chunked streaming APK downloads in `AppUpdater.kt` with live byte/percentage progress both in-app and in the Android notification shade.

---

## [1.0.0] - 2026-09-18

### 🚀 Initial Release
- Global random radio station shuffle powered by Radio Garden.
- Background media playback using AndroidX Media3 / ExoPlayer.
- Bluetooth / headset media button reshuffle support with audio feedback cue.
- Automatic skip for stalled or failed streams.
- Sleep timer (15, 30, 60 minutes).
- In-app GitHub release checker and APK updater.
