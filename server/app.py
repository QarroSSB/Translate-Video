from __future__ import annotations

import os

from dotenv import load_dotenv
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from multivoice_bridge import MultiVoiceDubBridge
from openai_bridge import TranslationBridge
from protocol import validate_chunk_ms, validate_mode, validate_target_language

load_dotenv()

app = FastAPI(title="Qarro Live Translator Gateway", version="0.3.0")


def _api_key_configured() -> bool:
    key = os.getenv("OPENAI_API_KEY", "").strip()
    return bool(key and key not in {"sk-proj-...", "sk-..."})


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse(
        {
            "ok": True,
            "version": "0.3.0",
            "api_key_configured": _api_key_configured(),
            "realtime_model": os.getenv("OPENAI_REALTIME_MODEL", "gpt-realtime-translate"),
            "diarize_model": os.getenv("OPENAI_DIARIZE_MODEL", "gpt-4o-transcribe-diarize"),
            "text_model": os.getenv("OPENAI_TEXT_MODEL", "gpt-4.1-mini"),
            "tts_model": os.getenv("OPENAI_TTS_MODEL", "gpt-4o-mini-tts"),
            "modes": ["live", "multivoice"],
        }
    )


@app.websocket("/ws/translate")
async def translate(websocket: WebSocket) -> None:
    await websocket.accept()
    try:
        first = await websocket.receive_json()
        if first.get("type") != "start":
            await websocket.send_json(
                {"type": "error", "message": "First message must be type=start"}
            )
            await websocket.close(code=1008)
            return

        target_language = validate_target_language(first.get("target_language", "ru"))
        mode = validate_mode(first.get("mode", "live"))
        chunk_ms = validate_chunk_ms(first.get("chunk_ms", 6000))

        if mode == "multivoice":
            bridge = MultiVoiceDubBridge(websocket, target_language, chunk_ms)
            await bridge.run()
        else:
            bridge = TranslationBridge(websocket)
            await bridge.run(target_language)
    except WebSocketDisconnect:
        return
    except Exception as exc:  # noqa: BLE001
        try:
            await websocket.send_json({"type": "error", "message": str(exc)})
        except Exception:  # noqa: BLE001
            pass
        try:
            await websocket.close(code=1011)
        except Exception:  # noqa: BLE001
            pass
