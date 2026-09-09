# Build status — Qarro Live Translator v0.3

## Verified in the current development environment

- Python source compilation: PASS
- Server pytest: **9/9 PASS**
- Android resource XML parsing: PASS
- GitHub Actions YAML parsing: PASS
- Kotlin `PcmResampler` standalone smoke test: PASS
- API-key pattern scan: PASS
- ZIP integrity: checked during packaging

## Not verified locally

The current execution environment does not contain Android SDK / `android.jar` / Gradle, and outbound installation of that toolchain is unavailable here. Therefore `assembleDebug` cannot honestly be marked PASS locally.

The repository includes a clean GitHub Actions build that installs:

- Temurin JDK 17
- Android command-line environment
- `platforms;android-35`
- `build-tools;35.0.0`
- Gradle 8.9

and runs `assembleDebug` before uploading `app-debug.apk`.

## Device-level checks still required after first APK

1. Install on the target Android phone.
2. Confirm MediaProjection permission flow.
3. Confirm whether the installed YouTube version allows its playback audio to be captured.
4. Measure Live end-to-end latency on Wi-Fi/mobile network.
5. Measure Multi-voice speaker consistency on a video with 2–4 recurring speakers.
6. Tune audio-focus ducking behavior for the device/YouTube version.
