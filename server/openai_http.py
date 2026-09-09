from __future__ import annotations

import json
import os
from typing import Any

import httpx

from audio_utils import pcm16_to_wav_bytes


class OpenAIHttp:
    def __init__(self) -> None:
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key or api_key.strip() in {"sk-proj-...", "sk-..."}:
            raise RuntimeError("OPENAI_API_KEY is not configured")
        self.base_url = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        self.headers = {
            "Authorization": f"Bearer {api_key}",
            "OpenAI-Safety-Identifier": os.getenv(
                "OPENAI_SAFETY_IDENTIFIER", "qarro-live-translator-local-user"
            ),
        }
        self.client = httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=20.0))

    async def close(self) -> None:
        await self.client.aclose()

    async def diarize(self, pcm: bytes, known_names: list[str], known_refs: list[str]) -> list[dict[str, Any]]:
        data: list[tuple[str, str]] = [
            ("model", os.getenv("OPENAI_DIARIZE_MODEL", "gpt-4o-transcribe-diarize")),
            ("response_format", "diarized_json"),
            ("chunking_strategy", "auto"),
        ]
        for name in known_names[:4]:
            data.append(("known_speaker_names[]", name))
        for ref in known_refs[:4]:
            data.append(("known_speaker_references[]", ref))
        files = {"file": ("chunk.wav", pcm16_to_wav_bytes(pcm), "audio/wav")}
        response = await self.client.post(
            f"{self.base_url}/audio/transcriptions",
            headers=self.headers,
            data=data,
            files=files,
        )
        response.raise_for_status()
        payload = response.json()
        return list(payload.get("segments") or [])

    async def translate_emotion(self, target_language: str, segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not segments:
            return []
        compact = [
            {
                "index": i,
                "speaker": s.get("speaker", "speaker"),
                "text": str(s.get("text", "")),
                "energy": round(float(s.get("energy", 0.0)), 4),
            }
            for i, s in enumerate(segments)
        ]
        schema = {
            "type": "object",
            "properties": {
                "segments": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "index": {"type": "integer"},
                            "translation": {"type": "string"},
                            "emotion": {
                                "type": "string",
                                "enum": [
                                    "neutral", "calm", "happy", "sad", "angry",
                                    "fearful", "excited", "surprised", "sarcastic", "tender"
                                ],
                            },
                            "intensity": {"type": "number", "minimum": 0, "maximum": 1},
                        },
                        "required": ["index", "translation", "emotion", "intensity"],
                        "additionalProperties": False,
                    },
                }
            },
            "required": ["segments"],
            "additionalProperties": False,
        }
        prompt = (
            f"Translate every segment into target language {target_language}. Preserve meaning, register, names, "
            "humor and profanity without sanitizing. Estimate the intended spoken emotion. Energy is normalized audio "
            "loudness; use it only as a weak clue. Do not add words not present in the source.\n\n"
            + json.dumps(compact, ensure_ascii=False)
        )
        body = {
            "model": os.getenv("OPENAI_TEXT_MODEL", "gpt-4.1-mini"),
            "input": prompt,
            "store": False,
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "qarro_dub_segments",
                    "strict": True,
                    "schema": schema,
                }
            },
        }
        response = await self.client.post(
            f"{self.base_url}/responses",
            headers={**self.headers, "Content-Type": "application/json"},
            json=body,
        )
        response.raise_for_status()
        payload = response.json()
        text = payload.get("output_text")
        if not isinstance(text, str):
            # REST responses always include output items; support SDK-independent parsing.
            pieces: list[str] = []
            for item in payload.get("output", []):
                for content in item.get("content", []):
                    if content.get("type") == "output_text":
                        pieces.append(str(content.get("text", "")))
            text = "".join(pieces)
        parsed = json.loads(text or "{}")
        return list(parsed.get("segments") or [])

    async def synthesize(
        self,
        text: str,
        voice: str,
        emotion: str,
        intensity: float,
        target_language: str,
        target_duration_s: float | None = None,
    ) -> bytes:
        intensity = min(1.0, max(0.0, float(intensity)))
        style = {
            "neutral": "natural and conversational",
            "calm": "calm, controlled, and warm",
            "happy": "genuinely happy and bright",
            "sad": "sad, restrained, and emotionally sincere",
            "angry": "angry and tense without distorting intelligibility",
            "fearful": "fearful, tense, and slightly breathless",
            "excited": "excited, energetic, and quick",
            "surprised": "surprised and reactive",
            "sarcastic": "dry and clearly sarcastic",
            "tender": "soft, intimate, and tender",
        }.get(emotion, "natural and conversational")
        instructions = (
            f"Speak in target language {target_language} as a professional dub actor. Delivery: {style}. "
            f"Emotion intensity about {round(intensity * 100)}%. Keep timing compact, natural, and do not narrate stage directions."
        )

        async def render(speed: float = 1.0) -> bytes:
            body = {
                "model": os.getenv("OPENAI_TTS_MODEL", "gpt-4o-mini-tts"),
                "voice": voice,
                "input": text,
                "instructions": instructions,
                "response_format": "pcm",
                "stream_format": "audio",
                "speed": round(speed, 2),
            }
            response = await self.client.post(
                f"{self.base_url}/audio/speech",
                headers={**self.headers, "Content-Type": "application/json"},
                json=body,
            )
            response.raise_for_status()
            return response.content

        audio = await render(1.0)
        if target_duration_s and target_duration_s >= 0.4:
            actual_s = len(audio) / float(24_000 * 2)
            if actual_s > target_duration_s * 1.20:
                speed = min(1.60, max(1.05, actual_s / target_duration_s))
                audio = await render(speed)
        return audio
