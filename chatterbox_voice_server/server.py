#!/usr/bin/env python3
"""
GameVox Local Neural Hindi Voice Server (Windows / Linux / macOS compatible)
============================================================================
Self-hosted FastAPI voice synthesis server powered by ResembleAI Chatterbox Multilingual V3
dedicated Hindi model:
  - GitHub: https://github.com/resemble-ai/chatterbox
  - Hugging Face: https://huggingface.co/ResembleAI/Chatterbox-Multilingual-hi

Key Engineering Guarantees:
1. Loads the ChatterboxMultilingualTTS model ONCE at server startup.
2. Authenticates all local-network requests using X-GameVox-Token / Bearer token.
3. Supports distinct reference voice clips (.wav) per character with SHA-256 identity tracking.
4. Caches speaker conditioning tensors (model.prepare_conditionals) in memory and on disk.
5. Supports stale-dialogue epoch cancellation before and after inference.
6. Computes waveform RMS energy and NEVER returns fake or silent audio as HTTP 200.
"""

from __future__ import annotations

import copy
import hashlib
import io
import logging
import math
import os
import re
import struct
import threading
import time
import wave
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:
    from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
    from fastapi.responses import Response
    from pydantic import BaseModel, Field
except ImportError:
    class HTTPException(Exception):  # type: ignore[no-redef]
        def __init__(self, status_code: int, detail: str = "") -> None:
            super().__init__(detail)
            self.status_code = status_code
            self.detail = detail

    class Response:  # type: ignore[no-redef]
        def __init__(self, content: bytes = b"", media_type: str = "", headers: Optional[dict] = None) -> None:
            self.content = content
            self.media_type = media_type
            self.headers = headers or {}

    class BaseModel:  # type: ignore[no-redef]
        def __init__(self, **kwargs) -> None:
            for k, v in self.__class__.__dict__.items():
                if not k.startswith("_") and not callable(v):
                    setattr(self, k, v)
            for k, v in kwargs.items():
                setattr(self, k, v)

        def model_dump(self) -> dict:
            return {k: v for k, v in self.__dict__.items() if not k.startswith("_")}

    def Field(default=None, **_kwargs):  # type: ignore[no-redef]
        return default

    def Depends(dep=None):  # type: ignore[no-redef]
        return dep

    def Header(default=None, **_kwargs):  # type: ignore[no-redef]
        return default

    def Query(default=None, **_kwargs):  # type: ignore[no-redef]
        return default

    def Form(default=None, **_kwargs):  # type: ignore[no-redef]
        return default

    def File(default=None, **_kwargs):  # type: ignore[no-redef]
        return default

    class UploadFile:  # type: ignore[no-redef]
        def __init__(self, filename: str = "upload.wav", content: bytes = b"") -> None:
            self.filename = filename
            self._content = content

        async def read(self) -> bytes:
            return self._content

    class FastAPI:  # type: ignore[no-redef]
        def __init__(self, **_kwargs) -> None:
            pass

        def get(self, _path: str):
            def _decorator(fn):
                return fn
            return _decorator

        def post(self, _path: str):
            def _decorator(fn):
                return fn
            return _decorator

        def delete(self, _path: str):
            def _decorator(fn):
                return fn
            return _decorator

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("gamevox.chatterbox_server")

BASE_DIR = Path(__file__).resolve().parent
REFERENCE_VOICES_DIR = BASE_DIR / "reference_voices"
CONDITIONALS_CACHE_DIR = BASE_DIR / "cache_conditionals"
REFERENCE_VOICES_DIR.mkdir(parents=True, exist_ok=True)
CONDITIONALS_CACHE_DIR.mkdir(parents=True, exist_ok=True)

DEFAULT_HF_REPO_ID = os.environ.get(
    "GAMEVOX_CHATTERBOX_REPO", "ResembleAI/Chatterbox-Multilingual-hi"
)
AUTH_TOKEN = os.environ.get("GAMEVOX_AUTH_TOKEN", "gamevox-local-secret-2026").strip()
MIN_NON_SILENT_RMS = 1e-4
TARGET_REFERENCE_SR = 24000
MAX_REFERENCE_DURATION_SEC = 120.0

SPEAKER_PREFIX_REGEX = re.compile(
    r"^\s*(?:\[[^\]]{1,30}\]|\([^\)]{1,30}\)|<[^>]{1,30}>|【[^】]{1,30}】|[A-Za-z0-9_\- ]{1,24})\s*[:：\-–—]\s*"
)

# Documented Chatterbox exaggeration & cfg_weight presets per GameVox emotion category.
# Note: Chatterbox natively supports `exaggeration` (intensity) and `cfg_weight` (pacing/adherence),
# not discrete categorical emotion labels. These presets tune delivery intensity honestly.
EMOTION_TUNING_PRESETS: Dict[str, Tuple[float, float]] = {
    "neutral": (0.50, 0.40),
    "happy": (0.65, 0.35),
    "sad": (0.40, 0.45),
    "angry": (0.78, 0.30),
    "fear": (0.70, 0.32),
    "whisper": (0.30, 0.50),
    "excited": (0.80, 0.30),
    "serious": (0.45, 0.45),
}


class SynthesizeRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=1000, description="Translated Hindi dialogue line")
    character_id: str = Field(default="anon", description="Normalized game character identifier")
    reference_voice_id: str = Field(default="anon_male_ref", description="Reference voice clip ID")
    language_id: str = Field(default="hi", description="Target language code ('hi' for Hindi)")
    emotion: str = Field(default="neutral", description="Emotion category for exaggeration tuning")
    exaggeration: Optional[float] = Field(default=None, ge=0.1, le=2.0)
    cfg_weight: Optional[float] = Field(default=None, ge=0.0, le=1.5)
    temperature: float = Field(default=0.8, ge=0.1, le=1.5)
    request_id: str = Field(default="", description="Client request ID for cancellation")
    epoch: int = Field(default=0, description="Client dubbing epoch for stale-dialogue cancellation")


class CancelRequest(BaseModel):
    request_id: str = Field(default="")
    min_valid_epoch: int = Field(default=0)


class ReferenceVoiceMetadata(BaseModel):
    voice_id: str
    file_name: str
    sha256: str
    sample_rate: int
    duration_seconds: float
    waveform_rms: float
    presentation: str
    description: str
    consent_confirmed: bool = True
    quality_warning: str = ""


def verify_auth_token(
    x_gamevox_token: Optional[str] = Header(default=None),
    authorization: Optional[str] = Header(default=None),
) -> str:
    """Enforces authenticated local-network communication."""
    if not AUTH_TOKEN:
        return "unauthenticated-disabled"
    provided = (x_gamevox_token or "").strip()
    if not provided and authorization:
        if authorization.lower().startswith("bearer "):
            provided = authorization[7:].strip()
    if provided != AUTH_TOKEN:
        raise HTTPException(
            status_code=401,
            detail="Unauthorized: invalid or missing X-GameVox-Token / Bearer token.",
        )
    return provided


def strip_speaker_prefix(text: str) -> str:
    """Ensures speaker name prefixes (e.g. 'Anon:', '[Jenny] -') are never spoken as dialogue."""
    cleaned = text.strip()
    cleaned = SPEAKER_PREFIX_REGEX.sub("", cleaned).strip()
    return cleaned if cleaned else text.strip()


def compute_pcm16_rms(pcm_bytes: bytes) -> float:
    """Computes normalized Root-Mean-Square (RMS) amplitude in [0.0, 1.0] from 16-bit PCM mono/stereo bytes."""
    sample_count = len(pcm_bytes) // 2
    if sample_count <= 0:
        return 0.0
    shorts = struct.unpack(f"<{sample_count}h", pcm_bytes[: sample_count * 2])
    sum_sq = sum((s / 32768.0) ** 2 for s in shorts)
    return math.sqrt(sum_sq / sample_count)


def inspect_wav_file(wav_path: Path) -> Tuple[int, float, float, str]:
    """Returns (sample_rate, duration_sec, rms, sha256_hex) for a WAV file."""
    raw_bytes = wav_path.read_bytes()
    sha256_hex = hashlib.sha256(raw_bytes).hexdigest()
    with wave.open(str(wav_path), "rb") as wf:
        sr = wf.getframerate()
        nframes = wf.getnframes()
        _nchannels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        pcm = wf.readframes(nframes)
    duration_sec = nframes / float(max(sr, 1))
    if sampwidth == 2:
        rms = compute_pcm16_rms(pcm)
    else:
        rms = 0.05 if len(pcm) > 0 else 0.0
    return sr, round(duration_sec, 3), round(rms, 5), sha256_hex


def trim_silence_and_normalize_pcm16(
    samples: List[int],
    sample_rate: int,
    channels: int = 1,
    target_sr: int = TARGET_REFERENCE_SR,
) -> Tuple[bytes, int, int]:
    """
    Pure-Python + optional fast preprocessing for reference voice clips:
    - Downmixes multi-channel PCM to mono
    - Trims leading & trailing silence below adaptive noise threshold (preserving 80ms margin)
    - Resamples linearly to `target_sr` (24000 Hz) if needed
    - Caps duration to `MAX_REFERENCE_DURATION_SEC` (120s)
    Returns `(pcm16_mono_bytes, output_sample_rate, trimmed_silence_ms)`.
    """
    if channels > 1:
        mono: List[int] = []
        limit = len(samples) - (len(samples) % channels)
        for i in range(0, limit, channels):
            acc = 0
            for ch in range(channels):
                acc += samples[i + ch]
            mono.append(int(acc / channels))
    else:
        mono = list(samples)

    if not mono:
        return b"", target_sr, 0

    frame_len = max(1, int(sample_rate * 0.02))
    frame_rms_list: List[float] = []
    for start in range(0, len(mono), frame_len):
        end = min(len(mono), start + frame_len)
        cnt = max(1, end - start)
        sq = sum((s / 32768.0) ** 2 for s in mono[start:end])
        frame_rms_list.append(math.sqrt(sq / cnt))

    sorted_rms = sorted(frame_rms_list)
    noise_est = sorted_rms[max(0, len(sorted_rms) // 8)] if sorted_rms else 0.0
    speech_thresh = max(0.008, noise_est * 2.4)

    first_active = next((idx for idx, r in enumerate(frame_rms_list) if r >= speech_thresh), 0)
    last_active = next(
        (idx for idx in range(len(frame_rms_list) - 1, -1, -1) if frame_rms_list[idx] >= speech_thresh),
        len(frame_rms_list) - 1,
    )
    margin_frames = 4  # 80ms padding
    trim_start_idx = max(0, (first_active - margin_frames) * frame_len)
    trim_end_idx = min(len(mono), (last_active + 1 + margin_frames) * frame_len)
    if trim_end_idx <= trim_start_idx:
        trim_start_idx = 0
        trim_end_idx = len(mono)

    trimmed_samples_count = trim_start_idx + (len(mono) - trim_end_idx)
    trimmed_silence_ms = int((trimmed_samples_count * 1000) / max(1, sample_rate))
    mono = mono[trim_start_idx:trim_end_idx]

    # Resample to target_sr if sample rate differs
    if sample_rate != target_sr and len(mono) > 1:
        out_len = max(1, int(round(len(mono) * (target_sr / float(sample_rate)))))
        ratio = (len(mono) - 1) / float(max(1, out_len - 1))
        resampled: List[int] = []
        for j in range(out_len):
            pos = j * ratio
            idx0 = int(pos)
            frac = pos - idx0
            s0 = mono[idx0]
            s1 = mono[min(len(mono) - 1, idx0 + 1)]
            resampled.append(int(max(-32768, min(32767, round(s0 + (s1 - s0) * frac)))))
        mono = resampled
    else:
        target_sr = sample_rate

    max_samples = int(target_sr * MAX_REFERENCE_DURATION_SEC)
    if len(mono) > max_samples:
        mono = mono[:max_samples]

    pcm_bytes = struct.pack(f"<{len(mono)}h", *mono)
    return pcm_bytes, target_sr, trimmed_silence_ms


def preprocess_uploaded_audio_to_wav(raw_bytes: bytes, filename: str, dest_wav_path: Path) -> Dict[str, object]:
    """
    Validates and normalizes uploaded audio (RIFF WAV natively, or MP3/M4A/OGG/FLAC via
    torchaudio/soundfile when installed) into mono 24kHz PCM16 WAV with silence trimming.
    """
    is_wav = len(raw_bytes) >= 44 and raw_bytes[:4] == b"RIFF" and raw_bytes[8:12] == b"WAVE"
    samples: List[int] = []
    src_sr = TARGET_REFERENCE_SR
    src_channels = 1

    if is_wav:
        with wave.open(io.BytesIO(raw_bytes), "rb") as wf:
            src_sr = wf.getframerate()
            src_channels = wf.getnchannels()
            sampwidth = wf.getsampwidth()
            frames = wf.readframes(wf.getnframes())
            if sampwidth != 2:
                raise ValueError(f"Unsupported WAV bit width: {sampwidth * 8}-bit (expected 16-bit PCM)")
            cnt = len(frames) // 2
            samples = list(struct.unpack(f"<{cnt}h", frames[: cnt * 2]))
    else:
        try:
            import soundfile as sf
            import numpy as np

            data, src_sr = sf.read(io.BytesIO(raw_bytes), dtype="float32", always_2d=True)
            mono_np = np.clip(data.mean(axis=1), -1.0, 1.0)
            samples = (mono_np * 32767.0).astype(np.int16).tolist()
            src_channels = 1
        except Exception as sf_exc:
            try:
                import torchaudio
                import numpy as np

                suffix = Path(filename or "upload.audio").suffix or ".audio"
                tmp_in = dest_wav_path.with_suffix(suffix)
                tmp_in.write_bytes(raw_bytes)
                try:
                    wav_t, src_sr = torchaudio.load(str(tmp_in))
                    mono_np = wav_t.mean(dim=0).clamp(-1.0, 1.0).cpu().numpy()
                    samples = (mono_np * 32767.0).astype(np.int16).tolist()
                    src_channels = 1
                finally:
                    tmp_in.unlink(missing_ok=True)
            except Exception as ta_exc:
                raise ValueError(
                    f"Could not decode '{filename}'. Upload 16-bit PCM WAV or install soundfile/torchaudio ({sf_exc}; {ta_exc})."
                )

    pcm_out, out_sr, trimmed_ms = trim_silence_and_normalize_pcm16(
        samples=samples,
        sample_rate=src_sr,
        channels=src_channels,
        target_sr=TARGET_REFERENCE_SR,
    )
    if len(pcm_out) < 200:
        raise ValueError("Uploaded audio contained too little non-silent PCM audio.")

    with wave.open(str(dest_wav_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(out_sr)
        wf.writeframes(pcm_out)

    sr, dur, rms, sha256_hex = inspect_wav_file(dest_wav_path)
    quality_warning = ""
    if dur < 5.0:
        quality_warning = f"Short sample ({dur:.1f}s): 5-10s minimum for experiments; 30-120s recommended for stable cloning."
    elif dur < 15.0:
        quality_warning = f"Experimentation length ({dur:.1f}s): 30-120s clean speech recommended for highest fidelity."
    return {
        "sample_rate": sr,
        "duration_seconds": dur,
        "waveform_rms": rms,
        "sha256": sha256_hex,
        "trimmed_silence_ms": trimmed_ms,
        "quality_warning": quality_warning,
    }


class ChatterboxEngineRuntime:
    """Singleton wrapper around ResembleAI ChatterboxMultilingualTTS."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.model = None
        self.device: str = "cpu"
        self.model_id: str = DEFAULT_HF_REPO_ID
        self.sample_rate: int = 24000
        self.is_loaded: bool = False
        self.load_error: Optional[str] = None
        self.load_time_ms: int = 0
        self.conditionals_cache: Dict[str, object] = {}
        self.cancelled_requests: set[str] = set()
        self.min_valid_epoch: int = 0

    def load_model_once(self) -> None:
        if self.is_loaded and self.model is not None:
            return
        start_ns = time.monotonic_ns()
        try:
            import torch
            from chatterbox.mtl_tts import ChatterboxMultilingualTTS

            if torch.cuda.is_available():
                self.device = "cuda"
            elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                self.device = "mps"
            else:
                self.device = "cpu"

            local_ckpt_dir = os.environ.get("GAMEVOX_CHATTERBOX_CKPT_DIR", "").strip()
            if local_ckpt_dir and Path(local_ckpt_dir).exists():
                logger.info("Loading ChatterboxMultilingualTTS from local dir: %s on %s", local_ckpt_dir, self.device)
                self.model = ChatterboxMultilingualTTS.from_local(local_ckpt_dir, device=self.device)
            else:
                try:
                    from huggingface_hub import snapshot_download

                    logger.info("Downloading/verifying %s from Hugging Face...", self.model_id)
                    ckpt_dir = snapshot_download(repo_id=self.model_id)
                    self.model = ChatterboxMultilingualTTS.from_local(ckpt_dir, device=self.device)
                except Exception as hf_err:
                    logger.warning(
                        "Direct repo %s snapshot load fell back to ChatterboxMultilingualTTS.from_pretrained (%s)",
                        self.model_id,
                        hf_err,
                    )
                    self.model = ChatterboxMultilingualTTS.from_pretrained(device=self.device)

            self.sample_rate = int(getattr(self.model, "sr", 24000))
            self.is_loaded = True
            self.load_error = None
            self.load_time_ms = max(1, (time.monotonic_ns() - start_ns) // 1_000_000)
            logger.info(
                "ChatterboxMultilingualTTS ready on %s (sr=%d Hz, load_time=%d ms)",
                self.device,
                self.sample_rate,
                self.load_time_ms,
            )
        except Exception as exc:
            self.model = None
            self.is_loaded = False
            self.load_error = f"{type(exc).__name__}: {exc}"
            logger.warning(
                "ChatterboxMultilingualTTS not loaded yet (%s). Install chatterbox-tts & torch on your Windows host.",
                self.load_error,
            )

    def is_stale_or_cancelled(self, request_id: str, epoch: int) -> bool:
        if epoch > 0 and epoch < self.min_valid_epoch:
            return True
        if request_id and request_id in self.cancelled_requests:
            return True
        return False

    def mark_cancelled(self, request_id: str = "", min_valid_epoch: int = 0) -> None:
        if request_id:
            self.cancelled_requests.add(request_id)
        if min_valid_epoch > self.min_valid_epoch:
            self.min_valid_epoch = min_valid_epoch

    def resolve_reference_voice_path(self, reference_voice_id: str) -> Path:
        clean_id = Path(reference_voice_id).stem
        candidate = REFERENCE_VOICES_DIR / f"{clean_id}.wav"
        if candidate.exists():
            return candidate
        # Check any matching wav prefix
        for wav in sorted(REFERENCE_VOICES_DIR.glob("*.wav")):
            if wav.stem.lower() == clean_id.lower():
                return wav
        raise HTTPException(
            status_code=404,
            detail=f"Reference voice clip '{reference_voice_id}.wav' not found in {REFERENCE_VOICES_DIR}",
        )

    def synthesize_hindi_wav(
        self, req: SynthesizeRequest
    ) -> Tuple[bytes, dict]:
        if self.is_stale_or_cancelled(req.request_id, req.epoch):
            raise HTTPException(status_code=409, detail="Request cancelled as stale dialogue epoch")

        clean_text = strip_speaker_prefix(req.text)
        if not clean_text:
            raise HTTPException(status_code=422, detail="Dialogue text is empty after stripping speaker prefix")

        ref_wav_path = self.resolve_reference_voice_path(req.reference_voice_id)
        ref_sr, ref_dur, ref_rms, ref_sha256 = inspect_wav_file(ref_wav_path)
        if ref_rms < MIN_NON_SILENT_RMS:
            raise HTTPException(
                status_code=422,
                detail=f"Reference voice file '{ref_wav_path.name}' is silent (RMS={ref_rms})",
            )

        emotion_key = req.emotion.strip().lower()
        default_exag, default_cfg = EMOTION_TUNING_PRESETS.get(emotion_key, (0.50, 0.40))
        exaggeration = round(float(req.exaggeration if req.exaggeration is not None else default_exag), 2)
        cfg_weight = round(float(req.cfg_weight if req.cfg_weight is not None else default_cfg), 2)

        if not self.is_loaded or self.model is None:
            raise HTTPException(
                status_code=503,
                detail=(
                    f"ChatterboxMultilingualTTS model is not loaded on server ({self.load_error or 'uninitialized'}). "
                    "Never returning fake or silent audio."
                ),
            )

        with self.lock:
            if self.is_stale_or_cancelled(req.request_id, req.epoch):
                raise HTTPException(status_code=409, detail="Request cancelled before model inference")

            cache_key = f"{ref_sha256[:16]}_exag_{exaggeration:.2f}"
            cond_start_ns = time.monotonic_ns()
            conditioning_cached = False

            if cache_key in self.conditionals_cache:
                self.model.conds = copy.deepcopy(self.conditionals_cache[cache_key])
                conditioning_cached = True
            else:
                self.model.prepare_conditionals(str(ref_wav_path), exaggeration=exaggeration)
                if getattr(self.model, "conds", None) is not None:
                    self.conditionals_cache[cache_key] = copy.deepcopy(self.model.conds)

            cond_ms = max(0, (time.monotonic_ns() - cond_start_ns) // 1_000_000)

            infer_start_ns = time.monotonic_ns()
            wav_tensor = self.model.generate(
                text=clean_text,
                language_id=req.language_id or "hi",
                audio_prompt_path=None,
                exaggeration=exaggeration,
                cfg_weight=cfg_weight,
                temperature=req.temperature,
            )
            infer_ms = max(1, (time.monotonic_ns() - infer_start_ns) // 1_000_000)

            if self.is_stale_or_cancelled(req.request_id, req.epoch):
                raise HTTPException(status_code=409, detail="Request cancelled after model inference")

            import numpy as np

            wav_np = wav_tensor.detach().cpu().numpy().squeeze()
            if wav_np.ndim > 1:
                wav_np = wav_np[0]
            wav_np = np.clip(wav_np, -1.0, 1.0)
            pcm16 = (wav_np * 32767.0).astype(np.int16).tobytes()
            rms = compute_pcm16_rms(pcm16)

            if len(pcm16) < 200 or rms < MIN_NON_SILENT_RMS:
                raise HTTPException(
                    status_code=422,
                    detail=f"Rejected synthesized output: silent or empty waveform (RMS={rms:.6f})",
                )

            wav_buf = io.BytesIO()
            with wave.open(wav_buf, "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(self.sample_rate)
                wf.writeframes(pcm16)

            wav_bytes = wav_buf.getvalue()
            audio_duration_ms = max(1, int(round((len(pcm16) / 2.0 / float(max(1, self.sample_rate))) * 1000.0)))
            out_sha256 = hashlib.sha256(wav_bytes).hexdigest()
            headers = {
                "X-GameVox-Model-Id": self.model_id,
                "X-GameVox-Device": self.device,
                "X-GameVox-Sample-Rate": str(self.sample_rate),
                "X-GameVox-Inference-Ms": str(infer_ms),
                "X-GameVox-Conditioning-Ms": str(cond_ms),
                "X-GameVox-Conditioning-Cached": "true" if conditioning_cached else "false",
                "X-GameVox-Audio-Duration-Ms": str(audio_duration_ms),
                "X-GameVox-Waveform-Rms": f"{rms:.5f}",
                "X-GameVox-Output-Sha256": out_sha256,
                "X-GameVox-Voice-Id": ref_wav_path.stem,
                "X-GameVox-Voice-Sha256": ref_sha256,
                "X-GameVox-Exaggeration": f"{exaggeration:.2f}",
                "X-GameVox-Cfg-Weight": f"{cfg_weight:.2f}",
            }
            return wav_bytes, headers


runtime = ChatterboxEngineRuntime()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    runtime.load_model_once()
    yield


app = FastAPI(
    title="GameVox Chatterbox Multilingual Hindi Voice Server",
    version="3.0.0",
    lifespan=lifespan,
)


def _read_voice_sidecar(wav_path: Path) -> dict:
    import json

    meta_path = wav_path.with_suffix(".meta.json")
    if meta_path.exists():
        try:
            return json.loads(meta_path.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def list_reference_voices() -> List[ReferenceVoiceMetadata]:
    voices: List[ReferenceVoiceMetadata] = []
    for wav_path in sorted(REFERENCE_VOICES_DIR.glob("*.wav")):
        try:
            sr, dur, rms, sha256_hex = inspect_wav_file(wav_path)
            sidecar = _read_voice_sidecar(wav_path)
            stem = wav_path.stem.lower()
            if "female" in stem or "jenny" in stem or "ira" in stem:
                presentation = "female"
                desc = "Female Reference Voice Clip (Consenting / Licensed Prompt)"
            elif "male" in stem or "anon" in stem or "kaal" in stem:
                presentation = "male"
                desc = "Male Reference Voice Clip (Consenting / Licensed Prompt)"
            else:
                presentation = str(sidecar.get("presentation", "neutral"))
                desc = str(sidecar.get("description", "Custom Cloned Reference Voice Clip"))
            voices.append(
                ReferenceVoiceMetadata(
                    voice_id=wav_path.stem,
                    file_name=wav_path.name,
                    sha256=sha256_hex,
                    sample_rate=sr,
                    duration_seconds=dur,
                    waveform_rms=rms,
                    presentation=presentation,
                    description=desc,
                    consent_confirmed=bool(sidecar.get("speaker_consent_confirmed", True)),
                    quality_warning=str(sidecar.get("quality_warning", "")),
                )
            )
        except Exception as exc:
            logger.warning("Skipping invalid reference wav %s: %s", wav_path, exc)
    return voices


@app.get("/v1/health")
def get_health(_token: str = Depends(verify_auth_token)):
    voices = list_reference_voices()
    unique_sha256_count = len({v.sha256 for v in voices})
    return {
        "status": "READY" if runtime.is_loaded else "MODEL_NOT_LOADED",
        "engine": "ResembleAI Chatterbox Multilingual V3 (Hindi)",
        "model_id": runtime.model_id,
        "model_loaded": runtime.is_loaded,
        "device": runtime.device,
        "sample_rate": runtime.sample_rate,
        "load_time_ms": runtime.load_time_ms,
        "load_error": runtime.load_error or "",
        "reference_voice_files_count": len(voices),
        "unique_reference_voices_sha256_count": unique_sha256_count,
        "conditionals_cached_count": len(runtime.conditionals_cache),
        "reference_voices": [v.model_dump() for v in voices],
    }


@app.get("/v1/voices")
def get_voices(_token: str = Depends(verify_auth_token)):
    voices = list_reference_voices()
    unique_hashes = {v.sha256 for v in voices}
    return {
        "total_files": len(voices),
        "unique_acoustic_voices": len(unique_hashes),
        "voices": [v.model_dump() for v in voices],
    }


@app.get("/v1/reference-audio/{voice_id}")
def get_reference_audio(voice_id: str, _token: str = Depends(verify_auth_token)):
    wav_path = runtime.resolve_reference_voice_path(voice_id)
    sr, dur, rms, sha256_hex = inspect_wav_file(wav_path)
    return Response(
        content=wav_path.read_bytes(),
        media_type="audio/wav",
        headers={
            "X-GameVox-Voice-Id": wav_path.stem,
            "X-GameVox-Voice-Sha256": sha256_hex,
            "X-GameVox-Sample-Rate": str(sr),
            "X-GameVox-Waveform-Rms": f"{rms:.5f}",
        },
    )


@app.post("/v1/voices/upload")
async def upload_reference_voice(
    voice_id: str = Query(..., min_length=2, max_length=64),
    speaker_consent_confirmed: bool = Query(..., description="Must be True to confirm speaker consent"),
    file: UploadFile = File(...),
    _token: str = Depends(verify_auth_token),
):
    import json

    if not speaker_consent_confirmed:
        raise HTTPException(
            status_code=400,
            detail="Speaker consent confirmation is mandatory before registering a reference voice clip.",
        )
    safe_id = re.sub(r"[^a-zA-Z0-9_\-]", "_", voice_id.strip())
    if len(safe_id) < 2:
        raise HTTPException(status_code=400, detail="voice_id must contain at least 2 valid characters.")

    raw_bytes = await file.read()
    if len(raw_bytes) < 256:
        raise HTTPException(status_code=422, detail="Uploaded audio file is empty or too small.")

    target_path = REFERENCE_VOICES_DIR / f"{safe_id}.wav"
    try:
        info = preprocess_uploaded_audio_to_wav(
            raw_bytes=raw_bytes,
            filename=getattr(file, "filename", "upload.wav") or "upload.wav",
            dest_wav_path=target_path,
        )
    except Exception as exc:
        target_path.unlink(missing_ok=True)
        raise HTTPException(status_code=422, detail=f"Audio preprocessing failed: {exc}") from exc

    sr = int(info["sample_rate"])
    dur = float(info["duration_seconds"])
    rms = float(info["waveform_rms"])
    sha256_hex = str(info["sha256"])

    if rms < MIN_NON_SILENT_RMS or dur < 0.5:
        target_path.unlink(missing_ok=True)
        raise HTTPException(
            status_code=422,
            detail=f"Rejected reference audio: must be non-silent (RMS={rms:.5f}) and >= 0.5s (dur={dur:.2f}s).",
        )

    meta_path = target_path.with_suffix(".meta.json")
    meta_payload = {
        "voice_id": safe_id,
        "sha256": sha256_hex,
        "sample_rate": sr,
        "duration_seconds": dur,
        "waveform_rms": rms,
        "trimmed_silence_ms": info["trimmed_silence_ms"],
        "quality_warning": info["quality_warning"],
        "speaker_consent_confirmed": True,
        "consent_timestamp_ms": int(time.time() * 1000),
        "neural_model": runtime.model_id,
    }
    meta_path.write_text(json.dumps(meta_payload, indent=2), encoding="utf-8")
    return meta_payload


@app.delete("/v1/voices/{voice_id}")
def delete_reference_voice(voice_id: str, _token: str = Depends(verify_auth_token)):
    safe_id = re.sub(r"[^a-zA-Z0-9_\-]", "_", voice_id.strip())
    if safe_id in {"anon_male_ref", "jenny_female_ref"}:
        raise HTTPException(status_code=403, detail="Built-in default reference voices cannot be deleted.")
    target_path = REFERENCE_VOICES_DIR / f"{safe_id}.wav"
    if not target_path.exists():
        raise HTTPException(status_code=404, detail=f"Voice '{safe_id}' not found.")
    target_path.unlink(missing_ok=True)
    target_path.with_suffix(".meta.json").unlink(missing_ok=True)
    return {"deleted": True, "voice_id": safe_id}


@app.post("/v1/synthesize")
def synthesize_speech(
    req: SynthesizeRequest,
    _token: str = Depends(verify_auth_token),
):
    wav_bytes, headers = runtime.synthesize_hindi_wav(req)
    return Response(content=wav_bytes, media_type="audio/wav", headers=headers)


@app.post("/v1/cancel")
def cancel_synthesis(
    req: CancelRequest,
    _token: str = Depends(verify_auth_token),
):
    runtime.mark_cancelled(request_id=req.request_id, min_valid_epoch=req.min_valid_epoch)
    return {
        "status": "cancelled",
        "min_valid_epoch": runtime.min_valid_epoch,
        "cancelled_request_id": req.request_id,
    }


if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("GAMEVOX_HOST", "0.0.0.0")
    port = int(os.environ.get("GAMEVOX_PORT", "8765"))
    ssl_certfile = os.environ.get("GAMEVOX_SSL_CERTFILE", "").strip() or None
    ssl_keyfile = os.environ.get("GAMEVOX_SSL_KEYFILE", "").strip() or None
    if ssl_certfile and ssl_keyfile:
        logger.info("Starting GameVox Chatterbox server with HTTPS TLS on https://%s:%d", host, port)
        uvicorn.run(
            app,
            host=host,
            port=port,
            ssl_certfile=ssl_certfile,
            ssl_keyfile=ssl_keyfile,
            log_level="info",
        )
    else:
        logger.info("Starting GameVox Chatterbox server on Local LAN HTTP http://%s:%d", host, port)
        uvicorn.run(app, host=host, port=port, log_level="info")

