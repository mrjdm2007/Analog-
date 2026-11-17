# Analog for macOS

A native SwiftUI desktop companion for the Android Analog player/studio. It is built as a real macOS app, not an Android emulator or a web wrapper.

## Build

Requires macOS 13 or newer and Xcode Command Line Tools.

```bash
bash macOS/package-macos-app.sh
```

The script cross-compiles an Apple Silicon (`arm64`) build of `macOS/AnalogMac` with Swift Package Manager and creates `build/Analog.app` plus `build/Analog-macOS.zip`.

## Current desktop features

- Local music-folder import and library search
- Native AVAudioEngine playback
- Ten-band AVAudioUnitEQ in the actual playback signal chain, with 16 sound profiles, preamp gain, stereo balance and master output control
- Playback queue, play-next, shuffle, repeat, seeking and sleep timer
- Mac-only live analyser with 36-band post-EQ spectrum, PCM waveform mode and peak-hold markers (not present in the Android app)
- Favorites, Calm / Energy / Focus / Nostalgia mood collections, custom playlists and per-track journal notes/ratings
- Microphone recording to a local Core Audio file
- Dark, maximalist Analog visual system with a library, now-playing inspector and persistent transport bar
- Expanded sound-profile selection for Jazz, Electronic, Acoustic, Podcast, Night, R&B, Hip-Hop, Metal, Small Speakers and Headphones

The macOS implementation is native SwiftUI and AVFoundation. It shares the product design and core workflows with Android, while using the Mac's own audio APIs. It is not a byte-for-byte port of Android Compose, and Android-only system integrations (Android notification, quick-settings tile and MediaStore) are replaced by macOS-native equivalents or omitted.
