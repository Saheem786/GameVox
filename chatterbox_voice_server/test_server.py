#!/usr/bin/env python3
"""
Unit tests for GameVox Chatterbox Multilingual FastAPI Voice Server (server.py).
Tests authentication, speaker prefix stripping, non-silent RMS validation,
reference voice SHA-256 deduplication, stale epoch cancellation, and fail-fast
503 behavior when the neural model weights are not loaded.
"""

import io
import math
import struct
import unittest
import wave
from pathlib import Path

import server


def make_test_wav_bytes(freq_hz: float = 220.0, duration_sec: float = 1.0, sr: int = 24000, amplitude: float = 0.35) -> bytes:
    n_samples = int(sr * duration_sec)
    samples = [
        int(max(-1.0, min(1.0, amplitude * math.sin(2.0 * math.pi * freq_hz * i / sr))) * 32767.0)
        for i in range(n_samples)
    ]
    pcm = struct.pack(f"<{n_samples}h", *samples)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm)
    return buf.getvalue()


class TestChatterboxVoiceServer(unittest.TestCase):

    def test_strip_speaker_prefix_never_speaks_character_labels(self):
        self.assertEqual(
            server.strip_speaker_prefix("Anon: यहाँ से निकलो, जल्दी!"),
            "यहाँ से निकलो, जल्दी!",
        )
        self.assertEqual(
            server.strip_speaker_prefix("[Jenny] - मुझे तुम्हारी कितनी फिक्र हो रही थी!"),
            "मुझे तुम्हारी कितनी फिक्र हो रही थी!",
        )
        self.assertEqual(
            server.strip_speaker_prefix("【Kaal】: तुम्हें लगता है तुम मुझे हरा दोगे?"),
            "तुम्हें लगता है तुम मुझे हरा दोगे?",
        )

    def test_compute_pcm16_rms_rejects_silence_and_accepts_speech(self):
        silent_pcm = bytes(24000 * 2)
        self.assertEqual(server.compute_pcm16_rms(silent_pcm), 0.0)

        active_wav = make_test_wav_bytes(freq_hz=180.0, duration_sec=0.5, amplitude=0.4)
        with wave.open(io.BytesIO(active_wav), "rb") as wf:
            pcm = wf.readframes(wf.getnframes())
        rms = server.compute_pcm16_rms(pcm)
        self.assertGreater(rms, 0.2)

    def test_emotion_presets_cover_all_8_categories(self):
        expected = {"neutral", "happy", "sad", "angry", "fear", "whisper", "excited", "serious"}
        self.assertEqual(set(server.EMOTION_TUNING_PRESETS.keys()), expected)
        for emotion, (exag, cfg) in server.EMOTION_TUNING_PRESETS.items():
            self.assertTrue(0.25 <= exag <= 1.0, f"Invalid exaggeration for {emotion}: {exag}")
            self.assertTrue(0.20 <= cfg <= 0.60, f"Invalid cfg_weight for {emotion}: {cfg}")

    def test_epoch_and_request_cancellation(self):
        rt = server.ChatterboxEngineRuntime()
        self.assertFalse(rt.is_stale_or_cancelled("req_1", epoch=5))
        rt.mark_cancelled(request_id="req_1", min_valid_epoch=6)
        self.assertTrue(rt.is_stale_or_cancelled("req_1", epoch=6))
        self.assertTrue(rt.is_stale_or_cancelled("req_2", epoch=5))
        self.assertFalse(rt.is_stale_or_cancelled("req_2", epoch=6))

    def test_reference_voices_exist_and_have_distinct_sha256_hashes(self):
        voices = server.list_reference_voices()
        self.assertGreaterEqual(len(voices), 2, "Must bundle at least 2 distinct reference voices (male & female)")
        by_id = {v.voice_id: v for v in voices}
        self.assertIn("anon_male_ref", by_id)
        self.assertIn("jenny_female_ref", by_id)
        self.assertNotEqual(
            by_id["anon_male_ref"].sha256,
            by_id["jenny_female_ref"].sha256,
            "Male and Female reference clips must have distinct SHA-256 audio contents",
        )
        self.assertGreater(by_id["anon_male_ref"].waveform_rms, 0.01)
        self.assertGreater(by_id["jenny_female_ref"].waveform_rms, 0.01)

    def test_trim_silence_downmix_stereo_and_resample_to_24k(self):
        sr_in = 16000
        silent_half_sec = [0] * (sr_in // 2 * 2)  # 0.5s stereo silence
        active_samples = []
        for i in range(sr_in):  # 1.0s stereo 220Hz
            val = int(0.4 * math.sin(2.0 * math.pi * 220.0 * i / sr_in) * 32767.0)
            active_samples.extend([val, val])
        pcm_out, out_sr, trimmed_ms = server.trim_silence_and_normalize_pcm16(
            samples=silent_half_sec + active_samples + silent_half_sec,
            sample_rate=sr_in,
            channels=2,
            target_sr=24000,
        )
        self.assertEqual(out_sr, 24000)
        self.assertGreater(trimmed_ms, 500)
        out_dur = (len(pcm_out) // 2) / float(out_sr)
        self.assertTrue(0.95 <= out_dur <= 1.35, f"Expected trimmed resampled ~1.16s, got {out_dur:.3f}s")
        self.assertGreater(server.compute_pcm16_rms(pcm_out), 0.15)

    def test_upload_requires_consent_and_rejects_unloaded_model_synthesis(self):
        import asyncio
        import tempfile

        wav_bytes = make_test_wav_bytes(freq_hz=240.0, duration_sec=2.0, sr=16000, amplitude=0.3)

        class _Upload(server.UploadFile):
            def __init__(self, data: bytes) -> None:
                super().__init__(filename="voice.wav", content=data)

        with self.assertRaises(server.HTTPException) as ctx_consent:
            asyncio.run(
                server.upload_reference_voice(
                    voice_id="test_speaker_no_consent",
                    speaker_consent_confirmed=False,
                    file=_Upload(wav_bytes),
                    _token="ok",
                )
            )
        self.assertEqual(ctx_consent.exception.status_code, 400)

        with tempfile.TemporaryDirectory() as tmpdir:
            old_dir = server.REFERENCE_VOICES_DIR
            try:
                server.REFERENCE_VOICES_DIR = Path(tmpdir)
                meta = asyncio.run(
                    server.upload_reference_voice(
                        voice_id="unittest_speaker_1",
                        speaker_consent_confirmed=True,
                        file=_Upload(wav_bytes),
                        _token="ok",
                    )
                )
                self.assertEqual(meta["voice_id"], "unittest_speaker_1")
                self.assertEqual(meta["sample_rate"], 24000)
                self.assertTrue(meta["speaker_consent_confirmed"])
                self.assertTrue((Path(tmpdir) / "unittest_speaker_1.wav").exists())
                self.assertTrue((Path(tmpdir) / "unittest_speaker_1.meta.json").exists())

                rt = server.ChatterboxEngineRuntime()
                rt.is_loaded = False
                req = server.SynthesizeRequest(
                    text="नमस्ते दोस्त!",
                    character_id="anon",
                    reference_voice_id="unittest_speaker_1",
                    language_id="hi",
                )
                with self.assertRaises(server.HTTPException) as ctx_503:
                    rt.synthesize_hindi_wav(req)
                self.assertEqual(ctx_503.exception.status_code, 503)
            finally:
                server.REFERENCE_VOICES_DIR = old_dir


if __name__ == "__main__":
    unittest.main()
