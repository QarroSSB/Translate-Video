#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python -m compileall -q server
(
  cd server
  pytest -q
)
python - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
for path in Path('android/app/src/main/res').rglob('*.xml'):
    ET.parse(path)
print('Android XML: OK')
PY
python - <<'PY'
import re
from pathlib import Path
pattern = re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{20,}")
bad=[]
for path in Path('.').rglob('*'):
    if not path.is_file() or any(part in {'.venv', '__pycache__', '.pytest_cache'} for part in path.parts):
        continue
    try:
        text=path.read_text(errors='ignore')
    except Exception:
        continue
    if pattern.search(text):
        bad.append(str(path))
if bad:
    raise SystemExit('Potential API key material: ' + ', '.join(bad))
print('Secret scan: OK')
PY

echo "Project verification: OK"
