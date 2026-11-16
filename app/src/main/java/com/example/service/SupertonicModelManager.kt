package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.data.TranslatorStateRepository
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Lifecycle, download progress, SHA-256 verification, and native readiness states for the
 * local sherpa-onnx Supertonic 3 Hindi model bundle (`filesDir/sherpa_models/supertonic_3_hi/`).
 *
 * CRITICAL CONTRACT: [Ready] is NEVER emitted until:
 * 1. All 7 required model files are present on disk.
 * 2. All 7 files pass cryptographic SHA-256 verification against [SupertonicModelManager.EXPECTED_SHA256_HASHES].
 * 3. Native `OfflineTts` initialization AND real Hindi native inference succeed for both Male (SID #5) and Female (SID #1).
 */
sealed class SupertonicModelState {
    object NotDownloaded : SupertonicModelState()
    data class Downloading(
        val progressFraction: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val currentFileName: String = "",
        val currentFileIndex: Int = 0,
        val totalFilesCount: Int = 7
    ) : SupertonicModelState()
    data class VerifyingSha256(
        val currentFileName: String = "",
        val verifiedFilesCount: Int = 0,
        val totalFilesCount: Int = 7
    ) : SupertonicModelState()
    data class InitializingNative(
        val stepDescription: String = "Initializing Sherpa-ONNX & running Hindi native inference check (Anon SID #5 & Jenny SID #1)..."
    ) : SupertonicModelState()
    object Extracting : SupertonicModelState()
    data class Ready(
        val modelDir: File,
        val totalSizeBytes: Long,
        val nativeInferenceVerified: Boolean = true,
        val sid5Rms: Float = 0f,
        val sid1Rms: Float = 0f,
        val readinessSummary: String = ""
    ) : SupertonicModelState()
    data class Error(val errorMessage: String) : SupertonicModelState()
}

/**
 * Information descriptor for Supertonic 3 model assets.
 */
data class SupertonicModelInfo(
    val name: String = "Supertonic 3 Multilingual (Hindi)",
    val version: String = "2026-05-11-int8",
    val license: String = "OpenRAIL-M (Supertone Open Model License)",
    val codeLicense: String = "Apache 2.0 (sherpa-onnx runtime)",
    val attributionNotice: String = "Model weights (C) Supertone Inc. under OpenRAIL-M license. Runtime inference engine (C) sherpa-onnx / Next-gen Kaldi under Apache 2.0. Lifetime free for on-device game dubbing and personal use in accordance with OpenRAIL use restrictions.",
    val baseSpeakerCount: Int = 10,
    val sampleRateHz: Int = 44100,
    val approximateSizeMb: Int = 145,
    val primaryDownloadUrl: String = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
    val fallbackMirrorUrl: String = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/"
)

/**
 * Manages:
 * 1. Verification of on-device Supertonic 3 model assets in `filesDir/sherpa_models/supertonic_3_hi/`.
 * 2. Reliable multi-file HTTP/HTTPS download (handling HTTP 301/302/303/307/308 & relative redirects) with per-file progress and exact error reporting.
 * 3. Strict SHA-256 integrity validation of every file.
 * 4. Native inference readiness verification (probing Male SID #5 and Female SID #1) BEFORE marking the model [SupertonicModelState.Ready].
 * 5. Model deletion/uninstall for storage cleanup.
 */
object SupertonicModelManager {
    private const val TAG = "SupertonicModelMgr"
    private const val RELATIVE_MODEL_DIR = "sherpa_models/supertonic_3_hi"
    const val ESTIMATED_TOTAL_MODEL_BYTES = 145_295_768L

    val modelInfo = SupertonicModelInfo()

    @Volatile
    var overrideModelDirectory: File? = null
        set(value) {
            field = value
            clearNativeVerificationState()
        }

    @Volatile
    var overrideBaseDownloadUrl: String? = null

    @Volatile
    var overrideBackendFactoryForTest: ((Context) -> SherpaSupertonicBackend)? = null

    private val verifiedFileHashCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var nativeVerifiedModelDirKey: String? = null

    @Volatile
    private var lastVerifiedSid5Rms: Float = 0f

    @Volatile
    private var lastVerifiedSid1Rms: Float = 0f

    @Volatile
    private var lastVerifiedReadinessSummary: String = ""

    @Volatile
    private var lastNativeVerificationError: String? = null

    val REQUIRED_FILES = listOf(
        "duration_predictor.int8.onnx",
        "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin"
    )

    /**
     * Approximate individual file sizes in bytes for accurate progress reporting before Content-Length is known.
     */
    val EXPECTED_FILE_SIZES_BYTES = mapOf(
        "duration_predictor.int8.onnx" to 521_270L,
        "text_encoder.int8.onnx" to 9_114_150L,
        "vector_estimator.int8.onnx" to 53_513_999L,
        "vocoder.int8.onnx" to 81_747_116L,
        "tts.json" to 12_883L,
        "unicode_indexer.bin" to 262_152L,
        "voice.bin" to 124_198L
    )

    /**
     * Official trusted upstream SHA-256 hashes from csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.
     * Validates downloaded and preexisting files against immutable expected hashes to prevent corruption or tampering.
     */
    val EXPECTED_SHA256_HASHES = mapOf(
        "duration_predictor.int8.onnx" to "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
        "text_encoder.int8.onnx" to "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
        "vector_estimator.int8.onnx" to "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd",
        "vocoder.int8.onnx" to "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152",
        "tts.json" to "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
        "unicode_indexer.bin" to "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30",
        "voice.bin" to "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"
    )

    data class ModelIntegrityReport(
        val allValid: Boolean,
        val verifiedFilesCount: Int,
        val totalFilesCount: Int,
        val mismatchedFiles: List<String>,
        val totalVerifiedBytes: Long = 0L
    )

    private val _modelState = MutableStateFlow<SupertonicModelState>(SupertonicModelState.NotDownloaded)
    val modelState: StateFlow<SupertonicModelState> = _modelState.asStateFlow()

    fun getModelDirectory(context: Context): File {
        return overrideModelDirectory ?: File(context.filesDir, RELATIVE_MODEL_DIR)
    }

    fun clearNativeVerificationState() {
        nativeVerifiedModelDirKey = null
        lastVerifiedSid5Rms = 0f
        lastVerifiedSid1Rms = 0f
        lastVerifiedReadinessSummary = ""
        lastNativeVerificationError = null
    }

    private fun buildModelDirectorySignatureKey(dir: File): String {
        if (!dir.exists() || !dir.isDirectory) return ""
        val sb = StringBuilder(dir.absolutePath)
        for (fileName in REQUIRED_FILES) {
            val f = File(dir, fileName)
            sb.append('|').append(fileName).append(':').append(f.length())
        }
        return sb.toString()
    }

    fun isNativeInferenceVerifiedForCurrentFiles(context: Context): Boolean {
        val dir = getModelDirectory(context)
        val currentKey = buildModelDirectorySignatureKey(dir)
        return currentKey.isNotEmpty() && currentKey == nativeVerifiedModelDirKey
    }

    /**
     * Called by [SherpaSupertonicBackend.initialize] only AFTER native `OfflineTts` initialization
     * AND real Hindi native inference for Male (SID #5) and Female (SID #1) have succeeded with non-silent PCM.
     */
    fun markNativeInferenceVerified(
        context: Context,
        sampleRate: Int,
        numSpeakers: Int,
        sid5Rms: Float,
        sid1Rms: Float,
        initLatencyMs: Long
    ) {
        val dir = getModelDirectory(context)
        var totalSize = 0L
        for (fileName in REQUIRED_FILES) {
            val f = File(dir, fileName)
            if (f.exists()) totalSize += f.length()
        }
        nativeVerifiedModelDirKey = buildModelDirectorySignatureKey(dir)
        lastVerifiedSid5Rms = sid5Rms
        lastVerifiedSid1Rms = sid1Rms
        lastNativeVerificationError = null
        val summary = "Native inference READY (${numSpeakers} speakers @ ${sampleRate}Hz • Init ${initLatencyMs}ms • Anon SID #5 RMS=${String.format(Locale.US, "%.4f", sid5Rms)}, Jenny SID #1 RMS=${String.format(Locale.US, "%.4f", sid1Rms)})"
        lastVerifiedReadinessSummary = summary
        _modelState.value = SupertonicModelState.Ready(
            modelDir = dir,
            totalSizeBytes = totalSize,
            nativeInferenceVerified = true,
            sid5Rms = sid5Rms,
            sid1Rms = sid1Rms,
            readinessSummary = summary
        )
        TranslatorStateRepository.clearVoiceEngineWarning()
    }

    /**
     * Called when native `OfflineTts` initialization or native Hindi inference fails.
     * Ensures the model is NEVER marked [SupertonicModelState.Ready] when native inference fails.
     */
    fun markNativeInferenceFailed(context: Context, exactError: String) {
        nativeVerifiedModelDirKey = null
        lastNativeVerificationError = exactError
        val errState = SupertonicModelState.Error("Native inference readiness check failed: $exactError")
        _modelState.value = errState
    }

    private fun getCachedOrComputeSha256(file: File): String {
        if (!file.exists()) return ""
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return verifiedFileHashCache.getOrPut(cacheKey) {
            computeSha256(file)
        }
    }

    /**
     * Verifies the cryptographic SHA-256 integrity of all required model assets on disk against trusted hashes.
     */
    fun verifyModelIntegrity(context: Context): ModelIntegrityReport {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) {
            return ModelIntegrityReport(false, 0, REQUIRED_FILES.size, REQUIRED_FILES, 0L)
        }

        val mismatched = mutableListOf<String>()
        var verifiedCount = 0
        var totalVerifiedBytes = 0L

        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            val expectedHash = EXPECTED_SHA256_HASHES[fileName]
            if (!file.exists() || file.length() < 100) {
                mismatched.add("$fileName (missing or empty)")
                continue
            }
            if (expectedHash != null) {
                val actualHash = getCachedOrComputeSha256(file)
                if (actualHash.equals(expectedHash, ignoreCase = true)) {
                    verifiedCount++
                    totalVerifiedBytes += file.length()
                } else {
                    mismatched.add("$fileName (SHA-256 mismatch: expected ${expectedHash.take(12)}…, got ${actualHash.take(12)}…)")
                }
            } else {
                verifiedCount++
                totalVerifiedBytes += file.length()
            }
        }

        return ModelIntegrityReport(
            allValid = mismatched.isEmpty(),
            verifiedFilesCount = verifiedCount,
            totalFilesCount = REQUIRED_FILES.size,
            mismatchedFiles = mismatched,
            totalVerifiedBytes = totalVerifiedBytes
        )
    }

    /**
     * Returns `true` iff all 7 required model files exist on disk and pass SHA-256 verification.
     * Note: This checks on-disk file integrity only; use [isModelReady] to check full native inference readiness.
     */
    fun areModelFilesVerifiedOnDisk(context: Context): Boolean {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) return false
        for (fileName in REQUIRED_FILES) {
            val f = File(dir, fileName)
            if (!f.exists() || f.length() < 100) return false
        }
        return verifyModelIntegrity(context).allValid
    }

    /**
     * Verifies all 7 model files on disk by SHA-256 AND runs native Hindi inference initialization
     * and synthesis checks (Male SID #5 + Female SID #1) before marking [SupertonicModelState.Ready].
     */
    fun verifyAndInitializeReadiness(
        context: Context,
        sherpaBackend: SherpaSupertonicBackend? = null
    ): Boolean {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) {
            clearNativeVerificationState()
            _modelState.value = SupertonicModelState.NotDownloaded
            return false
        }

        var missingCount = 0
        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            if (!file.exists() || file.length() < 100) {
                missingCount++
            }
        }
        if (missingCount == REQUIRED_FILES.size) {
            clearNativeVerificationState()
            _modelState.value = SupertonicModelState.NotDownloaded
            return false
        }

        val integrity = verifyModelIntegrity(context)
        if (!integrity.allValid) {
            clearNativeVerificationState()
            val errorState = SupertonicModelState.Error(
                "Model integrity verification failed (${integrity.verifiedFilesCount}/${integrity.totalFilesCount} valid): ${integrity.mismatchedFiles.joinToString(", ")}"
            )
            _modelState.value = errorState
            return false
        }

        if (isNativeInferenceVerifiedForCurrentFiles(context)) {
            val readyState = SupertonicModelState.Ready(
                modelDir = dir,
                totalSizeBytes = integrity.totalVerifiedBytes,
                nativeInferenceVerified = true,
                sid5Rms = lastVerifiedSid5Rms,
                sid1Rms = lastVerifiedSid1Rms,
                readinessSummary = lastVerifiedReadinessSummary
            )
            _modelState.value = readyState
            return true
        }

        _modelState.value = SupertonicModelState.InitializingNative(
            "7/7 files SHA-256 verified (${String.format(Locale.US, "%.1f", integrity.totalVerifiedBytes / 1_000_000.0)} MB). Initializing Sherpa-ONNX & testing native Hindi inference (Anon SID #5 & Jenny SID #1)..."
        )

        val backendToUse = sherpaBackend
            ?: overrideBackendFactoryForTest?.invoke(context)
            ?: ForegroundTtsManager.peekSherpaBackend()
            ?: SherpaSupertonicBackend(context)

        val isTransientBackend = sherpaBackend == null &&
            overrideBackendFactoryForTest == null &&
            ForegroundTtsManager.peekSherpaBackend() == null

        return try {
            val initialized = backendToUse.initialize()
            if (initialized && isNativeInferenceVerifiedForCurrentFiles(context)) {
                true
            } else {
                val err = backendToUse.initializationError
                    ?: lastNativeVerificationError
                    ?: "Native Sherpa-ONNX inference check did not complete (${OfflineTts.jniStatusSummary})"
                markNativeInferenceFailed(context, err)
                false
            }
        } finally {
            if (isTransientBackend) {
                try {
                    backendToUse.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Checks if all required Supertonic 3 model files are present, pass SHA-256 integrity verification,
     * AND pass native inference readiness verification.
     * Never declares [SupertonicModelState.Ready] until native inference succeeds.
     */
    fun checkStatus(
        context: Context,
        sherpaBackend: SherpaSupertonicBackend? = null
    ): SupertonicModelState {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) {
            clearNativeVerificationState()
            val state = SupertonicModelState.NotDownloaded
            _modelState.value = state
            return state
        }

        var presentFiles = 0
        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            if (file.exists() && file.length() >= 100) {
                presentFiles++
            }
        }
        if (presentFiles == 0) {
            clearNativeVerificationState()
            val state = SupertonicModelState.NotDownloaded
            _modelState.value = state
            return state
        }
        if (presentFiles < REQUIRED_FILES.size) {
            clearNativeVerificationState()
            val missing = REQUIRED_FILES.filter { !File(dir, it).exists() || File(dir, it).length() < 100 }
            val state = if (_modelState.value is SupertonicModelState.Downloading) {
                _modelState.value
            } else if (presentFiles == 0) {
                SupertonicModelState.NotDownloaded
            } else {
                SupertonicModelState.Error(
                    "Incomplete model bundle ($presentFiles/${REQUIRED_FILES.size} files present; missing: ${missing.joinToString(", ")})"
                )
            }
            _modelState.value = state
            return state
        }

        val integrity = verifyModelIntegrity(context)
        if (!integrity.allValid) {
            clearNativeVerificationState()
            val errorState = SupertonicModelState.Error(
                "Model integrity verification failed: ${integrity.mismatchedFiles.joinToString(", ")}"
            )
            _modelState.value = errorState
            return errorState
        }

        if (isNativeInferenceVerifiedForCurrentFiles(context)) {
            val readyState = SupertonicModelState.Ready(
                modelDir = dir,
                totalSizeBytes = integrity.totalVerifiedBytes,
                nativeInferenceVerified = true,
                sid5Rms = lastVerifiedSid5Rms,
                sid1Rms = lastVerifiedSid1Rms,
                readinessSummary = lastVerifiedReadinessSummary
            )
            _modelState.value = readyState
            return readyState
        }

        verifyAndInitializeReadiness(context, sherpaBackend)
        return _modelState.value
    }

    /**
     * Returns `true` ONLY when all 7 Supertonic 3 files are verified by SHA-256 on disk
     * AND native Hindi inference has succeeded.
     */
    fun isModelReady(context: Context): Boolean {
        val current = _modelState.value
        if (current is SupertonicModelState.Ready && current.nativeInferenceVerified && isNativeInferenceVerifiedForCurrentFiles(context)) {
            return true
        }
        return checkStatus(context) is SupertonicModelState.Ready
    }

    /**
     * Deletes on-device model files to reclaim disk space.
     */
    fun uninstallModel(context: Context): Boolean {
        val dir = getModelDirectory(context)
        verifiedFileHashCache.clear()
        clearNativeVerificationState()
        val success = if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
        _modelState.value = SupertonicModelState.NotDownloaded
        return success
    }

    /**
     * Checks if active network connection is unmetered (Wi-Fi/Ethernet).
     */
    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Opens an HTTP/HTTPS connection and manually follows HTTP 301, 302, 303, 307, and 308 redirects
     * (including relative `Location` headers like HuggingFace's `307 /api/resolve-cache/...` and
     * cross-domain `302 https://us.aws.cdn.hf.co/...` redirects).
     */
    fun openConnectionFollowingRedirects(
        initialUrl: URL,
        maxRedirects: Int = 8,
        connectTimeoutMs: Int = 20000,
        readTimeoutMs: Int = 60000
    ): Pair<HttpURLConnection, URL> {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= maxRedirects) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "GameVox-Android/1.0 (Sherpa-ONNX Supertonic-3)")
                setRequestProperty("Accept", "*/*")
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("HTTP $code redirect from $currentUrl missing Location header")
                }
                // Resolve relative or absolute Location against currentUrl
                currentUrl = URL(currentUrl, location.trim())
                redirects++
                continue
            }

            return conn to currentUrl
        }

        throw IllegalStateException("Too many HTTP redirects (> $maxRedirects) starting from $initialUrl")
    }

    /**
     * Downloads all 7 Supertonic 3 Hindi model files to `filesDir/sherpa_models/supertonic_3_hi/`,
     * verifies each file's SHA-256 hash against [EXPECTED_SHA256_HASHES], initializes [SherpaSupertonicBackend],
     * and runs native Hindi inference verification for Male (`Anon`, SID #5) and Female (`Jenny`, SID #1).
     *
     * Does NOT mark the model [SupertonicModelState.Ready] or return `true` unless native inference succeeds.
     */
    suspend fun downloadModel(
        context: Context,
        requireWifi: Boolean = false,
        sherpaBackend: SherpaSupertonicBackend? = null,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (requireWifi && !isConnectedToWifi(context)) {
            _modelState.value = SupertonicModelState.Error("Wi-Fi network required to download Supertonic 3 (~145 MB)")
            return@withContext false
        }

        val targetDir = getModelDirectory(context).apply { mkdirs() }
        clearNativeVerificationState()
        val totalEstimatedBytes = ESTIMATED_TOTAL_MODEL_BYTES
        _modelState.value = SupertonicModelState.Downloading(
            progressFraction = 0.0f,
            bytesDownloaded = 0L,
            totalBytes = totalEstimatedBytes,
            currentFileName = REQUIRED_FILES.first(),
            currentFileIndex = 1,
            totalFilesCount = REQUIRED_FILES.size
        )

        try {
            val rawBaseUrl = (overrideBaseDownloadUrl ?: modelInfo.fallbackMirrorUrl).trim()
            val baseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
            var downloadedBytesTotal = 0L

            for ((index, fileName) in REQUIRED_FILES.withIndex()) {
                val fileNumber = index + 1
                val destFile = File(targetDir, fileName)
                val expectedHash = EXPECTED_SHA256_HASHES[fileName]

                // 1. Check if preexisting file on disk already matches expected SHA-256
                if (destFile.exists() && destFile.length() >= 100) {
                    _modelState.value = SupertonicModelState.VerifyingSha256(
                        currentFileName = fileName,
                        verifiedFilesCount = index,
                        totalFilesCount = REQUIRED_FILES.size
                    )
                    val existingHash = computeSha256(destFile)
                    if (expectedHash == null || existingHash.equals(expectedHash, ignoreCase = true)) {
                        downloadedBytesTotal += destFile.length()
                        val progress = (downloadedBytesTotal.toFloat() / totalEstimatedBytes).coerceIn(0.0f, 0.92f)
                        _modelState.value = SupertonicModelState.Downloading(
                            progressFraction = progress,
                            bytesDownloaded = downloadedBytesTotal,
                            totalBytes = totalEstimatedBytes,
                            currentFileName = fileName,
                            currentFileIndex = fileNumber,
                            totalFilesCount = REQUIRED_FILES.size
                        )
                        onProgress(progress)
                        continue
                    } else {
                        Log.w(TAG, "Deleting corrupted preexisting file $fileName (SHA-256 mismatch)")
                        destFile.delete()
                    }
                }

                // 2. Download file following HTTP 301/302/303/307/308 redirects (including relative Location headers)
                val fileUrl = URL("$baseUrl$fileName")
                _modelState.value = SupertonicModelState.Downloading(
                    progressFraction = (downloadedBytesTotal.toFloat() / totalEstimatedBytes).coerceIn(0.0f, 0.92f),
                    bytesDownloaded = downloadedBytesTotal,
                    totalBytes = totalEstimatedBytes,
                    currentFileName = fileName,
                    currentFileIndex = fileNumber,
                    totalFilesCount = REQUIRED_FILES.size
                )

                val (connection, resolvedUrl) = try {
                    openConnectionFollowingRedirects(fileUrl)
                } catch (connErr: Exception) {
                    val exactErr = "Network error downloading $fileName ($fileNumber/${REQUIRED_FILES.size}): ${connErr.localizedMessage ?: connErr.javaClass.simpleName}"
                    Log.e(TAG, exactErr, connErr)
                    _modelState.value = SupertonicModelState.Error(exactErr)
                    return@withContext false
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val responseMsg = try { connection.responseMessage.orEmpty() } catch (_: Exception) { "" }
                    connection.disconnect()
                    val exactErr = "Download failed for $fileName ($fileNumber/${REQUIRED_FILES.size}): HTTP $responseCode $responseMsg from ${resolvedUrl.host}"
                    Log.e(TAG, exactErr)
                    _modelState.value = SupertonicModelState.Error(exactErr)
                    return@withContext false
                }

                val tempFile = File(targetDir, "$fileName.tmp")
                if (tempFile.exists()) tempFile.delete()

                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            var lastEmitBytes = downloadedBytesTotal
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytesTotal += read
                                if (downloadedBytesTotal - lastEmitBytes >= 65536L || read < buffer.size) {
                                    lastEmitBytes = downloadedBytesTotal
                                    val progress = (downloadedBytesTotal.toFloat() / totalEstimatedBytes).coerceIn(0.0f, 0.92f)
                                    _modelState.value = SupertonicModelState.Downloading(
                                        progressFraction = progress,
                                        bytesDownloaded = downloadedBytesTotal,
                                        totalBytes = totalEstimatedBytes.coerceAtLeast(downloadedBytesTotal),
                                        currentFileName = fileName,
                                        currentFileIndex = fileNumber,
                                        totalFilesCount = REQUIRED_FILES.size
                                    )
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                if (!tempFile.exists() || tempFile.length() < 100) {
                    val actualLen = if (tempFile.exists()) tempFile.length() else 0L
                    tempFile.delete()
                    val exactErr = "Download failed for $fileName ($fileNumber/${REQUIRED_FILES.size}): received incomplete file ($actualLen bytes)"
                    _modelState.value = SupertonicModelState.Error(exactErr)
                    return@withContext false
                }

                // 3. Per-file cryptographic SHA-256 verification before atomic rename
                _modelState.value = SupertonicModelState.VerifyingSha256(
                    currentFileName = fileName,
                    verifiedFilesCount = index,
                    totalFilesCount = REQUIRED_FILES.size
                )
                if (expectedHash != null) {
                    val computed = computeSha256(tempFile)
                    if (!computed.equals(expectedHash, ignoreCase = true)) {
                        tempFile.delete()
                        val exactErr = "SHA-256 verification failed for $fileName ($fileNumber/${REQUIRED_FILES.size}): expected ${expectedHash.take(12)}…, got ${computed.take(12)}…"
                        Log.e(TAG, exactErr)
                        _modelState.value = SupertonicModelState.Error(exactErr)
                        return@withContext false
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            }

            verifiedFileHashCache.clear()
            onProgress(0.95f)

            // 4. Verify all 7 files on disk + initialize Sherpa-ONNX + run real Hindi native inference readiness check
            val ready = verifyAndInitializeReadiness(context, sherpaBackend)
            if (ready && _modelState.value is SupertonicModelState.Ready) {
                onProgress(1.0f)
                true
            } else {
                val errState = _modelState.value
                if (errState !is SupertonicModelState.Error) {
                    _modelState.value = SupertonicModelState.Error(
                        "Model files downloaded & verified, but native inference readiness check failed (${OfflineTts.jniStatusSummary})"
                    )
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            _modelState.value = SupertonicModelState.Error(
                "Download failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            false
        }
    }

    /**
     * Computes the SHA-256 hash of a file for integrity verification.
     */
    fun computeSha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }
}
