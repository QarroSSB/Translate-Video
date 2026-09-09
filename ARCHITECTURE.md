# Architecture — v0.3

## Live path

```text
Android app playback
  → AudioPlaybackCapture (48 kHz PCM16 mono)
  → 48→24 kHz resample
  → WebSocket /ws/translate
  → gateway
  → /v1/realtime/translations (gpt-realtime-translate)
  → translated PCM + transcript deltas
  → Android AudioTrack + subtitle overlay
```

Client-side WebSocket queue is bounded by a latency guard. When the network cannot keep up, new captured blocks are dropped rather than allowing a large stale backlog to accumulate.

## Multi-voice path

```text
Captured 24 kHz PCM
  → 3–12 s chunk
  → /audio/transcriptions + gpt-4o-transcribe-diarize
  → SpeakerBank
  → /responses translation + delivery estimate
  → /audio/speech + gpt-4o-mini-tts
  → 24 kHz PCM frames
  → Android AudioTrack
```

### Speaker identity strategy

The diarization file endpoint can accept up to four known-speaker reference clips. Qarro accumulates ~2–10 seconds of reference audio for stable profiles and returns those clips on future calls.

Raw labels such as `speaker_0` are **not assumed stable across independent chunks**. Profiles are capped at eight to avoid unbounded false speaker creation before enough reference audio exists.

### Emotion state in v0.3

Emotion is currently an estimate from transcript context plus normalized segment energy. TTS receives an explicit delivery instruction and intensity. This produces emotional dubbing, but it is not yet a waveform/prosody-preserving voice conversion system.

## Android constraints

The app uses a foreground `mediaProjection` service and playback capture. Android only exposes playback audio if the source app allows capture. The companion app cannot independently pause/delay the stock YouTube player's media clock.

## Buffered Dub — next architecture

A true head-start buffer requires a player we control:

```text
source media → prefetch/decode N seconds ahead → translation/dub queue
                                         ↓
                                  delayed controlled playback
```

That is deliberately kept separate from the stock-YouTube Live path.
