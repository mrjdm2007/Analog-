# Analog

Analog is an offline-first Android music player with a warm, vintage hi-fi identity.

## Implemented foundation

- Kotlin + Jetpack Compose
- Media3 / ExoPlayer playback service for background audio and system media controls
- Local audio discovery through Android MediaStore (Android 8+ permission paths)
- Search across song titles, artists, and albums
- M3U playlist import/export with local-library matching
- Pitch-preserving playback speed control from 0.5× to 2×
- ReplayGain tag reading from ID3, FLAC Vorbis comments, and Ogg Vorbis comments
- Songs, albums, artists, and folder library views
- Favorites persisted locally
- User-created playlists persisted locally, with add-to-playlist actions
- Full now-playing screen with seek, previous/next, shuffle, and repeat controls
- Compact player controls
- Playback queue viewer with jump-to-track and remove-from-queue controls
- Full-screen retro FFT spectrum visualizer
- Analog needle VU meter display
- Audio effects screen exposing every equalizer band provided by the active Android audio-effects implementation, with actual center-frequency labels, plus bass boost and virtualizer controls (device/output support varies)
- Native Analog Studio with AudioRecord PCM capture and a microphone foreground service
- WAV 16-bit PCM, Android 12+ 24-bit packed PCM, and 32-bit IEEE-float capture when the Android input route supports the requested encoding; configurable 44.1/48/96 kHz capture and mono/stereo selection
- Live sample-derived waveform, 48-band FFT spectrum, rolling 64-frame spectrogram, peak/RMS dBFS meters
- Pause/resume, markers with sidecar notes, input gain, 80 Hz high-pass and peak limiter
- Android input-device routing requests for available built-in, wired, USB and Bluetooth capture devices (routing remains device-policy dependent)
- Three-band low/mid/high tone shaping and a basic 3:1 dynamics compressor with adjustable threshold
- Recent take browser with in-app playback, rename, delete, secure share and export to public Music/Analog/Recordings on Android 10+; originals remain in app-specific storage
- Offline-first operation; the app does not stream or download music

## Visual identity

Warm amber controls, phosphor-green accents, dark panels, and vintage hi-fi inspired typography.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 35, allow Gradle sync, then run the `app` configuration on an Android device.

GitHub Actions builds debug and release APKs for pull requests targeting `main` and pushes to `main`, then uploads both artifacts.

## Recording limitations

- The recorder writes uncompressed 16-bit PCM, 24-bit packed PCM (Android 12+), or 32-bit IEEE-float WAV when supported by the active input route. Float WAV preserves processing headroom when the limiter is bypassed; it does not create extra dynamic range beyond the microphone and ADC. FLAC/AAC, multitrack, timecode, standards-compliant LUFS/true-peak metering, advanced parametric EQ, look-ahead limiting and spectral noise reduction are not implemented.
- The built-in tone stage is a lightweight three-band filter and the compressor is a basic single-band dynamics processor; neither should be treated as a mastering-grade signal chain.
- Supported sample rates and stereo capture depend on the phone and connected input hardware. A requested unsupported combination reports an error.
- The live FFT and meters analyze the actual captured PCM samples. Device routing and processing behavior remain hardware/Android dependent.

## Verification still required

- Final CI compilation and release packaging on the latest commit
- Physical-device tests for each advertised sample rate, mono/stereo, wired/USB input routing, pause/resume, service interruption, long recordings and exported/shared WAV playback
- Audio quality evaluation with calibrated test signals; the visual meters are useful monitoring tools but are not certified loudness or true-peak meters

Analog is designed for audio files already stored on the device. No account or network connection is required.
