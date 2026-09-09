@echo off
setlocal
cd /d %~dp0\..\server
if not exist .venv python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install -q -r requirements.txt
if not exist .env copy .env.example .env >nul
python -c "from dotenv import load_dotenv; import os,sys; load_dotenv(); k=os.getenv('OPENAI_API_KEY','').strip(); sys.exit(0 if k and k not in {'sk-proj-...','sk-...'} else 2)"
if errorlevel 1 (
  echo.
  echo OPENAI_API_KEY ne nastroen. Otkroy server\.env, vstav klyuch i zapusti skript snova.
  echo.
  exit /b 2
)
python -m uvicorn app:app --host 0.0.0.0 --port 8765
