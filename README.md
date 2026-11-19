# Analog

Analog is an offline-first Android music player with a warm, vintage hi-fi identity.

## Implemented foundation

- Kotlin + Jetpack Compose
- Media3 / ExoPlayer playback service for background audio and system media controls
- Local audio discovery through Android MediaStore (Android 8+ permission paths)
- Optional Storage Access Framework folder grants for audio files not indexed by MediaStore; selected trees are persisted, scanned in the background, and removable from Settings
- Search across song titles, artists, and albums
- M3U playlist import/export with local-library matching
- Pitch-preserving playback speed control from 0.5× to 2×
- A/B repeat for looping a section of a track, with a minimum 300 ms loop span
- Per-track playback bookmarks to save and resume a precise listening position
- Configurable sleep timer with a smooth 5/10/15/30-second fade-out before pausing, or immediate pause when disabled
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
- Broadcast Wave (BWF) metadata with UTC origination date/time and sample time reference; up to 128 embedded WAV cue markers plus readable marker sidecar log; IEEE-float WAV fact chunk and RIFF word padding; pause/resume, input gain, 80 Hz high-pass and peak limiter
- Android input-device routing requests for available built-in, wired, USB and Bluetooth capture devices (routing remains device-policy dependent)
- Three-band low/mid/high tone shaping and a basic 3:1 dynamics compressor with adjustable threshold
- Quick recording presets for dialogue, interview, stereo music and ambience, with editable settings and device-version-aware bit-depth defaults
- Raw Mic Capture mode requests Android's `UNPROCESSED` source when the device reports support and bypasses Analog gain, EQ, compression, high-pass filtering and limiting; clearly reports when it must fall back to the regular microphone source
- Player now has 10-second rewind and 30-second skip-forward
- Player sleep timer controls directly in Now Playing: 15/30/45/60-minute timers, stop after current track, and cancel timer
- Recording cue markers are visible live with millisecond timecodes and are written to a sidecar marker file
- Playback-speed controls and service support have been removed
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

## Automated regression tests

The CI workflow runs JVM tests for PCM frame alignment, 16/24-bit quantization, non-finite float samples, ReplayGain peak protection and ID3/Vorbis tag parsing, BWF header layout, float-format fact chunk, embedded cue offsets, odd-length RIFF padding and 4 GB size-limit handling, plus A/B loop range validation and wrap behavior, and sleep fade envelope clamping.

## Verification still required

- Final CI compilation and release packaging on the latest commit
- Physical-device tests for MediaStore and SAF folder discovery (including removable storage and cloud document providers)
- Physical-device tests for each advertised sample rate, mono/stereo, wired/USB input routing, pause/resume, service interruption, long recordings and exported/shared WAV playback
- Audio quality evaluation with calibrated test signals; the visual meters are useful monitoring tools but are not certified loudness or true-peak meters

Analog is designed for audio files already stored on the device. No account or network connection is required.

## Professional audio updates

- Studio now exposes variable 40–200 Hz high-pass cutoff, adjustable limiter ceiling (-12 to -0.1 dBFS), compressor threshold and ratio, plus 1–100 ms attack and 20–500 ms release
- Input peak/RMS/clip metering is measured before Analog DSP so the hard ceiling cannot hide overloaded microphone input; stereo takes also show independent left/right peak and RMS meters
- BWF cue reservation now supports all 128 allowed markers while retaining valid RIFF chunk alignment
- Now Playing displays live shuffle and repeat mode status; pausing during crossfade restores the primary deck to unity volume
