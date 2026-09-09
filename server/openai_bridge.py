from __future__ import annotations

import asyncio
import json
import os
from typing import Any

import websockets
from fastapi import WebSocket
from websockets.asyncio.client import ClientConnection

from protocol import validate_audio_b64, validate_target_language


class TranslationBridge:
    def __init__(self, client: WebSocket) -> None:
        self.client = client
        self.upstream: ClientConnection | None = None
        self.closing = False

    async def connect_upstream(self, target_language: str) -> None:
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key or api_key.strip() in {"sk-proj-...", "sk-..."}:
            raise RuntimeError("OPENAI_API_KEY is not configured")

        model = os.getenv("OPENAI_REALTIME_MODEL", "gpt-realtime-translate")
        safety_id = os.getenv(
            "OPENAI_SAFETY_IDENTIFIER", "qarro-live-translator-local-user"
        )
        url = f"wss://api.openai.com/v1/realtime/translations?model={model}"
        self.upstream = await websockets.connect(
            url,
            additional_headers={
                "Authorization": f"Bearer {api_key}",
                "OpenAI-Safety-Identifier": safety_id,
            },
            max_size=8 * 1024 * 1024,
            ping_interval=20,
            ping_timeout=20,
        )

        await self.upstream.send(
            json.dumps(
                {
                    "type": "session.update",
                    "session": {
                        "audio": {
                            "output": {
                                "language": validate_target_language(target_language)
                            }
                        }
                    },
                }
            )
        )

    async def client_to_upstream(self) -> None:
        assert self.upstream is not None
        while True:
            message = await self.client.receive_json()
            msg_type = message.get("type")
            if msg_type == "audio":
                audio = validate_audio_b64(message.get("audio"))
                await self.upstream.send(
                    json.dumps(
                        {
                            "type": "session.input_audio_buffer.append",
                            "audio": audio,
                        }
                    )
                )
            elif msg_type == "stop":
                await self.close_upstream()
                return
            elif msg_type == "ping":
                await self.client.send_json({"type": "pong"})

    async def upstream_to_client(self) -> None:
        assert self.upstream is not None
        async for raw in self.upstream:
            event: dict[str, Any] = json.loads(raw)
            event_type = event.get("type")

            if event_type == "session.output_audio.delta":
                await self.client.send_json(
                    {"type": "translated_audio", "audio": event.get("delta", "")}
                )
            elif event_type == "session.output_transcript.delta":
                await self.client.send_json(
                    {"type": "target_transcript", "delta": event.get("delta", "")}
                )
            elif event_type == "session.input_transcript.delta":
                await self.client.send_json(
                    {"type": "source_transcript", "delta": event.get("delta", "")}
                )
            elif event_type == "session.closed":
                await self.client.send_json({"type": "status", "state": "closed"})
                return
            elif event_type == "error":
                await self.client.send_json(
                    {
                        "type": "error",
                        "message": event.get("error", {}).get("message", "OpenAI error"),
                    }
                )

    async def close_upstream(self) -> None:
        if self.closing or self.upstream is None:
            return
        self.closing = True
        try:
            await self.upstream.send(json.dumps({"type": "session.close"}))
        except Exception:  # noqa: BLE001
            pass
        finally:
            try:
                await self.upstream.close()
            except Exception:  # noqa: BLE001
                pass
            self.upstream = None

    async def run(self, target_language: str) -> None:
        await self.connect_upstream(target_language)
        await self.client.send_json({"type": "status", "state": "connected"})

        sender = asyncio.create_task(self.client_to_upstream())
        receiver = asyncio.create_task(self.upstream_to_client())
        done, pending = await asyncio.wait(
            {sender, receiver}, return_when=asyncio.FIRST_COMPLETED
        )
        for task in pending:
            task.cancel()
        for task in done:
            if task.cancelled():
                continue
            exc = task.exception()
            if exc:
                raise exc
        await self.close_upstream()
