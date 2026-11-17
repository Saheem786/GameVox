package com.example.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.engine.SpeakerHeuristicEngine
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownServiceException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Distinct diagnostic categories for local Chatterbox server connectivity and inference status.
 */
enum class NeuralServerErrorCategory(val code: String, val displayTitle: String) {
    NONE("NONE", "Connected & Ready"),
    HTTP_BLOCKED("HTTP_BLOCKED", "HTTP Blocked (Cleartext Policy / Public Host Restricted)"),
    SERVER_UNREACHABLE("SERVER_UNREACHABLE", "Server Unreachable (Check IP, Port, Wi-Fi & Firewall)"),
    AUTH_FAILED("AUTH_FAILED", "Authentication Failed (Invalid X-GameVox-Token)"),
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE", "Model Unavailable (Chatterbox Hindi Model Not Loaded)"),
    INFERENCE_FAILED("INFERENCE_FAILED", "Inference Failed (TTS Generation Error on Server)")
}

data class ServerReferenceVoiceInfo(
    val voiceId: String,
    val fileName: String,
    val sha256: String,
    val sampleRate: Int,
    val durationSeconds: Float,
    val waveformRms: Float,
    val presentation: String,
    val description: String
)

data class NeuralServerHealthReport(
    val reachable: Boolean,
    val modelLoaded: Boolean,
    val status: String,
    val engineName: String,
    val modelId: String,
    val device: String,
    val sampleRate: Int,
    val referenceVoiceFilesCount: Int,
    val uniqueReferenceVoicesSha256Count: Int,
    val conditionalsCachedCount: Int,
    val roundTripLatencyMs: Long,
    val errorMessage: String = "",
    val errorCategory: NeuralServerErrorCategory = if (reachable && modelLoaded) {
        NeuralServerErrorCategory.NONE
    } else if (reachable) {
        NeuralServerErrorCategory.MODEL_UNAVAILABLE
    } else {
        NeuralServerErrorCategory.SERVER_UNREACHABLE
    },
    val referenceVoices: List<ServerReferenceVoiceInfo> = emptyList()
)

data class NeuralSynthesizeRequest(
    val text: String,
    val characterId: String,
    val referenceVoiceId: String,
    val languageId: String = "hi",
    val emotion: String = "neutral",
    val exaggeration: Float = 0.50f,
    val cfgWeight: Float = 0.40f,
    val temperature: Float = 0.80f,
    val requestId: String = "gv_${UUID.randomUUID().toString().take(8)}",
    val epoch: Long = 0L
)

data class ParsedWavData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmBytes: ByteArray,
    val waveformRms: Float,
    val durationMs: Long,
    val sha256Hex: String
)

sealed class NeuralSynthesisOutcome {
    data class Success(
        val rawWavBytes: ByteArray,
        val parsedWav: ParsedWavData,
        val serverRequestLatencyMs: Long,
        val serverInferenceLatencyMs: Long,
        val conditioningLatencyMs: Long,
        val conditioningCached: Boolean,
        val voiceId: String,
        val voiceSha256: String,
        val deviceUsed: String,
        val appliedExaggeration: Float,
        val appliedCfgWeight: Float
    ) : NeuralSynthesisOutcome()

    data class Error(
        val httpStatus: Int,
        val reason: String,
        val requestLatencyMs: Long,
        val errorCategory: NeuralServerErrorCategory = classifyError(httpStatus, reason)
    ) : NeuralSynthesisOutcome() {
        companion object {
            fun classifyError(httpStatus: Int, reason: String): NeuralServerErrorCategory {
                val lower = reason.lowercase(Locale.ROOT)
                return when {
                    lower.contains("cleartext") ||
                        lower.contains("not permitted") ||
                        lower.contains("http_blocked") ||
                        lower.contains("public host restricted") -> NeuralServerErrorCategory.HTTP_BLOCKED
                    httpStatus == 401 || httpStatus == 403 || lower.contains("unauthorized") -> NeuralServerErrorCategory.AUTH_FAILED
                    httpStatus == 503 || lower.contains("not loaded") || lower.contains("model_unavailable") -> NeuralServerErrorCategory.MODEL_UNAVAILABLE
                    httpStatus in 400..599 -> NeuralServerErrorCategory.INFERENCE_FAILED
                    else -> NeuralServerErrorCategory.SERVER_UNREACHABLE
                }
            }
        }
    }
}

sealed class ReferenceVoiceUploadOutcome {
    data class Success(
        val voiceId: String,
        val sha256: String,
        val sampleRate: Int,
        val durationSeconds: Float,
        val waveformRms: Float,
        val trimmedSilenceMs: Long,
        val qualityWarning: String,
        val uploadLatencyMs: Long
    ) : ReferenceVoiceUploadOutcome()

    data class Error(
        val httpStatus: Int,
        val reason: String,
        val errorCategory: NeuralServerErrorCategory
    ) : ReferenceVoiceUploadOutcome()
}

/**
 * Local Wi-Fi HTTPS/HTTP client for the self-hosted Windows/Linux Python FastAPI Voice Server
 * powered by ResembleAI Chatterbox Multilingual V3 Dedicated Hindi (`ResembleAI/Chatterbox-Multilingual-hi`).
 *
 * Security & Reliability Guarantees:
 * - Supports secure `https://` connections to the local voice server (including self-signed LAN certificates
 *   strictly scoped to RFC-1918 private LAN / loopback hosts).
 * - For development-only local LAN `http://` testing (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `127.0.0.1`),
 *   works with `@xml/network_security_config` and provides a scoped RFC-1918 LAN socket transport fallback if
 *   a dynamic LAN IP is blocked by `HttpURLConnection`, while strictly blocking cleartext HTTP to any public host.
 * - Reports distinct error categories: `HTTP_BLOCKED`, `SERVER_UNREACHABLE`, `AUTH_FAILED`, `MODEL_UNAVAILABLE`, `INFERENCE_FAILED`.
 * - Verifies returned WAV audio has non-silent PCM16 RMS >= [MIN_NON_SILENT_RMS] (`1e-4f`).
 */
class NeuralVoiceClient(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 25000
) {

    @Volatile
    var lastErrorCategory: NeuralServerErrorCategory = NeuralServerErrorCategory.NONE
        private set

    companion object {
        private const val TAG = "NeuralVoiceClient"
        const val DEFAULT_SERVER_URL = "http://192.168.1.100:8765"
        const val DEFAULT_AUTH_TOKEN = "gamevox-local-secret-2026"
        const val MIN_NON_SILENT_RMS = 1e-4f

        fun normalizeBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

        /**
         * Returns true ONLY if [host] is a loopback or private RFC-1918 local LAN address:
         * - `localhost`, `*.local`
         * - `127.0.0.0/8`
         * - `10.0.0.0/8`
         * - `172.16.0.0/12` (`172.16.x.x` .. `172.31.x.x`)
         * - `192.168.0.0/16` (`192.168.x.x`)
         *
         * Cleartext HTTP and local self-signed TLS trust are strictly restricted to these hosts
         * and never permitted for public internet domains or public IPs.
         */
        fun isPrivateLanOrLoopbackHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.ROOT).trim('[', ']')
            if (h.isEmpty()) return false
            if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true

            val parts = h.split(".")
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                val p0 = parts[0].toInt()
                val p1 = parts[1].toInt()
                if (p0 == 127) return true
                if (p0 == 10) return true
                if (p0 == 192 && p1 == 168) return true
                if (p0 == 172 && p1 in 16..31) return true
                return false
            }
            return false
        }

        fun classifyException(e: Throwable, rawUrl: String = ""): Pair<NeuralServerErrorCategory, String> {
            val msg = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            val lower = msg.lowercase(Locale.ROOT)
            return if (e is UnknownServiceException ||
                lower.contains("cleartext") ||
                lower.contains("not permitted")
            ) {
                NeuralServerErrorCategory.HTTP_BLOCKED to
                    "HTTP Blocked: Cleartext HTTP traffic to $rawUrl blocked by network policy ($msg). Use HTTPS or a private LAN IP (192.168.x.x / 10.x.x.x)."
            } else {
                NeuralServerErrorCategory.SERVER_UNREACHABLE to
                    "Server Unreachable (${e.javaClass.simpleName}: $msg)"
            }
        }

        fun computePcm16Rms(pcmBytes: ByteArray): Float {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount <= 0) return 0f
            val shortBuf = ByteBuffer.wrap(pcmBytes, 0, sampleCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            var sumSq = 0.0
            for (i in 0 until sampleCount) {
                val norm = shortBuf.get(i) / 32768.0
                sumSq += norm * norm
            }
            return sqrt(sumSq / sampleCount).toFloat()
        }

        fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Parses a standard RIFF WAVE byte array, extracting sampleRate, channels, bitsPerSample,
         * raw PCM16 payload, normalized RMS amplitude, and duration in milliseconds.
         */
        fun parseWavBytes(wavBytes: ByteArray): ParsedWavData? {
            if (wavBytes.size < 44) return null
            val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
            val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") return null

            val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var sampleRate = 24000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (offset + 8 <= wavBytes.size) {
                val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = buf.getInt(offset + 4)
                val chunkBodyStart = offset + 8
                if (chunkSize < 0 || chunkBodyStart > wavBytes.size) break

                if (chunkId == "fmt " && chunkSize >= 16 && chunkBodyStart + 16 <= wavBytes.size) {
                    channels = buf.getShort(chunkBodyStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = buf.getInt(chunkBodyStart + 4).coerceAtLeast(8000)
                    bitsPerSample = buf.getShort(chunkBodyStart + 14).toInt().coerceAtLeast(8)
                } else if (chunkId == "data") {
                    dataOffset = chunkBodyStart
                    dataSize = chunkSize.coerceAtMost(wavBytes.size - chunkBodyStart)
                    break
                }
                val paddedSize = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
                offset = chunkBodyStart + paddedSize
            }

            if (dataOffset < 0 || dataSize <= 0) return null
            val pcmBytes = wavBytes.copyOfRange(dataOffset, dataOffset + dataSize)
            val rms = computePcm16Rms(pcmBytes)
            val bytesPerSampleFrame = (channels * (bitsPerSample / 8)).coerceAtLeast(2)
            val totalFrames = pcmBytes.size / bytesPerSampleFrame
            val durationMs = ((totalFrames * 1000L) / sampleRate.toLong()).coerceAtLeast(1L)

            return ParsedWavData(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                pcmBytes = pcmBytes,
                waveformRms = rms,
                durationMs = durationMs,
                sha256Hex = computeSha256Hex(wavBytes)
            )
        }

        private val scopedLanSslSocketFactory by lazy {
            val trustAllLanCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllLanCerts, SecureRandom())
            }.socketFactory
        }

        private val scopedLanHostnameVerifier = HostnameVerifier { hostname, session ->
            if (isPrivateLanOrLoopbackHost(hostname)) {
                true
            } else {
                HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray
    ) {
        fun header(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        fun bodyUtf8(): String = String(bodyBytes, Charsets.UTF_8).trim()
    }

    private val activePlaybackEpoch = AtomicLong(0L)
    private val audioTrackLock = Any()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    /**
     * Executes an HTTP/HTTPS request with:
     * 1. Policy check: Rejects cleartext `http://` to any non-LAN public host with `UnknownServiceException`.
     * 2. Scoped HTTPS TLS support for self-signed certificates on private LAN IPs (`192.168.x.x`, `10.x.x.x`, etc.).
     * 3. Automatic fallback to a scoped RFC-1918 LAN socket HTTP/1.1 transport if Android's `HttpURLConnection`
     *    rejects a private LAN IP not explicitly enumerated in `network_security_config.xml`.
     */
    private fun executeHttpRequest(
        url: URL,
        method: String,
        authToken: String,
        acceptHeader: String,
        contentType: String? = null,
        requestBody: ByteArray? = null,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 25000
    ): RawHttpResponse {
        val protocol = url.protocol.lowercase(Locale.ROOT)
        val host = url.host.orEmpty()
        val isPrivateLan = isPrivateLanOrLoopbackHost(host)

        // Never permit cleartext HTTP to public internet hosts
        if (protocol == "http" && !isPrivateLan) {
            throw UnknownServiceException(
                "HTTP_BLOCKED: Cleartext HTTP traffic to public host '$host' is not permitted. Use https:// or a private RFC-1918 LAN IP (e.g. 192.168.1.100)."
            )
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection && isPrivateLan) {
                    sslSocketFactory = scopedLanSslSocketFactory
                    hostnameVerifier = scopedLanHostnameVerifier
                }
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", acceptHeader)
                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                applyAuthHeaders(this, authToken)
                if (requestBody != null) {
                    doOutput = true
                }
            }

            if (requestBody != null) {
                conn.outputStream.use { out ->
                    out.write(requestBody)
                    out.flush()
                }
            }

            val code = conn.responseCode
            val responseHeaders = mutableMapOf<String, String>()
            var headerIdx = 0
            while (true) {
                val key = conn.getHeaderFieldKey(headerIdx)
                val value = conn.getHeaderField(headerIdx)
                if (key == null && value == null) break
                if (key != null && value != null) {
                    responseHeaders[key] = value
                }
                headerIdx++
            }

            val bodyBytes = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.use { it.readBytes() } ?: ByteArray(0)
            } catch (_: Exception) {
                ByteArray(0)
            }

            return RawHttpResponse(
                statusCode = code,
                headers = responseHeaders,
                bodyBytes = bodyBytes
            )
        } catch (e: Exception) {
            val msg = (e.localizedMessage ?: e.message ?: "").lowercase(Locale.ROOT)
            val isCleartextBlock = e is UnknownServiceException ||
                msg.contains("cleartext") ||
                msg.contains("not permitted")

            // If Android's HttpURLConnection blocked a legitimate RFC-1918 private LAN IP that wasn't
            // listed in network_security_config.xml (e.g. 192.168.29.x), execute via scoped LAN socket.
            if (isCleartextBlock && protocol == "http" && isPrivateLan) {
                Log.i(TAG, "Using scoped private-LAN socket transport for $url")
                return executeScopedLanSocketHttp(
                    url = url,
                    method = method,
                    authToken = authToken,
                    acceptHeader = acceptHeader,
                    contentType = contentType,
                    requestBody = requestBody,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs
                )
            }
            throw e
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Minimal HTTP/1.1 client over `java.net.Socket` strictly restricted to verified RFC-1918
     * private LAN / loopback addresses (`isPrivateLanOrLoopbackHost == true`).
     */
    private fun executeScopedLanSocketHttp(
        url: URL,
        method: String,
        authToken: String,
        acceptHeader: String,
        contentType: String?,
        requestBody: ByteArray?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): RawHttpResponse {
        val host = url.host.orEmpty()
        if (!isPrivateLanOrLoopbackHost(host)) {
            throw UnknownServiceException("HTTP_BLOCKED: Host '$host' is not a private LAN address.")
        }
        val port = if (url.port > 0) url.port else 80
        val pathAndQuery = buildString {
            append(url.path.ifEmpty { "/" })
            if (!url.query.isNullOrEmpty()) {
                append("?").append(url.query)
            }
        }

        Socket().use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val out = socket.getOutputStream()
            val cleanToken = authToken.trim()
            val bodyLen = requestBody?.size ?: 0
            val reqHeader = buildString {
                append("$method $pathAndQuery HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Accept: $acceptHeader\r\n")
                append("Connection: close\r\n")
                if (cleanToken.isNotEmpty()) {
                    append("X-GameVox-Token: $cleanToken\r\n")
                    append("Authorization: Bearer $cleanToken\r\n")
                }
                if (contentType != null) {
                    append("Content-Type: $contentType\r\n")
                }
                if (requestBody != null) {
                    append("Content-Length: $bodyLen\r\n")
                }
                append("\r\n")
            }
            out.write(reqHeader.toByteArray(Charsets.UTF_8))
            if (requestBody != null && requestBody.isNotEmpty()) {
                out.write(requestBody)
            }
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readAsciiLine(input)
                ?: throw IllegalStateException("Empty HTTP response from $host:$port")
            val statusParts = statusLine.split(" ")
            val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: 502

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim()
                    val v = line.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
            }

            val transferEncoding = headers.entries.firstOrNull {
                it.key.equals("Transfer-Encoding", ignoreCase = true)
            }?.value.orEmpty()
            val contentLength = headers.entries.firstOrNull {
                it.key.equals("Content-Length", ignoreCase = true)
            }?.value?.toIntOrNull() ?: -1

            val bodyBytes = when {
                transferEncoding.contains("chunked", ignoreCase = true) -> readChunkedBody(input)
                contentLength >= 0 -> readFixedBytes(input, contentLength)
                else -> input.readBytes()
            }

            return RawHttpResponse(
                statusCode = statusCode,
                headers = headers,
                bodyBytes = bodyBytes
            )
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawByte) String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r') else null
            }
            sawByte = true
            if (b == '\n'.code) {
                break
            }
            buf.write(b)
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
    }

    private fun readFixedBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val r = input.read(out, offset, count - offset)
            if (r <= 0) break
            offset += r
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input)?.trim() ?: break
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                readAsciiLine(input)
                break
            }
            val chunk = readFixedBytes(input, chunkSize)
            out.write(chunk)
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    /**
     * Queries `GET /v1/health` on the local Chatterbox Python server.
     * Reports distinct error categories (`HTTP_BLOCKED`, `SERVER_UNREACHABLE`, `AUTH_FAILED`, `MODEL_UNAVAILABLE`).
     */
    suspend fun checkServerHealth(
        rawBaseUrl: String,
        authToken: String
    ): NeuralServerHealthReport = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val startMs = SystemClock.elapsedRealtime()
        try {
            val url = URL("$baseUrl/v1/health")
            val response = executeHttpRequest(
                url = url,
                method = "GET",
                authToken = authToken,
                acceptHeader = "application/json",
                connectTimeoutMs = connectTimeoutMs.coerceAtMost(2500),
                readTimeoutMs = readTimeoutMs.coerceAtMost(3500)
            )
            val code = response.statusCode
            val rttMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)

            if (code != HttpURLConnection.HTTP_OK) {
                val errBody = response.bodyUtf8()
                val category = when (code) {
                    401, 403 -> NeuralServerErrorCategory.AUTH_FAILED
                    503 -> NeuralServerErrorCategory.MODEL_UNAVAILABLE
                    else -> NeuralServerErrorCategory.INFERENCE_FAILED
                }
                lastErrorCategory = category
                return@withContext NeuralServerHealthReport(
                    reachable = true,
                    modelLoaded = false,
                    status = "HTTP_$code",
                    engineName = "Chatterbox Local Server",
                    modelId = "",
                    device = "",
                    sampleRate = 24000,
                    referenceVoiceFilesCount = 0,
                    uniqueReferenceVoicesSha256Count = 0,
                    conditionalsCachedCount = 0,
                    roundTripLatencyMs = rttMs,
                    errorMessage = "${category.displayTitle} (HTTP $code: $errBody)",
                    errorCategory = category
                )
            }

            val body = response.bodyUtf8()
            val json = JSONObject(body)
            val voicesList = mutableListOf<ServerReferenceVoiceInfo>()
            val voicesArr = json.optJSONArray("reference_voices")
            if (voicesArr != null) {
                for (i in 0 until voicesArr.length()) {
                    val v = voicesArr.getJSONObject(i)
                    voicesList.add(
                        ServerReferenceVoiceInfo(
                            voiceId = v.optString("voice_id", ""),
                            fileName = v.optString("file_name", ""),
                            sha256 = v.optString("sha256", ""),
                            sampleRate = v.optInt("sample_rate", 24000),
                            durationSeconds = v.optDouble("duration_seconds", 0.0).toFloat(),
                            waveformRms = v.optDouble("waveform_rms", 0.0).toFloat(),
                            presentation = v.optString("presentation", "neutral"),
                            description = v.optString("description", "")
                        )
                    )
                }
            }

            val isLoaded = json.optBoolean("model_loaded", false)
            val loadErr = json.optString("load_error", "")
            val cat = if (isLoaded) {
                NeuralServerErrorCategory.NONE
            } else {
                NeuralServerErrorCategory.MODEL_UNAVAILABLE
            }
            lastErrorCategory = cat

            NeuralServerHealthReport(
                reachable = true,
                modelLoaded = isLoaded,
                status = json.optString("status", if (isLoaded) "READY" else "MODEL_NOT_LOADED"),
                engineName = json.optString("engine", "ResembleAI Chatterbox Multilingual V3 (Hindi)"),
                modelId = json.optString("model_id", "ResembleAI/Chatterbox-Multilingual-hi"),
                device = json.optString("device", "cpu"),
                sampleRate = json.optInt("sample_rate", 24000),
                referenceVoiceFilesCount = json.optInt("reference_voice_files_count", voicesList.size),
                uniqueReferenceVoicesSha256Count = json.optInt("unique_reference_voices_sha256_count", 0),
                conditionalsCachedCount = json.optInt("conditionals_cached_count", 0),
                roundTripLatencyMs = rttMs,
                errorMessage = if (!isLoaded && loadErr.isBlank()) {
                    "Model Unavailable: Chatterbox Multilingual Hindi model is not loaded on server"
                } else {
                    loadErr
                },
                errorCategory = cat,
                referenceVoices = voicesList
            )
        } catch (e: Exception) {
            val rttMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val (category, formattedError) = classifyException(e, baseUrl)
            lastErrorCategory = category
            NeuralServerHealthReport(
                reachable = false,
                modelLoaded = false,
                status = category.code,
                engineName = "Chatterbox Local Server",
                modelId = "ResembleAI/Chatterbox-Multilingual-hi",
                device = "offline",
                sampleRate = 24000,
                referenceVoiceFilesCount = 0,
                uniqueReferenceVoicesSha256Count = 0,
                conditionalsCachedCount = 0,
                roundTripLatencyMs = rttMs,
                errorMessage = formattedError,
                errorCategory = category
            )
        }
    }

    /**
     * Uploads a normalized reference voice clip (`.wav` / audio) to `POST /v1/voices/upload`
     * with mandatory `speaker_consent_confirmed=true` and multipart/form-data payload.
     */
    suspend fun uploadReferenceVoice(
        rawBaseUrl: String,
        authToken: String,
        voiceId: String,
        audioFile: File,
        speakerConsentConfirmed: Boolean
    ): ReferenceVoiceUploadOutcome = withContext(Dispatchers.IO) {
        if (!speakerConsentConfirmed) {
            lastErrorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            return@withContext ReferenceVoiceUploadOutcome.Error(
                httpStatus = 400,
                reason = "Voice ownership or explicit speaker permission confirmation is required before uploading.",
                errorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            )
        }
        if (!audioFile.exists() || audioFile.length() < 256L) {
            lastErrorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            return@withContext ReferenceVoiceUploadOutcome.Error(
                httpStatus = 400,
                reason = "Reference audio file is missing or too small (${audioFile.length()} bytes).",
                errorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            )
        }

        val safeId = voiceId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "custom_ref" }
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val boundary = "----GameVoxBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val startMs = SystemClock.elapsedRealtime()

        try {
            val fileBytes = audioFile.readBytes()
            val bodyStream = ByteArrayOutputStream(fileBytes.size + 512)
            val partHeader = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(audioFile.name).append("\"\r\n")
                append("Content-Type: audio/wav\r\n\r\n")
            }
            val partFooter = "\r\n--$boundary--\r\n"
            bodyStream.write(partHeader.toByteArray(Charsets.UTF_8))
            bodyStream.write(fileBytes)
            bodyStream.write(partFooter.toByteArray(Charsets.UTF_8))

            val encodedVoiceId = java.net.URLEncoder.encode(safeId, "UTF-8")
            val targetUrl = URL("$baseUrl/v1/voices/upload?voice_id=$encodedVoiceId&speaker_consent_confirmed=true")
            val response = executeHttpRequest(
                url = targetUrl,
                method = "POST",
                authToken = authToken,
                acceptHeader = "application/json",
                contentType = "multipart/form-data; boundary=$boundary",
                requestBody = bodyStream.toByteArray(),
                connectTimeoutMs = connectTimeoutMs.coerceAtLeast(3500),
                readTimeoutMs = readTimeoutMs.coerceAtLeast(15000)
            )

            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            if (response.statusCode !in 200..299) {
                val errDetail = response.bodyUtf8()
                val category = when (response.statusCode) {
                    401, 403 -> NeuralServerErrorCategory.AUTH_FAILED
                    503 -> NeuralServerErrorCategory.MODEL_UNAVAILABLE
                    else -> NeuralServerErrorCategory.INFERENCE_FAILED
                }
                lastErrorCategory = category
                return@withContext ReferenceVoiceUploadOutcome.Error(
                    httpStatus = response.statusCode,
                    reason = "${category.displayTitle} (HTTP ${response.statusCode}: $errDetail)",
                    errorCategory = category
                )
            }

            val json = JSONObject(response.bodyUtf8())
            lastErrorCategory = NeuralServerErrorCategory.NONE
            ReferenceVoiceUploadOutcome.Success(
                voiceId = json.optString("voice_id", safeId),
                sha256 = json.optString("sha256", computeSha256Hex(fileBytes)),
                sampleRate = json.optInt("sample_rate", 24000),
                durationSeconds = json.optDouble("duration_seconds", 0.0).toFloat(),
                waveformRms = json.optDouble("waveform_rms", 0.0).toFloat(),
                trimmedSilenceMs = json.optLong("trimmed_silence_ms", 0L),
                qualityWarning = json.optString("quality_warning", ""),
                uploadLatencyMs = elapsed
            )
        } catch (e: Exception) {
            val (category, formattedError) = classifyException(e, baseUrl)
            lastErrorCategory = category
            ReferenceVoiceUploadOutcome.Error(
                httpStatus = -1,
                reason = formattedError,
                errorCategory = category
            )
        }
    }

    /**
     * Sends `POST /v1/cancel` to abort any in-flight or stale synthesis on the Python server.
     */
    suspend fun cancelStaleSynthesis(
        rawBaseUrl: String,
        authToken: String,
        requestId: String = "",
        minValidEpoch: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        try {
            val payload = JSONObject().apply {
                put("request_id", requestId)
                put("min_valid_epoch", minValidEpoch)
            }.toString().toByteArray(Charsets.UTF_8)

            val response = executeHttpRequest(
                url = URL("$baseUrl/v1/cancel"),
                method = "POST",
                authToken = authToken,
                acceptHeader = "application/json",
                contentType = "application/json; charset=utf-8",
                requestBody = payload,
                connectTimeoutMs = connectTimeoutMs.coerceAtMost(1500),
                readTimeoutMs = readTimeoutMs.coerceAtMost(1500)
            )
            response.statusCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Calls `POST /v1/synthesize` on the local Chatterbox FastAPI server, validates WAV headers
     * and non-silent PCM16 RMS energy (`>= 1e-4f`), and returns disaggregated server + inference timing
     * or a categorized [NeuralSynthesisOutcome.Error].
     */
    suspend fun synthesizeHindiWav(
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest
    ): NeuralSynthesisOutcome = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val cleanDialogue = SpeakerHeuristicEngine.stripLeadingCharacterName(request.text).trim()
        if (cleanDialogue.isBlank()) {
            lastErrorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            return@withContext NeuralSynthesisOutcome.Error(
                httpStatus = 422,
                reason = "Inference Failed: Dialogue text is empty after stripping speaker prefix",
                requestLatencyMs = 0L,
                errorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
            )
        }

        val startMs = SystemClock.elapsedRealtime()
        try {
            val jsonBody = JSONObject().apply {
                put("text", cleanDialogue)
                put("character_id", request.characterId)
                put("reference_voice_id", request.referenceVoiceId.removeSuffix(".wav"))
                put("language_id", request.languageId)
                put("emotion", request.emotion)
                put("exaggeration", request.exaggeration.toDouble())
                put("cfg_weight", request.cfgWeight.toDouble())
                put("temperature", request.temperature.toDouble())
                put("request_id", request.requestId)
                put("epoch", request.epoch)
            }.toString().toByteArray(Charsets.UTF_8)

            val response = executeHttpRequest(
                url = URL("$baseUrl/v1/synthesize"),
                method = "POST",
                authToken = authToken,
                acceptHeader = "audio/wav",
                contentType = "application/json; charset=utf-8",
                requestBody = jsonBody,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )

            val status = response.statusCode
            val totalRequestMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            if (status != HttpURLConnection.HTTP_OK) {
                val errDetail = response.bodyUtf8()
                val category = when (status) {
                    401, 403 -> NeuralServerErrorCategory.AUTH_FAILED
                    503 -> NeuralServerErrorCategory.MODEL_UNAVAILABLE
                    else -> NeuralServerErrorCategory.INFERENCE_FAILED
                }
                lastErrorCategory = category
                return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = status,
                    reason = "${category.displayTitle} (HTTP $status: $errDetail)",
                    requestLatencyMs = totalRequestMs,
                    errorCategory = category
                )
            }

            val wavBytes = response.bodyBytes
            val parsed = parseWavBytes(wavBytes)
            if (parsed == null) {
                lastErrorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
                return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = 502,
                    reason = "Inference Failed: Invalid or truncated WAV header from neural voice server (${wavBytes.size} bytes)",
                    requestLatencyMs = totalRequestMs,
                    errorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
                )
            }

            if (parsed.waveformRms < MIN_NON_SILENT_RMS) {
                lastErrorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
                return@withContext NeuralSynthesisOutcome.Error(
                    httpStatus = 500,
                    reason = String.format(
                        Locale.US,
                        "Inference Failed: Rejected silent audio from server (RMS=%.6f < %.4f)",
                        parsed.waveformRms,
                        MIN_NON_SILENT_RMS
                    ),
                    requestLatencyMs = totalRequestMs,
                    errorCategory = NeuralServerErrorCategory.INFERENCE_FAILED
                )
            }

            lastErrorCategory = NeuralServerErrorCategory.NONE
            val inferMs = response.header("X-GameVox-Inference-Ms")?.toLongOrNull() ?: totalRequestMs
            val condMs = response.header("X-GameVox-Conditioning-Ms")?.toLongOrNull() ?: 0L
            val condCached = response.header("X-GameVox-Conditioning-Cached")?.equals("true", ignoreCase = true) == true
            val voiceId = response.header("X-GameVox-Voice-Id").orEmpty().ifBlank { request.referenceVoiceId }
            val voiceSha = response.header("X-GameVox-Voice-Sha256").orEmpty()
            val deviceUsed = response.header("X-GameVox-Device").orEmpty().ifBlank { "cpu" }
            val exagUsed = response.header("X-GameVox-Exaggeration")?.toFloatOrNull() ?: request.exaggeration
            val cfgUsed = response.header("X-GameVox-Cfg-Weight")?.toFloatOrNull() ?: request.cfgWeight

            NeuralSynthesisOutcome.Success(
                rawWavBytes = wavBytes,
                parsedWav = parsed,
                serverRequestLatencyMs = totalRequestMs,
                serverInferenceLatencyMs = inferMs,
                conditioningLatencyMs = condMs,
                conditioningCached = condCached,
                voiceId = voiceId,
                voiceSha256 = voiceSha,
                deviceUsed = deviceUsed,
                appliedExaggeration = exagUsed,
                appliedCfgWeight = cfgUsed
            )
        } catch (e: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val (category, formattedError) = classifyException(e, baseUrl)
            lastErrorCategory = category
            NeuralSynthesisOutcome.Error(
                httpStatus = -1,
                reason = formattedError,
                requestLatencyMs = elapsed,
                errorCategory = category
            )
        }
    }

    /**
     * Synthesizes Hindi speech via the local Chatterbox FastAPI server and writes the resulting
     * verified non-silent WAV bytes directly to [outputWavFile]. Never generates synthetic sine waves.
     */
    suspend fun synthesizeToWavFile(
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest,
        outputWavFile: File,
        onSuccess: (NeuralSynthesisOutcome.Success) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        when (val outcome = synthesizeHindiWav(rawBaseUrl, authToken, request)) {
            is NeuralSynthesisOutcome.Error -> {
                onError(outcome.reason)
                false
            }
            is NeuralSynthesisOutcome.Success -> {
                try {
                    outputWavFile.parentFile?.mkdirs()
                    FileOutputStream(outputWavFile).use { it.write(outcome.rawWavBytes) }
                    onSuccess(outcome)
                    outputWavFile.exists() && outputWavFile.length() > 100L
                } catch (e: Exception) {
                    onError("Failed to write synthesized WAV: ${e.localizedMessage}")
                    false
                }
            }
        }
    }

    @Volatile
    private var attachedExoPlayerManager: HindiExoPlayerManager? = null

    fun attachExoPlayerManager(manager: HindiExoPlayerManager) {
        attachedExoPlayerManager = manager
    }

    /**
     * Synthesizes multi-sentence Hindi dialogue sentence-by-sentence and plays the resulting WAV
     * audio streams sequentially via [HindiExoPlayerManager] with clean transitions between sentences
     * and automatic cleanup of staged audio files.
     */
    suspend fun synthesizeAndPlaySentencesWithExoPlayer(
        exoPlayerManager: HindiExoPlayerManager,
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest,
        exportWavFile: File? = null,
        onSynthesisTelemetry: (NeuralSynthesisOutcome.Success) -> Unit = {},
        onFirstAudioStarted: (Long) -> Unit = {},
        onCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        attachedExoPlayerManager = exoPlayerManager
        val currentEpoch = request.epoch
        activePlaybackEpoch.set(currentEpoch)
        exoPlayerManager.stopAndClearQueue()
        stopAudioTrackOnly()

        val cleanDialogue = SpeakerHeuristicEngine.stripLeadingCharacterName(request.text).trim()
        val sentences = HindiExoPlayerManager.splitIntoSentences(cleanDialogue)
        if (sentences.isEmpty()) {
            onError("Inference Failed: Dialogue text is empty after sentence splitting")
            return@withContext false
        }

        val pipelineStartMs = SystemClock.elapsedRealtime()
        var firstAudioReported = false

        for ((index, sentence) in sentences.withIndex()) {
            if (activePlaybackEpoch.get() != currentEpoch) {
                exoPlayerManager.stopAndClearQueue()
                return@withContext false
            }

            val sentenceRequest = request.copy(
                text = sentence,
                requestId = "${request.requestId}_s$index"
            )
            when (val outcome = synthesizeHindiWav(rawBaseUrl, authToken, sentenceRequest)) {
                is NeuralSynthesisOutcome.Error -> {
                    exoPlayerManager.stopAndClearQueue()
                    onError(outcome.reason)
                    return@withContext false
                }

                is NeuralSynthesisOutcome.Success -> {
                    if (activePlaybackEpoch.get() != currentEpoch) {
                        exoPlayerManager.stopAndClearQueue()
                        return@withContext false
                    }
                    if (index == 0) {
                        onSynthesisTelemetry(outcome)
                    }
                    val playedOk = exoPlayerManager.playWavBytesStream(
                        wavBytes = outcome.rawWavBytes,
                        sentenceText = sentence,
                        epoch = currentEpoch,
                        flushExistingQueue = (index == 0),
                        exportWavFile = if (index == 0) exportWavFile else null,
                        onFirstAudioStarted = {
                            if (!firstAudioReported) {
                                firstAudioReported = true
                                val totalFirstMs = (SystemClock.elapsedRealtime() - pipelineStartMs).coerceAtLeast(1L)
                                onFirstAudioStarted(totalFirstMs)
                            }
                        },
                        onError = onError
                    )
                    if (!playedOk) {
                        return@withContext false
                    }
                }
            }
        }

        if (activePlaybackEpoch.get() == currentEpoch) {
            onCompleted()
            return@withContext true
        }
        false
    }

    /**
     * Synthesizes Hindi speech via the local Chatterbox FastAPI server, exports the latest WAV
     * for A/B inspection if [exportWavFile] is provided, and plays the synthesized stream via
     * [HindiExoPlayerManager] (when attached or provided) or [AudioTrack] with full hardware buffer
     * drain and epoch cancellation support.
     */
    suspend fun synthesizeAndPlay(
        rawBaseUrl: String,
        authToken: String,
        request: NeuralSynthesizeRequest,
        exportWavFile: File? = null,
        exoPlayerManager: HindiExoPlayerManager? = attachedExoPlayerManager,
        onSynthesisTelemetry: (NeuralSynthesisOutcome.Success) -> Unit = {},
        onFirstAudioStarted: (Long) -> Unit = {},
        onCompleted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (exoPlayerManager != null) {
            return@withContext synthesizeAndPlaySentencesWithExoPlayer(
                exoPlayerManager = exoPlayerManager,
                rawBaseUrl = rawBaseUrl,
                authToken = authToken,
                request = request,
                exportWavFile = exportWavFile,
                onSynthesisTelemetry = onSynthesisTelemetry,
                onFirstAudioStarted = onFirstAudioStarted,
                onCompleted = onCompleted,
                onError = onError
            )
        }

        val currentEpoch = request.epoch
        activePlaybackEpoch.set(currentEpoch)
        stopAudioTrackOnly()

        val pipelineStartMs = SystemClock.elapsedRealtime()
        when (val outcome = synthesizeHindiWav(rawBaseUrl, authToken, request)) {
            is NeuralSynthesisOutcome.Error -> {
                onError(outcome.reason)
                return@withContext false
            }

            is NeuralSynthesisOutcome.Success -> {
                if (activePlaybackEpoch.get() != currentEpoch) {
                    Log.i(TAG, "Discarding stale neural server audio for epoch $currentEpoch")
                    return@withContext false
                }

                if (exportWavFile != null) {
                    try {
                        exportWavFile.parentFile?.mkdirs()
                        FileOutputStream(exportWavFile).use { it.write(outcome.rawWavBytes) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to export A/B wav file: ${e.message}")
                    }
                }

                onSynthesisTelemetry(outcome)

                val wav = outcome.parsedWav
                val channelMask = if (wav.channels >= 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    wav.sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(wav.sampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (e: Exception) {
                    onError("AudioTrack creation failed: ${e.localizedMessage}")
                    return@withContext false
                }

                synchronized(audioTrackLock) {
                    activeAudioTrack = track
                }

                try {
                    track.play()
                    val firstPlayableMs = (SystemClock.elapsedRealtime() - pipelineStartMs).coerceAtLeast(1L)
                    onFirstAudioStarted(firstPlayableMs)

                    val pcm = wav.pcmBytes
                    val chunkBytes = 4096
                    var offset = 0
                    while (offset < pcm.size) {
                        if (activePlaybackEpoch.get() != currentEpoch) {
                            stopAudioTrackOnly()
                            return@withContext false
                        }
                        val toWrite = minOf(chunkBytes, pcm.size - offset)
                        val written = track.write(pcm, offset, toWrite)
                        if (written < 0) break
                        offset += written
                    }

                    // Drain queued hardware frames before releasing AudioTrack
                    val bytesPerFrame = (wav.channels * 2).coerceAtLeast(2)
                    val totalFrames = pcm.size / bytesPerFrame
                    val drainTimeoutMs = wav.durationMs + 1500L
                    val drainStartMs = SystemClock.elapsedRealtime()
                    while (activePlaybackEpoch.get() == currentEpoch) {
                        val elapsedDrain = SystemClock.elapsedRealtime() - drainStartMs
                        val headFrames = try {
                            track.playbackHeadPosition
                        } catch (_: Exception) {
                            totalFrames
                        }
                        if (headFrames >= totalFrames || elapsedDrain >= drainTimeoutMs || elapsedDrain >= wav.durationMs) {
                            break
                        }
                        delay(20L)
                    }

                    if (activePlaybackEpoch.get() == currentEpoch) {
                        onCompleted()
                    }
                    true
                } catch (e: Exception) {
                    onError("Neural audio playback error: ${e.localizedMessage}")
                    false
                } finally {
                    synchronized(audioTrackLock) {
                        if (activeAudioTrack === track) {
                            activeAudioTrack = null
                        }
                    }
                    try {
                        track.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        track.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun stop() {
        activePlaybackEpoch.incrementAndGet()
        attachedExoPlayerManager?.stopAndClearQueue()
        stopAudioTrackOnly()
    }

    private fun stopAudioTrackOnly() {
        val track = synchronized(audioTrackLock) {
            val t = activeAudioTrack
            activeAudioTrack = null
            t
        }
        if (track != null) {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Exception) {
            }
            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun applyAuthHeaders(conn: HttpURLConnection, authToken: String) {
        val clean = authToken.trim()
        if (clean.isNotEmpty()) {
            conn.setRequestProperty("X-GameVox-Token", clean)
            conn.setRequestProperty("Authorization", "Bearer $clean")
        }
    }
}
