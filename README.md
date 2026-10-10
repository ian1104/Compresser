# Compresser

Android video compressor focused on fast local H.264 hardware encoding.

## Included
- Multi-select videos from the Android document picker.
- H.264/AVC MP4 output through Media3 Transformer / Android MediaCodec.
- Hardware encoder selection with codec fallback.
- 720p fast preset, 1080p balanced preset, and 1080p quality preset.
- Approximate target-size control using bitrate estimation from source size and duration.
- Real-time per-file and overall progress.
- Foreground processing so long exports can continue when the UI is backgrounded.
- Android 15+ media-processing foreground service type.
- Results saved to `Movies/Compresser` in the user's media storage.
- Original files are never modified.

## Build locally
Use JDK 17 and Gradle 8.10.2:

```bash
gradle :app:assembleRelease
```

## GitHub Actions
Every push to `main` and manual workflow runs build `app-release.apk` and publish it as the `Compresser-release` Actions artifact.

## Notes
Output size is approximate. VBR encoding, source complexity, audio bitrate, codec implementation and device hardware affect the final size and speed. The app intentionally prioritizes hardware H.264 throughput over maximum compression efficiency.

The implementation uses AndroidX Media3 Transformer. Transformer exposes progress through `getProgress` and uses device MediaCodec encoders for the actual video encoding.
