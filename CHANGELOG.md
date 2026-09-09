# Changelog

## 0.4.0
- Added Phone-Only Live mode: no LAN IP, PC, Python gateway, or Termux required.
- Added direct OpenAI Realtime Translation WebSocket client for 24 kHz PCM16 audio.
- User API key is stored locally with Android Keystore AES/GCM; no API key is embedded in the APK or repository.
- Added YouTube URL field and embedded WebView player.
- Supports common YouTube watch, youtu.be, Shorts, Live, and embed links.
- Added Android Share target: YouTube → Share → Qarro Live Translator.
- Enabled app playback capture policy for in-app video testing.
- Changed realtime audio frames to 200 ms before 48 kHz → 24 kHz resampling.
- Kept Advanced Server mode for the current Multi-voice + emotion pipeline.
- Multi-voice is intentionally disabled in Phone-Only mode until that pipeline is moved off the Python gateway.

## 0.3.0
- Server health probe from Android UI.
- Translation volume control.
- Optional Android audio-focus duck request.
- WebSocket input backpressure guard.
- Multi-voice processing metrics.
- Speaker-profile cap and improved reference diagnostics.
- Upstream realtime WebSocket closes explicitly.
- Replaced invalid/ambiguous text-model default with low-latency `gpt-4.1-mini`.
- Blank API-key template and stricter configuration detection.
- 9 server tests.
- Python CI workflow and project verification script.

## 0.2.0
- Added Multi-voice diarization and emotion-directed TTS path.
- Added speaker-bank reference clips.
- Added Android overlay speaker labels.

## 0.1.0
- Initial Android AudioPlaybackCapture → WebSocket → realtime translation MVP.
