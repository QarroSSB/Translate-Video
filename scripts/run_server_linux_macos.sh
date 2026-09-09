#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../server"

if [ ! -d .venv ]; then
  python3 -m venv .venv
fi
source .venv/bin/activate
python -m pip install -q -r requirements.txt
[ -f .env ] || cp .env.example .env

python - <<'PY'
import os
from dotenv import load_dotenv
load_dotenv()
key = os.getenv("OPENAI_API_KEY", "").strip()
if not key or key in {"sk-proj-...", "sk-..."}:
    raise SystemExit("\nOPENAI_API_KEY не настроен. Открой server/.env, вставь ключ и запусти скрипт снова.\n")
PY

exec python -m uvicorn app:app --host 0.0.0.0 --port 8765
