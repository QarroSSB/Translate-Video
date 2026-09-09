from __future__ import annotations

import base64
import io
import math
import struct
import wave

SAMPLE_RATE = 24_000
SAMPLE_WIDTH = 2
CHANNELS = 1
BYTES_PER_SECOND = SAMPLE_RATE * SAMPLE_WIDTH * CHANNELS


def pcm16_to_wav_bytes(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm)
    return out.getvalue()


def wav_data_url(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> str:
    encoded = base64.b64encode(pcm16_to_wav_bytes(pcm, sample_rate)).decode("ascii")
    return "data:audio/wav;base64," + encoded


def slice_pcm(pcm: bytes, start_s: float, end_s: float, sample_rate: int = SAMPLE_RATE) -> bytes:
    start = max(0, int(start_s * sample_rate) * 2)
    end = min(len(pcm), int(end_s * sample_rate) * 2)
    if end <= start:
        return b""
    return pcm[start:end]


def duration_s(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> float:
    return len(pcm) / float(sample_rate * 2)


def rms_normalized(pcm: bytes) -> float:
    if len(pcm) < 2:
        return 0.0
    count = len(pcm) // 2
    total = 0.0
    for (sample,) in struct.iter_unpack("<h", pcm[: count * 2]):
        total += float(sample * sample)
    return min(1.0, math.sqrt(total / max(1, count)) / 32768.0)
