from __future__ import annotations

import asyncio
import base64
import time
from typing import Any

from fastapi import WebSocket

from audio_utils import BYTES_PER_SECOND, rms_normalized, slice_pcm
from openai_http import OpenAIHttp
from protocol import validate_audio_b64
from speaker_bank import SpeakerBank


class MultiVoiceDubBridge:
    """Chunked multi-speaker, emotion-directed dubbing pipeline.

    This intentionally trades latency for speaker labels and controllable TTS voices.
    It is useful for quality dubbing; on the stock YouTube app the translated track
    necessarily lags because a companion app cannot delay YouTube playback itself.
    """

    def __init__(self, client: WebSocket, target_language: str, chunk_ms: int) -> None:
        self.client = client
        self.target_language = target_language
        self.chunk_bytes = int(BYTES_PER_SECOND * chunk_ms / 1000)
        self.buffer = bytearray()
        self.bank = SpeakerBank()
        self.api = OpenAIHttp()
        self.queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=3)
        self.worker: asyncio.Task[None] | None = None
        self.processed_chunks = 0

    async def run(self) -> None:
        self.worker = asyncio.create_task(self._worker())
        await self.client.send_json(
            {
                "type": "status",
                "state": "connected",
                "mode": "multivoice",
                "note": "quality mode uses chunked diarization + emotion TTS",
            }
        )
        try:
            while True:
                message = await self.client.receive_json()
                msg_type = message.get("type")
                if msg_type == "audio":
                    encoded = validate_audio_b64(message.get("audio"))
                    self.buffer.extend(base64.b64decode(encoded))
                    while len(self.buffer) >= self.chunk_bytes:
                        chunk = bytes(self.buffer[: self.chunk_bytes])
                        del self.buffer[: self.chunk_bytes]
                        # Backpressure is intentional here: bounded queue means memory
                        # cannot grow forever if TTS is temporarily slower than playback.
                        await self.queue.put(chunk)
                elif msg_type == "stop":
                    if self.buffer:
                        await self.queue.put(bytes(self.buffer))
                        self.buffer.clear()
                    await self.queue.put(None)
                    if self.worker:
                        await self.worker
                    await self.client.send_json({"type": "status", "state": "closed"})
                    return
                elif msg_type == "ping":
                    await self.client.send_json({"type": "pong"})
        finally:
            if self.worker and not self.worker.done():
                self.worker.cancel()
            await self.api.close()

    async def _worker(self) -> None:
        while True:
            chunk = await self.queue.get()
            if chunk is None:
                return
            try:
                await self._process_chunk(chunk)
            except Exception as exc:  # noqa: BLE001
                await self.client.send_json(
                    {"type": "error", "message": f"Multi-voice pipeline: {exc}"}
                )

    async def _process_chunk(self, pcm: bytes) -> None:
        started = time.perf_counter()
        self.processed_chunks += 1
        await self.client.send_json({"type": "status", "state": "analyzing_speakers"})
        known_names, known_refs = self.bank.known_references()
        diarized = await self.api.diarize(pcm, known_names, known_refs)
        if not diarized:
            await self._send_metrics(started, 0)
            return

        self.bank.begin_chunk()
        enriched: list[dict[str, Any]] = []
        for raw in diarized:
            label = str(raw.get("speaker") or "speaker")
            profile = self.bank.profile_for_label(label)
            start = max(0.0, float(raw.get("start") or 0.0))
            end = max(start, float(raw.get("end") or start))
            segment_pcm = slice_pcm(pcm, start, end)
            profile.add_reference_audio(segment_pcm)
            enriched.append(
                {
                    "speaker": profile.name,
                    "voice": profile.voice,
                    "text": str(raw.get("text") or "").strip(),
                    "start": start,
                    "end": end,
                    "energy": rms_normalized(segment_pcm),
                }
            )

        enriched = [s for s in enriched if s["text"]]
        if not enriched:
            await self._send_metrics(started, 0)
            return

        await self.client.send_json(
            {"type": "speaker_bank", "speakers": self.bank.snapshot()}
        )
        await self.client.send_json({"type": "status", "state": "translating_emotion"})
        styled = await self.api.translate_emotion(self.target_language, enriched)
        styled_by_index = {int(s.get("index", -1)): s for s in styled}

        for index, segment in enumerate(enriched):
            meta = styled_by_index.get(index, {})
            translation = str(meta.get("translation") or segment["text"]).strip()
            emotion = str(meta.get("emotion") or "neutral")
            intensity = float(meta.get("intensity") or 0.35)
            voice = str(segment["voice"])

            await self.client.send_json(
                {
                    "type": "speaker_line",
                    "speaker": segment["speaker"],
                    "voice": voice,
                    "emotion": emotion,
                    "intensity": intensity,
                    "source": segment["text"],
                    "translation": translation,
                }
            )
            await self.client.send_json(
                {"type": "target_transcript", "delta": translation + " "}
            )
            await self.client.send_json(
                {"type": "status", "state": f"tts_{segment['speaker']}"}
            )
            audio = await self.api.synthesize(translation, voice, emotion, intensity)
            # 0.5 s @ 24 kHz mono PCM16 = 24,000 bytes.
            frame = 24_000
            for offset in range(0, len(audio), frame):
                piece = audio[offset : offset + frame]
                await self.client.send_json(
                    {
                        "type": "translated_audio",
                        "audio": base64.b64encode(piece).decode("ascii"),
                        "speaker": segment["speaker"],
                        "voice": voice,
                        "emotion": emotion,
                    }
                )
        await self._send_metrics(started, len(enriched))
        await self.client.send_json({"type": "status", "state": "listening"})

    async def _send_metrics(self, started: float, segment_count: int) -> None:
        await self.client.send_json(
            {
                "type": "metrics",
                "processing_ms": round((time.perf_counter() - started) * 1000),
                "queue_chunks": self.queue.qsize(),
                "processed_chunks": self.processed_chunks,
                "segments": segment_count,
                "speaker_profiles": self.bank.profile_count,
                "speaker_overflow_aliases": self.bank.overflow_aliases,
            }
        )
