// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    var dictDir: String = "", // unused
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    var dictDir: String = "", // unused
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "", // unused
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsZipVoiceModelConfig(
    var tokens: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var vocoder: String = "",
    var dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    var lexicon: String = "",
    var featScale: Float = 0.1f,
    var tShift: Float = 0.5f,
    var targetRms: Float = 0.1f,
    var guidanceScale: Float = 1.0f,
)

data class OfflineTtsKittenModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    var lengthScale: Float = 1.0f,
    var lexicon: String = "",
)

/**
 * Configuration for Pocket TTS models.
 *
 * See https://k2-fsa.github.io/sherpa/onnx/tts/pocket/index.html for details.
 */
data class OfflineTtsPocketModelConfig(
    var lmFlow: String = "",
    var lmMain: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var textConditioner: String = "",
    var vocabJson: String = "",
    var tokenScoresJson: String = "",
    var voiceEmbeddingCacheCapacity: Int = 50,
)

data class OfflineTtsSupertonicModelConfig(
    var durationPredictor: String = "",
    var textEncoder: String = "",
    var vectorEstimator: String = "",
    var vocoder: String = "",
    var ttsJson: String = "",
    var unicodeIndexer: String = "",
    var voiceStyle: String = "",
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var zipvoice: OfflineTtsZipVoiceModelConfig = OfflineTtsZipVoiceModelConfig(),
    var kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    var pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),
    var supertonic: OfflineTtsSupertonicModelConfig = OfflineTtsSupertonicModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

data class GenerationConfig(
    var silenceScale: Float = 0.2f,
    var speed: Float = 1.0f,
    var sid: Int = 0,
    var referenceAudio: FloatArray? = null,
    var referenceSampleRate: Int = 0,
    var referenceText: String? = null,
    var numSteps: Int = 5,
    var extra: Map<String, String>? = mapOf("lang" to "hi"),
    // Pre-phonemized input as Unicode codepoints. One inner array per
    // sentence. When set, it is used in place of the text argument.
    // Mutually exclusive with tokens.
    var phonemeCodepoints: Array<IntArray>? = null,
    // Pre-tokenized input, e.g. Chinese pinyin like "zhong1". One inner array
    // per sentence. Mutually exclusive with phonemeCodepoints.
    var tokens: Array<Array<String>>? = null
)

/**
 * Bridges Kotlin 2.x invokedynamic lambdas `(FloatArray) -> Int` to the exact
 * JVM method descriptor `invoke([F)Ljava/lang/Integer;` expected by sherpa-onnx's
 * C++ JNI `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`.
 */
internal class NativeAudioCallbackBridge(
    private val delegate: (FloatArray) -> Int
) : Function1<FloatArray, Int?> {
    override fun invoke(samples: FloatArray): Int? = Integer.valueOf(delegate(samples))
}

/**
 * Official Sherpa-ONNX v2.0.1 Kotlin API for OfflineTts.
 * Zero silent fallback stubs: requires native JNI library and valid native model pointer (ptr != 0L).
 */
class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    val nativeHandle: Long
        get() = ptr

    init {
        ensureNativeLibraryLoaded()
        check(isNativeLibraryLoaded) {
            "Sherpa-ONNX native JNI library (libsherpa-onnx-jni.so) is not loaded: ${jniLoadError ?: "unknown error"}"
        }
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid OfflineTtsConfig: failed to create native OfflineTts"
        }
    }

    fun sampleRate(): Int {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return getSampleRate(ptr)
    }

    fun numSpeakers(): Int {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return getNumSpeakers(ptr)
    }

    // Language/voice of the model, e.g., "en-us". Empty if not available.
    fun lang(): String {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return getLang(ptr)
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return generateImpl(ptr, text = text, sid = sid, speed = speed)
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return generateWithCallbackImpl(
            ptr,
            text = text,
            sid = sid,
            speed = speed,
            callback = NativeAudioCallbackBridge(callback)
        )
    }

    fun generateWithConfig(
        text: String,
        config: GenerationConfig
    ): GeneratedAudio {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return generateWithConfigImpl(ptr, text, config, null)
    }

    fun generateWithConfigAndCallback(
        text: String,
        config: GenerationConfig,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio {
        check(ptr != 0L) { "OfflineTts native instance has been released (ptr == 0)" }
        return generateWithConfigImpl(ptr, text, config, NativeAudioCallbackBridge(callback))
    }

    fun allocate(assetManager: AssetManager? = null) {
        if (ptr == 0L) {
            ensureNativeLibraryLoaded()
            check(isNativeLibraryLoaded) {
                "Sherpa-ONNX native JNI library (libsherpa-onnx-jni.so) is not loaded: ${jniLoadError ?: "unknown error"}"
            }
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
            require(ptr != 0L) {
                "Invalid OfflineTtsConfig: failed to create native OfflineTts"
            }
        }
    }

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = free()

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int
    private external fun getLang(ptr: Long): String

    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int?
    ): GeneratedAudio

    private external fun generateWithConfigImpl(
        ptr: Long,
        text: String,
        config: GenerationConfig,
        callback: ((samples: FloatArray) -> Int?)?
    ): GeneratedAudio

    companion object {
        @Volatile
        var isNativeLibraryLoaded: Boolean = false
            private set

        @Volatile
        var jniLoadError: String? = null
            private set

        val jniStatusSummary: String
            get() = when {
                isNativeLibraryLoaded -> "SUCCESS (libsherpa-onnx-jni.so v2.0.1 loaded)"
                jniLoadError != null -> "FAILED: $jniLoadError"
                else -> "NOT LOADED"
            }

        @Synchronized
        fun ensureNativeLibraryLoaded(): Boolean {
            if (isNativeLibraryLoaded) return true
            return try {
                try {
                    System.loadLibrary("onnxruntime")
                } catch (_: Throwable) {
                    // May be resolved automatically via DT_NEEDED if in the same linker namespace
                }
                System.loadLibrary("sherpa-onnx-jni")
                isNativeLibraryLoaded = true
                jniLoadError = null
                true
            } catch (t: Throwable) {
                isNativeLibraryLoaded = false
                jniLoadError = "${t.javaClass.simpleName}: ${t.message}"
                false
            }
        }

        init {
            ensureNativeLibraryLoaded()
        }
    }
}

fun getOfflineTtsConfig(
    modelDir: String,
    modelName: String = "", // for VITS
    acousticModelName: String = "", // for Matcha
    vocoder: String = "", // for Matcha
    voices: String = "", // for Kokoro or kitten
    lexicon: String = "",
    dataDir: String = "", // deprecated, ignored; use lexicon or phonemeCodepoints
    dictDir: String = "", // unused
    ruleFsts: String = "",
    ruleFars: String = "",
    numThreads: Int? = null,
    isKitten: Boolean = false,
    isSupertonic: Boolean = false,
    durationPredictor: String = "", // for Supertonic
    textEncoder: String = "", // for Supertonic
    vectorEstimator: String = "", // for Supertonic
    supertonicVocoder: String = "", // for Supertonic
    ttsJson: String = "", // for Supertonic
    unicodeIndexer: String = "", // for Supertonic
    voiceStyle: String = "", // for Supertonic
    isZipvoice: Boolean = false,
    zipvoiceEncoder: String = "", // for ZipVoice
    zipvoiceDecoder: String = "", // for ZipVoice
    zipvoiceVocoder: String = "", // for ZipVoice
    featScale: Float = 0.1f, // for ZipVoice
    tShift: Float = 0.5f, // for ZipVoice
    targetRms: Float = 0.1f, // for ZipVoice
    guidanceScale: Float = 1.0f, // for ZipVoice
    isPocket: Boolean = false,
    lmFlow: String = "", // for Pocket
    lmMain: String = "", // for Pocket
    pocketEncoder: String = "", // for Pocket
    pocketDecoder: String = "", // for Pocket
    textConditioner: String = "", // for Pocket
    vocabJson: String = "", // for Pocket
    tokenScoresJson: String = "", // for Pocket
    voiceEmbeddingCacheCapacity: Int = 50, // for Pocket
): OfflineTtsConfig {
    val numberOfThreads = if (numThreads != null) {
        numThreads
    } else if (voices.isNotEmpty()) {
        4
    } else {
        2
    }

    if (!isSupertonic && !isZipvoice && !isPocket && modelName.isEmpty() &&
        acousticModelName.isEmpty()
    ) {
        throw IllegalArgumentException("Please specify a TTS model")
    }

    if (modelName.isNotEmpty() && acousticModelName.isNotEmpty()) {
        throw IllegalArgumentException("Please specify either a VITS or a Matcha model, but not both")
    }

    if (acousticModelName.isNotEmpty() && vocoder.isEmpty()) {
        throw IllegalArgumentException("Please provide vocoder for Matcha TTS")
    }

    if (isZipvoice && zipvoiceEncoder.isEmpty()) {
        throw IllegalArgumentException("Please provide encoder for ZipVoice")
    }

    if (isPocket && lmFlow.isEmpty()) {
        throw IllegalArgumentException("Please provide lmFlow for Pocket TTS")
    }

    val lexiconPath = when {
        lexicon == "" -> lexicon
        "," in lexicon -> lexicon
        else -> "$modelDir/$lexicon"
    }

    val vits = if (modelName.isNotEmpty() && voices.isEmpty() && !isSupertonic) {
        OfflineTtsVitsModelConfig(
            model = "$modelDir/$modelName",
            lexicon = lexiconPath,
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
        )
    } else {
        OfflineTtsVitsModelConfig()
    }

    val matcha = if (acousticModelName.isNotEmpty()) {
        OfflineTtsMatchaModelConfig(
            acousticModel = "$modelDir/$acousticModelName",
            vocoder = vocoder,
            lexicon = lexiconPath,
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
        )
    } else {
        OfflineTtsMatchaModelConfig()
    }

    val kokoro = if (voices.isNotEmpty() && !isKitten && !isSupertonic) {
        OfflineTtsKokoroModelConfig(
            model = "$modelDir/$modelName",
            voices = "$modelDir/$voices",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
            lexicon = lexiconPath,
        )
    } else {
        OfflineTtsKokoroModelConfig()
    }

    val kitten = if (isKitten) {
        OfflineTtsKittenModelConfig(
            model = "$modelDir/$modelName",
            voices = "$modelDir/$voices",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
            lexicon = lexiconPath,
        )
    } else {
        OfflineTtsKittenModelConfig()
    }

    val supertonic = if (isSupertonic) {
        OfflineTtsSupertonicModelConfig(
            durationPredictor = "$modelDir/$durationPredictor",
            textEncoder = "$modelDir/$textEncoder",
            vectorEstimator = "$modelDir/$vectorEstimator",
            vocoder = "$modelDir/$supertonicVocoder",
            ttsJson = "$modelDir/$ttsJson",
            unicodeIndexer = "$modelDir/$unicodeIndexer",
            voiceStyle = "$modelDir/$voiceStyle",
        )
    } else {
        OfflineTtsSupertonicModelConfig()
    }

    val zipvoice = if (isZipvoice) {
        OfflineTtsZipVoiceModelConfig(
            tokens = "$modelDir/tokens.txt",
            encoder = "$modelDir/$zipvoiceEncoder",
            decoder = "$modelDir/$zipvoiceDecoder",
            vocoder = zipvoiceVocoder,
            lexicon = lexiconPath,
            featScale = featScale,
            tShift = tShift,
            targetRms = targetRms,
            guidanceScale = guidanceScale,
        )
    } else {
        OfflineTtsZipVoiceModelConfig()
    }

    val pocket = if (isPocket) {
        OfflineTtsPocketModelConfig(
            lmFlow = "$modelDir/$lmFlow",
            lmMain = "$modelDir/$lmMain",
            encoder = "$modelDir/$pocketEncoder",
            decoder = "$modelDir/$pocketDecoder",
            textConditioner = "$modelDir/$textConditioner",
            vocabJson = "$modelDir/$vocabJson",
            tokenScoresJson = "$modelDir/$tokenScoresJson",
            voiceEmbeddingCacheCapacity = voiceEmbeddingCacheCapacity,
        )
    } else {
        OfflineTtsPocketModelConfig()
    }

    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = vits,
            matcha = matcha,
            kokoro = kokoro,
            zipvoice = zipvoice,
            kitten = kitten,
            pocket = pocket,
            supertonic = supertonic,
            numThreads = numberOfThreads,
            debug = false,
            provider = "cpu",
        ),
        ruleFsts = ruleFsts,
        ruleFars = ruleFars,
    )
}
