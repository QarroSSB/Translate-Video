from __future__ import annotations

import base64
from dataclasses import dataclass


SUPPORTED_MODES = {"live", "multivoice"}


@dataclass(frozen=True)
class StartMessage:
    target_language: str = "ru"
    mode: str = "live"
    chunk_ms: int = 6000


def validate_target_language(value: object) -> str:
    if not isinstance(value, str):
        return "ru"
    value = value.strip()
    if not value or len(value) > 16:
        return "ru"
    if not all(ch.isalpha() or ch in "-_" for ch in value):
        return "ru"
    return value


def validate_mode(value: object) -> str:
    if not isinstance(value, str):
        return "live"
    value = value.strip().lower()
    return value if value in SUPPORTED_MODES else "live"


def validate_chunk_ms(value: object) -> int:
    try:
        n = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 6000
    return min(12000, max(3000, n))


def validate_audio_b64(value: object) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError("audio must be a non-empty base64 string")
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception as exc:  # noqa: BLE001
        raise ValueError("audio is not valid base64") from exc
    if len(raw) % 2 != 0:
        raise ValueError("PCM16 payload must have an even byte length")
    if len(raw) > 24000 * 2 * 2:  # max ~2 seconds mono PCM16 @ 24 kHz/message
        raise ValueError("audio chunk is too large")
    return value
