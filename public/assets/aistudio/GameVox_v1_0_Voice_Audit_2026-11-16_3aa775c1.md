# GameVox v1.0 — Independent voice-routing audit (2026-11-16)

## Files inspected
- `/mnt/data/GameVox-main (2).zip`: SHA-256 `f677fa81ecca9af552d5732835a031215fcd687df0fa69fbe2eb6797336c5131`; size 28,993,735 bytes.
- `/mnt/data/GameVox-Hindi-v1.0-debug (2).apk`: SHA-256 `181752aef2238d86903ed913e5cf9cdbe55e5472370d4edf0222ef4f5de000e1`; size 120,990,187 bytes.
- Native Sherpa `.so` files are present in APK under `lib/arm64-v8a/` and match source ZIP byte for byte: `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libonnxruntime.so`.
- Two WAV references bundled in the APK: `anon_male_ref.wav` (6.98 sec, 22,050 Hz, mono, RMS ~0.0482) and `jenny_female_ref.wav` (6.50 sec, 22,050 Hz, mono, RMS ~0.0493). Their SHA-256 values differ, proving distinct bytes, **not** subjective quality or gender.
- No `.onnx` model files packaged in the APK; on-device Supertonic inference requires model download or prior installation.
- Source ZIP contains `chatterbox_voice_server/server.py` and its Windows setup files. It is a separate host-side service and is **not** an automatically running Android component.

## Confirmed source defects / architecture mismatches

### P0-1: Engine selection defaults to system TTS, not character-cloned speech
`app/src/main/java/com/example/data/TranslatorStateRepository.kt` default `freeVoiceBackend = FreeVoiceBackend.ANDROID_USER_SELECTED` (~line 195). `ForegroundTtsManager.kt` branches to Chatterbox only when `freeVoiceBackend == LOCAL_CHATTERBOX_SERVER`, to Sherpa when explicit selection or model readiness, and otherwise to `speakOnAndroidTts` (~lines 1347, 1582, 1770). On a phone without an installed and validated neural model or running configured local server, Google/installed TTS can be used, providing little or no speaker separation.

**Fix:** explicitly select and persist the requested backend. Show the **actual engine on every utterance** (not a predicted state), and require a working remote server and two loaded distinct references for Human Voice. Do not silently claim neural voice while using system fallback.

### P0-2: Preview and live-game voice engine selection differ
`ForegroundTtsManager.synthesizeCharacterPreview` (~line 2552) uses Chatterbox if **either** the global backend is Chatterbox **or** the character profile's `voiceEngine == "chatterbox_local_server"`. `ForegroundTtsManager.speakNonBlocking` (~line 1347) uses Chatterbox **only if** the global backend is Chatterbox. `CharacterVoiceAssignment` defaults `voiceEngine = "chatterbox_local_server"` for its profiles. Thus a preview may try Chatterbox while the live game follows Sherpa or system fallback under the same global setting. This matches the user's observed discrepancy.

**Fix:** use one backend resolver for both preview and live playback, with the same settings and per-character overrides. A profile's voice preference must have identical precedence in both paths, or global choice must uniformly dominate both.

### P0-3: Unknown / unlabeled game subtitles default to same speaker
`CharacterVoiceRouter.routeLiveSubtitle` (~lines 288-344) uses a 15,000 ms continuation for unlabeled subtitles and then falls back to a selected/default character (`anon` typically). Without a successfully read explicit speaker tag, a rapid male↔female exchange can be dubbed using the same speaker for both lines. `SpeakerHeuristicEngine` guesses name headers based on first line capitalization or known name lists (~lines 312-435), not reliable across all games.

**Fix:** ensure OCR ROI includes speaker label; display `rawOCRName → normalizedName → resolvedSID` in real time. When label confidence is weak, show Unknown/Manual Assignment instead of silently assigning Anon. Keep an explicit scene mapping only when corroborated; do not assume every line in 15 seconds has the same speaker.

### P1-4: Preview vs Live comparison can pass without real neural audio
`CharacterVoiceRouter.runSingleCharacterPreviewVsLiveComparison` (~lines 560-670) synthesizes a contrived `[Character]: dialogue` string, not a game capture. `synthesizeRouteToWav` (~lines 730-772) calls `writeDeterministicDiagnosticWav` if the native model is unavailable or generation throws. This function generates **synthetic periodic tones** (not Hindi speech) with distinct frequencies for different SID values. The comparison accepts non-silent RMS, speaker ID match, different WAV bytes, and `overallPassed` can be true despite no real neural Hindi voice.

**Fix:** Never report acoustic verification PASS for diagnostic/synthetic tones. Replace with `NOT_TESTED_NO_MODEL`. Compare actual WAV captured from the in-game route and preview, record backend ID, SHA, and SID after native synthesis, and require human listening to assert voice quality.

### P1-5: Only two true reference audio recordings for 24+ character profiles
`CharacterVoiceAssignment.kt` uses `anon_male_ref` and `jenny_female_ref` throughout its 24 defaults. Different archetype labels, speed, `exaggeration`, and SID cannot create 24 unique Chatterbox reference identities. With Chatterbox the 24 profiles principally share two identities until new licensed/consented references are imported.

**Fix:** UI should state **2 unique reference recordings**. Distinguish per-character identity from acoustic style. Add independent references for additional genuinely unique roles, only with consent and appropriate rights.

### P1-6: Same line from a different speaker may be wrongly skipped
`OcrTranslationPipeline.handleExtractedSubtitle` (~line 469) checks repeat similarity only by normalized dialogue. `SubtitleDubbingQueue.isDuplicate` compares English and Hindi text alone, not character ID or route SID. A repeated sentence from two different speakers can be rejected as duplicate.

**Fix:** anti-repeat cache key must include `(gameNamespace, characterName-or-stable-speaker-ID, dialogueNormalized, sceneEpoch)`; treat a character switch as distinct speech even when text matches.

### P1-7: System fallback only conditionally selects distinct gender voices
`ForegroundTtsManager.speakInternal` (~line 1985) calls `selectGenderBasedTtsVoice()` only if `state.supportsDistinctGenderVoices`; otherwise it selects `defaultEngineVoice`, with pitch/rate changes. It may sound the same for all characters on a given installed Android TTS engine.

**Fix:** report `SYSTEM_FALLBACK_SINGLE_VOICE` when distinct native voice identities are not available; do not render it as a successful male/female voice test.

### P1-8: Misleading auditory diagnostic measurements
In `ForegroundTtsManager.synthesizeCharacterPreview` (~lines 2650 and 2700), the system fallback audit stores `outputPcmRms = 0.05f` although no PCM data from that fallback has been measured. A success callback/preview log can mislead users about non-silent waveform verification.

**Fix:** use nullable/unknown RMS for system fallback; only measured PCM may be used for acoustic validation.

### P2-9: OCR alias typo
`CharacterVoiceRouter.kt` initializes `ocrAliasCorrections["arjun"] = "aarav"`. Unless explicitly requested by user for their game, this reassigns two distinct character names and can cause wrong voice attribution.

**Fix:** remove that hardcoded alias and apply only per-game confirmed corrections.

## What is *not* verified
- No real Android device was attached to this audit: neural runtime initialization, audio quality, actual game subtitle-to-audio delay, CPU use, and voice identity are **NOT VERIFIED**.
- This is source/APK inspection, not a new Gradle build. Earlier AI Studio test counts do not establish a real-game voice-routing success.
- Chatterbox inference on the user's PC may be too slow for instant dubbing: benchmark before enabling by default.

## Acceptance test to require
1. Set the app to a visible, explicitly selected backend and log the actual backend per utterance.
2. In the real game, capture `Anon` line and `Jenny` line; show recognized names, SIDs, reference IDs and actual engine.
3. Use the *same exact sentence* through Voice Manager preview and the real game live capture route. Capture/extract four WAVs and compare the IDs and actual voices.
4. If the model/server is unavailable, report `FALLBACK`/`NOT TESTED`, not success.
5. Include a case of different characters speaking the same English line; confirm both are spoken.
6. Record first audible speech latency measured from real phone and listen to voices for naturalness; no synthetic waveform test substitutes for listening.

## Top repair order
1. Unify preview/live backend selection, and remove fake PASS from diagnostic WAV.
2. Fix speaker OCR recognition and prevent default-to-Anon on uncertain subtitles.
3. Make duplicate detection speaker-aware.
4. Benchmark two genuine reference voices end-to-end on the real game with logged identity.
5. Expand distinct voice identity library only once those two pass.
