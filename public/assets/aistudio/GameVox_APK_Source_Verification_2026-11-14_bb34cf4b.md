# GameVox — APK / Source Verification Report
**Date:** 2026-11-14  
**Artifacts inspected:** Uploaded `/mnt/data/app-debug.apk`, `/mnt/data/GameVox-main (1).zip`  
**Scope:** static inspection of ZIP/APK entries and supplied Kotlin code; no device execution or Android SDK build performed.

## Artifact identity
| File | Actual size | SHA-256 |
|---|---:|---|
| app-debug.apk | 269,197,604 bytes (256.73 MiB) | `bc04f50f72866573f757f857ec536b835cadd08da8295f69665059cfe5543efc` |
| GameVox-main (1).zip | 1,868,484 bytes (1.78 MiB) | `f08a4dd23592a8ebd53b9b31d3a096f6cedc07a5e7746355857fb2c707649af2` |

The APK SHA-256 differs from the previously reported `952a5cc4cdd7d12a71e51f5ec4a6547382baef79a8aefd07803688d955d37a43`. The uploaded source ZIP is also different in size/hash from AI Studio's reported 129 MB archive. Thus reported hashes do not identify the uploaded binaries; do not assume they are identical builds.

## P0 — Blockers
1. **Sherpa JNI not in APK.** APK contains MediaPipe/ML Kit native libraries but no `lib/<abi>/libsherpa-onnx-jni.so` in any ABI. The source does not declare a Sherpa native dependency or bundle `jniLibs`. `Tts.kt:120–132` attempts `System.loadLibrary("sherpa-onnx-jni")`. In the uploaded APK's native-library set this load cannot succeed normally. A separately installed Android TTS engine doesn't add JNI libraries into GameVox's process.
2. **Production TTS wrapper generates silence if JNI is absent.** `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt:180–195` returns `GeneratedAudio(FloatArray(sampleCount), ...)` instead of throwing when `ptr == 0` / JNI missing. `sampleRate()` and `numSpeakers()` fabricate 24000 and 10 in the same situation (`Tts.kt:151–169`), and `SherpaSupertonicBackend.kt:147–151` marks initialization successful even though native synthesis was not initialized. This can produce a false `SHERPA_DIRECT` status and silent playback. Remove production silent-stub fallback; inject explicit fakes only in tests.
3. **Custom JNI methods do not match the upstream `Tts.kt` contract.** The project has its own `private external fun generateText(ptr, text, sid, speed): FloatArray` (`Tts.kt:237`) instead of upstream `generateWithConfigImpl(ptr, text, config, callback): GeneratedAudio`. `generateWithConfig()` ignores `numSteps` and `extra["lang"]` when forwarding work (`Tts.kt:180–189`). Adopt the exact source/bindings and matching `.so` files from one pinned sherpa-onnx release; do not use ad hoc partial JNI wrappers.
4. **Streaming not real.** `Tts.kt:197–215` synthesizes the full audio first, then loops over 1,024-sample chunks. This cannot reduce neural first-chunk generation latency. Use upstream native `generateWithConfigAndCallback()` implementation; time callback receipt and actual playback separately.

## P1 — High priority defects
5. **Audio can be truncated.** `SherpaSupertonicBackend.kt:244–299` buffers chunks, calls `onCompleted()`, then stops/releases `AudioTrack` in `finally` without waiting for buffered frames to play. Implement correct playback-drain/finish semantics or use a blocking completion signal tied to AudioTrack playback head/marker (while supporting cancellation).
6. **Fallback may speak twice.** `ForegroundTtsManager.kt:1291–1331` calls `speakOnAndroidTts()` inside the `onError` callback and again when `synthesizeAndPlay()` returns `false`. Ensure only one fallback per utterance, with an atomic state/epoch guard.
7. **Model-ready does not verify hashes.** `SupertonicModelManager.kt:121–149` uses file existence/length to mark Ready, whereas `verifyModelIntegrity()` (`:94–117`) hashes. Download loop `:196–202` skips preexisting files without integrity verification. Require trusted version-pinned hashes before marking Ready; validate any existing files and downloaded files before use.
8. **Tests validate stubs, not synthesis.** `SherpaSupertonicBackendTest.kt:testOfflineTtsApiContract` expects nonempty samples from a no-JNI `OfflineTts`, and `testStepBenchmarkExecution` uses a stub factory. `testExpectedSha256HashesCoverage` checks hash string lengths, not equality with actual upstream model bytes. Passing JVM tests does not demonstrate native inference, voice quality, model integrity or low latency.

## Positive findings
- APK is a real Android package with ML Kit OCR, translation and MediaPipe native dependencies.
- Source contains the requested per-character profile persistence, Anon SID 5 / Jenny SID 1 mapping, and diagnostic UI.
- Source contains an offline model downloader and explicit paid-voice controls, but its runtime Supertonic integration is blocked as above.

## Required acceptance tests before claiming working dubbing
1. Build with official pinned Sherpa Android libraries; inspect APK for `lib/arm64-v8a/libsherpa-onnx-jni.so` and required transitive `.so` files.
2. On physical arm64 Android, confirm `System.loadLibrary` succeeds, the model verifies, native TTS pointer initializes, and `numSpeakers() == 10` comes from JNI (not a constant).
3. Generate one Hindi utterance with SID 5 and one with SID 1; save real PCM/WAV test clips. Check that waveform RMS is not near zero and an actual human can hear them.
4. Run 5/8-step benchmarks, record real-time factor and first playable audio latency. Assert language map reaches native code.
5. Confirm drained/finished audio with no truncation, exactly one fallback if disabled, and per-character voice switching for 3 consecutive lines.
6. Rebuild/zip, record artifact SHA-256 hashes and compare them with uploaded outputs in CI. Include device-only checks as NOT VERIFIED until performed.

**Verdict:** Not ready for real direct-neural dubbing based on the inspected APK. This is a static review; no actual voice was measured or listened to.
