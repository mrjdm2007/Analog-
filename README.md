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
- Playback queue viewer with jump-to-track and remove-from-queue controls
- Full-screen retro FFT spectrum visualizer
- Analog needle VU meter display
- Audio effects screen with system equalizer, bass boost, and virtualizer controls (device/output support varies)
- Native Analog Studio with AudioRecord PCM capture and a microphone foreground service
- WAV 16-bit PCM recording, configurable 44.1/48/96 kHz capture, mono/stereo selection
- Live sample-derived waveform, 48-band FFT spectrum, peak/RMS dBFS meters
- Pause/resume, markers with sidecar notes, input gain, 80 Hz high-pass and peak limiter
- Recent take browser with in-app playback; recordings stored in app-specific Music/Analog/Recordings
- Offline-first operation; the app does not stream or download music

## Visual identity

Warm amber controls, phosphor-green accents, dark panels, and vintage hi-fi inspired typography.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 35, allow Gradle sync, then run the `app` configuration on an Android device.

GitHub Actions is configured to build a debug APK on pushes to `main` and upload it as the `Analog-debug` artifact. The build still needs confirmation on a successful CI run and physical-device testing.

## Recording limitations

- The recorder writes uncompressed 16-bit PCM WAV. 24-bit, FLAC, AAC, multitrack, timecode, LUFS, true-peak, advanced EQ/compression and noise reduction are not implemented in this first Studio pass.
- Supported sample rates and stereo capture depend on the phone and connected input hardware. A requested unsupported combination reports an error.
- The live FFT and meters analyze the actual captured PCM samples. Device routing and processing behavior remain hardware/Android dependent.

## Next development milestones

1. Verify build and playback on a physical Android device
2. Add album artwork extraction and a polished now-playing layout
3. Add a full queue editor, playlist rename/delete, and richer queue actions
4. Add sleep timer, gapless playback validation, and audio focus edge-case handling
5. Expand the visualizer with waveform/oscilloscope, CRT scanlines, adjustable sensitivity, and user-selectable palettes
6. Add lyrics (embedded and local LRC), metadata editing, and playback settings

Analog is designed for audio files already stored on the device. No account or network connection is required.
