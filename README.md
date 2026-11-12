# Analog

Analog is an offline-first Android music player with a warm, vintage hi-fi identity.

## Implemented foundation

- Kotlin + Jetpack Compose
- Media3 / ExoPlayer playback service for background audio and system media controls
- Local audio discovery through Android MediaStore (Android 8+ permission paths)
- Search across song titles, artists, and albums
- Songs, albums, artists, and folder library views
- Favorites persisted locally
- User-created playlists persisted locally, with add-to-playlist actions
- Full now-playing screen with seek, previous/next, shuffle, and repeat controls
- Compact player controls
- Full-screen retro FFT spectrum visualizer
- Analog needle VU meter display
- Audio effects screen with system equalizer, bass boost, and virtualizer controls (device/output support varies)
- Offline-first operation; the app does not stream or download music

## Visual identity

Warm amber controls, phosphor-green accents, dark panels, and vintage hi-fi inspired typography.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 35, allow Gradle sync, then run the `app` configuration on an Android device.

GitHub Actions is configured to build a debug APK on pushes to `main` and upload it as the `Analog-debug` artifact. The build still needs confirmation on a successful CI run and physical-device testing.

## Next development milestones

1. Verify build and playback on a physical Android device
2. Add album artwork extraction and a polished now-playing layout
3. Add a full queue editor, playlist rename/delete, and richer queue actions
4. Add sleep timer, gapless playback validation, and audio focus edge-case handling
5. Expand the visualizer with waveform/oscilloscope, CRT scanlines, adjustable sensitivity, and user-selectable palettes
6. Add lyrics (embedded and local LRC), metadata editing, and playback settings

Analog is designed for audio files already stored on the device. No account or network connection is required.
