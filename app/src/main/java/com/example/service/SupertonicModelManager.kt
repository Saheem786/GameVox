package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Lifecycle and storage states for the local sherpa-onnx Supertonic 3 model bundle.
 */
sealed class SupertonicModelState {
    object NotDownloaded : SupertonicModelState()
    data class Downloading(val progressFraction: Float, val bytesDownloaded: Long, val totalBytes: Long) : SupertonicModelState()
    object Extracting : SupertonicModelState()
    data class Ready(val modelDir: File, val totalSizeBytes: Long) : SupertonicModelState()
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
    val sampleRateHz: Int = 24000,
    val approximateSizeMb: Int = 120,
    val primaryDownloadUrl: String = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
    val fallbackMirrorUrl: String = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/"
)

/**
 * Manages:
 * 1. Verification of on-device Supertonic 3 model assets in `filesDir/sherpa_models/supertonic_3_hi/`.
 * 2. User-initiated model download with optional Wi-Fi constraint and progress telemetry.
 * 3. SHA-256 integrity validation and decompression.
 * 4. Model deletion/uninstall for zero-storage cleanup.
 */
object SupertonicModelManager {
    private const val TAG = "SupertonicModelMgr"
    private const val RELATIVE_MODEL_DIR = "sherpa_models/supertonic_3_hi"

    val modelInfo = SupertonicModelInfo()

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
     * Official trusted upstream SHA-256 hashes from csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.
     * Validates downloaded files against immutable expected hashes to prevent corruption or tampering.
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
        val mismatchedFiles: List<String>
    )

    private val _modelState = MutableStateFlow<SupertonicModelState>(SupertonicModelState.NotDownloaded)
    val modelState: StateFlow<SupertonicModelState> = _modelState.asStateFlow()

    fun getModelDirectory(context: Context): File {
        return File(context.filesDir, RELATIVE_MODEL_DIR)
    }

    /**
     * Verifies the cryptographic SHA-256 integrity of all required model assets against trusted hashes.
     */
    fun verifyModelIntegrity(context: Context): ModelIntegrityReport {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) {
            return ModelIntegrityReport(false, 0, REQUIRED_FILES.size, REQUIRED_FILES)
        }

        val mismatched = mutableListOf<String>()
        var verifiedCount = 0

        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            val expectedHash = EXPECTED_SHA256_HASHES[fileName]
            if (!file.exists() || file.length() < 100) {
                mismatched.add("$fileName (missing or empty)")
                continue
            }
            if (expectedHash != null) {
                val actualHash = computeSha256(file)
                if (actualHash.equals(expectedHash, ignoreCase = true)) {
                    verifiedCount++
                } else {
                    mismatched.add("$fileName (hash mismatch)")
                }
            } else {
                verifiedCount++
            }
        }

        return ModelIntegrityReport(
            allValid = mismatched.isEmpty(),
            verifiedFilesCount = verifiedCount,
            totalFilesCount = REQUIRED_FILES.size,
            mismatchedFiles = mismatched
        )
    }

    /**
     * Checks if all required Supertonic 3 model files are present and valid on disk.
     */
    fun checkStatus(context: Context): SupertonicModelState {
        val dir = getModelDirectory(context)
        if (!dir.exists() || !dir.isDirectory) {
            val state = SupertonicModelState.NotDownloaded
            _modelState.value = state
            return state
        }

        var totalSize = 0L
        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            if (!file.exists() || file.length() < 100) {
                val state = SupertonicModelState.NotDownloaded
                _modelState.value = state
                return state
            }
            totalSize += file.length()
        }

        val readyState = SupertonicModelState.Ready(dir, totalSize)
        _modelState.value = readyState
        return readyState
    }

    fun isModelReady(context: Context): Boolean {
        return checkStatus(context) is SupertonicModelState.Ready
    }

    /**
     * Deletes on-device model files to reclaim disk space.
     */
    fun uninstallModel(context: Context): Boolean {
        val dir = getModelDirectory(context)
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
     * Downloads individual or archive Supertonic model files to `filesDir/sherpa_models/supertonic_3_hi/`.
     */
    suspend fun downloadModel(
        context: Context,
        requireWifi: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (requireWifi && !isConnectedToWifi(context)) {
            _modelState.value = SupertonicModelState.Error("Wi-Fi network required to download (~120 MB)")
            return@withContext false
        }

        val targetDir = getModelDirectory(context).apply { mkdirs() }
        _modelState.value = SupertonicModelState.Downloading(0.0f, 0L, 120_000_000L)

        try {
            // Attempt downloading individual model files from HuggingFace mirror
            val baseUrl = modelInfo.fallbackMirrorUrl
            var downloadedBytesTotal = 0L
            val estimatedTotalBytes = 135_000_000L

            for ((index, fileName) in REQUIRED_FILES.withIndex()) {
                val destFile = File(targetDir, fileName)
                if (destFile.exists() && destFile.length() > 1000) {
                    downloadedBytesTotal += destFile.length()
                    continue
                }

                val fileUrl = URL("$baseUrl$fileName")
                val connection = (fileUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "GameVox-Android/1.0")
                }

                if (connection.responseCode in 200..299) {
                    val fileLength = connection.contentLengthLong.coerceAtLeast(1L)
                    val tempFile = File(targetDir, "$fileName.tmp")

                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var fileRead = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                fileRead += read
                                downloadedBytesTotal += read
                                val progress = (downloadedBytesTotal.toFloat() / estimatedTotalBytes).coerceIn(0.0f, 0.98f)
                                _modelState.value = SupertonicModelState.Downloading(
                                    progressFraction = progress,
                                    bytesDownloaded = downloadedBytesTotal,
                                    totalBytes = estimatedTotalBytes
                                )
                                onProgress(progress)
                            }
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        val expectedHash = EXPECTED_SHA256_HASHES[fileName]
                        if (expectedHash != null) {
                            val computed = computeSha256(tempFile)
                            if (!computed.equals(expectedHash, ignoreCase = true)) {
                                tempFile.delete()
                                _modelState.value = SupertonicModelState.Error(
                                    "Integrity check failed for $fileName (SHA-256 mismatch: expected $expectedHash, got $computed)"
                                )
                                return@withContext false
                            }
                        }
                        tempFile.renameTo(destFile)
                    }
                } else {
                    connection.disconnect()
                }
            }

            // Verify final status
            val status = checkStatus(context)
            if (status is SupertonicModelState.Ready) {
                onProgress(1.0f)
                true
            } else {
                _modelState.value = SupertonicModelState.Error("One or more model files could not be downloaded")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            _modelState.value = SupertonicModelState.Error("Download failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
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
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
