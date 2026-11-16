@echo off
setlocal
echo ============================================================================
echo Starting GameVox Chatterbox Multilingual V3 (Hindi) Local Voice Server
echo ============================================================================

if exist ".venv\Scripts\activate.bat" (
    call .venv\Scripts\activate.bat
)

if "%GAMEVOX_AUTH_TOKEN%"=="" (
    set GAMEVOX_AUTH_TOKEN=gamevox-local-secret-2026
)
if "%GAMEVOX_PORT%"=="" (
    set GAMEVOX_PORT=8765
)
if "%GAMEVOX_CHATTERBOX_REPO%"=="" (
    set GAMEVOX_CHATTERBOX_REPO=ResembleAI/Chatterbox-Multilingual-hi
)

echo.
echo Local Wi-Fi IPv4 Addresses on this Windows PC:
ipconfig | findstr /i "IPv4"
echo.
echo Enter the above IPv4 address (e.g. http://192.168.1.100:%GAMEVOX_PORT%)
echo and Auth Token "%GAMEVOX_AUTH_TOKEN%" in GameVox Android App -> Neural Server.
echo.

python server.py
pause
