from __future__ import annotations

from dataclasses import dataclass, field

from audio_utils import duration_s, wav_data_url


@dataclass
class SpeakerProfile:
    name: str
    voice: str
    pcm_parts: list[bytes] = field(default_factory=list)
    pcm_bytes: int = 0

    def add_reference_audio(self, pcm: bytes) -> None:
        # The diarization API accepts useful known-speaker references in the ~2–10s range.
        if not pcm or self.reference_ready:
            return
        max_bytes = 10 * 24_000 * 2
        remaining = max_bytes - self.pcm_bytes
        if remaining <= 0:
            return
        part = pcm[:remaining]
        self.pcm_parts.append(part)
        self.pcm_bytes += len(part)

    @property
    def reference_pcm(self) -> bytes:
        return b"".join(self.pcm_parts)

    @property
    def reference_ready(self) -> bool:
        return duration_s(self.reference_pcm) >= 2.0

    @property
    def reference_url(self) -> str | None:
        pcm = self.reference_pcm
        if duration_s(pcm) < 2.0:
            return None
        return wav_data_url(pcm[: 10 * 24_000 * 2])


class SpeakerBank:
    """Maps diarization labels onto stable dub voices.

    Unknown diarization labels are only aliases inside one analysis chunk because labels
    such as ``speaker_0`` are not guaranteed to have the same identity in a later API call.
    Once a profile has >=2 seconds of reference audio it is sent back as a *known speaker*,
    allowing the diarizer to return our stable name directly.

    ``max_profiles`` prevents short/noisy clips from growing an unbounded list of fake
    speakers before references become reliable.
    """

    VOICES = ["onyx", "coral", "echo", "nova", "cedar", "marin", "ash", "sage"]

    def __init__(self, max_known_refs: int = 4, max_profiles: int = 8) -> None:
        self.max_known_refs = max_known_refs
        self.max_profiles = max(1, max_profiles)
        self._profiles: dict[str, SpeakerProfile] = {}
        self._chunk_aliases: dict[str, str] = {}
        self._overflow_aliases = 0

    def begin_chunk(self) -> None:
        self._chunk_aliases = {}

    def _new_profile(self) -> SpeakerProfile:
        index = len(self._profiles)
        stable_name = f"Speaker {chr(ord('A') + index)}"
        voice = self.VOICES[index % len(self.VOICES)]
        profile = SpeakerProfile(name=stable_name, voice=voice)
        self._profiles[stable_name] = profile
        return profile

    def profile_for_label(self, label: str) -> SpeakerProfile:
        # Known-speaker references come back under our stable names.
        if label in self._profiles:
            return self._profiles[label]
        if label in self._chunk_aliases:
            return self._profiles[self._chunk_aliases[label]]

        if len(self._profiles) < self.max_profiles:
            profile = self._new_profile()
        else:
            # Do not invent Speaker I/J/K forever on noisy or rapidly changing material.
            # Prefer a not-yet-stable profile so it can collect enough audio to become
            # a known reference; otherwise rotate among existing profiles deterministically.
            candidates = [p for p in self._profiles.values() if not p.reference_ready]
            if not candidates:
                candidates = list(self._profiles.values())
            profile = min(candidates, key=lambda p: (p.pcm_bytes, p.name))
            self._overflow_aliases += 1

        self._chunk_aliases[label] = profile.name
        return profile

    def known_references(self) -> tuple[list[str], list[str]]:
        names: list[str] = []
        refs: list[str] = []
        for profile in self._profiles.values():
            if len(names) >= self.max_known_refs:
                break
            ref = profile.reference_url
            if ref:
                names.append(profile.name)
                refs.append(ref)
        return names, refs

    @property
    def profile_count(self) -> int:
        return len(self._profiles)

    @property
    def overflow_aliases(self) -> int:
        return self._overflow_aliases

    def snapshot(self) -> list[dict[str, str | bool | int]]:
        return [
            {
                "speaker": p.name,
                "voice": p.voice,
                "reference_ready": p.reference_ready,
                "reference_ms": round(duration_s(p.reference_pcm) * 1000),
            }
            for p in self._profiles.values()
        ]
