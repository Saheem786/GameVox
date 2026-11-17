# GameVox Local Neural Hindi Voice Server — Windows Setup & Architecture Guide

This folder contains the self-hosted, 100% free **FastAPI Voice Server** for **GameVox** powered by **ResembleAI Chatterbox Multilingual V3 (Dedicated Hindi Model)**:
- **Official Repository**: `https://github.com/resemble-ai/chatterbox`
- **Dedicated Hindi Weights**: `https://huggingface.co/ResembleAI/Chatterbox-Multilingual-hi`

---

## 1. Why a Local Neural Server?

On-device mobile INT8 TTS models (like Supertonic 3) are fast (~400–1100 ms on CPU) and work 100% offline, but all 10 built-in speaker embeddings come from the same compact vocoder architecture and can sound similar across characters.

By running **ResembleAI Chatterbox Multilingual (`Chatterbox-Multilingual-hi`)** on your Windows PC over local Wi-Fi:
1. **Zero-Shot Reference Voice Cloning**: Each game character (`Anon` Male, `Jenny` Female, etc.) uses a distinct real human reference `.wav` clip (`reference_voices/anon_male_ref.wav`, `reference_voices/jenny_female_ref.wav`, or any consenting speaker `.wav` you upload).
2. **Conditioning Tensor Cache**: `server.py` calls `model.prepare_conditionals(ref_wav_path, exaggeration=...)` **once** per `(voice_sha256, exaggeration)` and caches the resulting `Conditionals` (`t3` + `s3gen`) in RAM so subsequent game subtitles skip voice-encoder extraction.
3. **Zero Paid APIs**: Runs 100% locally on your Windows PC and Android phone over LAN with `X-GameVox-Token` authentication.

---

## 2. Step-by-Step Windows Installation

### Prerequisites
- **Windows 10 / 11 (64-bit)**
- **Python 3.10 or 3.11** (64-bit, with "Add Python to PATH" checked)
- **Git for Windows** (`https://git-scm.com/download/win`)
- **Recommended Hardware**: NVIDIA RTX GPU (6 GB+ VRAM) with CUDA for sub-second inference. (CPU-only laptops will still run `ChatterboxMultilingualTTS` on `"cpu"`, see Section 5 for lighter open-weight alternatives if your laptop lacks a discrete GPU.)

### Installation Steps
1. Extract `GameVox-Chatterbox-Windows-Server.zip` (or open the `chatterbox_voice_server/` folder).
2. Double-click **`setup_windows.bat`** (or run in PowerShell/CMD):
   ```bat
   setup_windows.bat
   ```
   This creates `.venv`, installs PyTorch (CUDA 12.1 or CPU), `fastapi`, `uvicorn`, `huggingface-hub`, and `chatterbox-tts`.
3. Place or replace consenting reference speaker `.wav` clips (3–8 seconds of clean speech, 16-bit PCM mono WAV) inside `reference_voices/`:
   - `reference_voices/anon_male_ref.wav` (Male protagonist reference voice)
   - `reference_voices/jenny_female_ref.wav` (Female companion reference voice)
   *(Two distinct baseline reference `.wav` clips are pre-bundled for immediate vertical-slice verification; replace them with consenting human voice recordings for maximum naturalness.)*
4. Double-click **`run_server_windows.bat`** to start the server on port `8765`:
   ```bat
   run_server_windows.bat
   ```
   On first launch, `huggingface_hub` downloads `ResembleAI/Chatterbox-Multilingual-hi` once and caches it in `~/.cache/huggingface/hub/`.
5. Allow inbound TCP port `8765` on Windows Defender Firewall (Private Network only):
   ```powershell
   New-NetFirewallRule -DisplayName "GameVox Local Neural TTS" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow -Profile Private
   ```
6. Open the **GameVox** Android app:
   - Select Dubbing Mode: **`Human Voice — Local Neural Server`**
   - Enter your Windows PC's LAN IPv4 address (shown in the `run_server_windows.bat` console, e.g., `http://192.168.1.42:8765`)
   - Enter Auth Token: `gamevox-local-secret-2026`
   - Tap **Test Server Connection** or open the **A/B Voice Testing** screen.

---

## 3. REST API Endpoints (Authenticated via `X-GameVox-Token`)

| Endpoint | Method | Purpose |
| :--- | :--- | :--- |
| `/v1/health` | `GET` | Returns model status, device (`cuda`/`cpu`), sample rate, cached conditionals count, and **unique SHA-256 reference voice count**. |
| `/v1/voices` | `GET` | Lists all `.wav` reference clips in `reference_voices/` with SHA-256 hashes, durations, and RMS energy. |
| `/v1/reference-audio/{voice_id}` | `GET` | Streams the raw reference `.wav` clip so the Android app can preview the original reference voice separately from synthesized Hindi speech. |
| `/v1/voices/upload` | `POST` | Uploads a new consenting speaker audio clip (`speaker_consent_confirmed=true` mandatory), strips silence, downmixes to 24 kHz mono WAV (up to 120s), validates non-silent RMS, and saves a metadata sidecar. |
| `/v1/voices/{voice_id}` | `DELETE` | Deletes an uploaded cloned voice clip, its metadata sidecar, and cached conditioning tensors from RAM/disk. |
| `/v1/synthesize` | `POST` | Synthesizes Hindi dialogue using `ChatterboxMultilingualTTS.generate(text, language_id="hi", exaggeration=..., cfg_weight=...)`, validates non-silent RMS (`>= 1e-4`), and returns `audio/wav` with latency headers. |
| `/v1/cancel` | `POST` | Marks a `request_id` or `epoch` stale so queued/in-flight synthesis aborts when the gamer pauses, stops, or advances dialogue. |

---

## 4. Honest Emotion Control Notes

Chatterbox Multilingual exposes two continuous conditioning parameters during inference:
- **`exaggeration`** (`0.25` – `2.0`, default `0.50`): Controls vocal expressiveness and emphasis intensity.
- **`cfg_weight`** (`0.0` – `1.0`, default `0.40`): Classifier-Free Guidance / pacing adherence weight (lower values `~0.30` help cross-lingual Hindi flow).

GameVox maps its 8 dialogue emotion categories (`Neutral`, `Happy`, `Sad`, `Angry`, `Fear`, `Whisper`, `Excited`, `Serious`) to calibrated `(exaggeration, cfg_weight)` pairs. **Important**: `exaggeration` modulates expressive intensity on top of the reference clip's prosody; it is not a discrete theatrical emotion switch. For dramatic whispering or shouting, pairing an expressive reference `.wav` clip with `exaggeration` yields the most natural result.

---

## 5. Hardware Performance & Lighter Open-Weight Alternatives

- **NVIDIA RTX 3060 / 4060 Laptop GPU (`cuda`)**: Typical Chatterbox Hindi inference is `~350–900 ms` per sentence after `prepare_conditionals` is cached.
- **Windows Laptop CPU-Only (`cpu`)**: Because Chatterbox (`0.5B` Llama backbone + S3Gen flow matching) is a heavy generative model, CPU-only inference on a laptop can take `4,000–12,000 ms` per sentence—too slow for real-time action game subtitles.
- **Recommended Lighter Open-Weight Alternatives if Your Laptop Lacks a Discrete GPU**:
  1. **Fast Offline Voice — Supertonic 3 (Built into GameVox Android APK)**: Runs in-process on the Android phone's ARM64 CPU in `~640–1,100 ms` (`RTF ~0.30x–0.45x`) with zero PC required.
  2. **AI4Bharat Indic Parler-TTS (`ai4bharat/indic-parler-tts`)**: Open-weight Hindi/Indic model supporting natural-language speaker style prompts on modest GPUs.
  3. **Meta MMS-TTS Hindi (`facebook/mms-tts-hin`) / Piper VITS Hindi**: Ultra-lightweight (`< 100 MB`) VITS models capable of `< 150 ms` synthesis on laptop CPUs.
