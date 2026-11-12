# Analog

An offline-first Android music player with a warm, vintage hi-fi identity.

## First development milestone

- Kotlin + Jetpack Compose Android app
- Media3 / ExoPlayer playback service for background audio and system media controls
- Local music discovery through Android MediaStore
- Song list with title, artist, duration, and a compact playback bar
- Full-screen live FFT spectrum visualizer
- Retro amber spectrum bars, phosphor-green accents, and an analog needle VU-meter mode
- No network permission or streaming functionality

The visualizer uses Android's audio visualizer API. Android may ask for audio permission so the app can display playback levels; Analog does not record or save audio.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 35, allow Gradle sync, then run the `app` configuration on an Android device.

A GitHub Actions workflow builds a debug APK on pushes to `main` and uploads it as the `Analog-debug` artifact.

## Roadmap

1. Verify the first debug build on a physical Android device
2. Complete album, artist, folder, and genre browsing
3. Add full now-playing screen, artwork, lyrics, and queue management
4. Add playlists, favorites, search, and metadata editing
5. Add equalizer, bass boost, gapless playback, and sleep timer
6. Expand visualizer modes with waveform, CRT scanlines, color presets, and sensitivity controls

Analog is designed for music stored on your device. It does not stream or download music.
