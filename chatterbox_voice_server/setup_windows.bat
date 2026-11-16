@echo off
setlocal
echo ============================================================================
echo GameVox Chatterbox Multilingual V3 (Hindi) Windows Voice Server Setup
echo ============================================================================

where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Python 3.10 or 3.11 is not in PATH. Install Python from https://www.python.org/downloads/windows/
    pause
    exit /b 1
)

if not exist ".venv" (
    echo [1/4] Creating Python virtual environment (.venv)...
    python -m venv .venv
)

call .venv\Scripts\activate.bat

echo [2/4] Upgrading pip, wheel, and setuptools...
python -m pip install --upgrade pip wheel setuptools

echo [3/4] Installing PyTorch (CUDA 12.1 if NVIDIA GPU present, fallback to CPU)...
python -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu121
if %ERRORLEVEL% neq 0 (
    echo [WARN] CUDA wheel install failed; installing standard PyTorch wheels...
    python -m pip install torch torchaudio
)

echo [4/4] Installing ResembleAI Chatterbox TTS and FastAPI server dependencies...
python -m pip install -r requirements.txt
python -m pip install git+https://github.com/resemble-ai/chatterbox.git

echo.
echo ============================================================================
echo Setup complete! Run run_server_windows.bat to start the local voice server.
echo ============================================================================
pause
