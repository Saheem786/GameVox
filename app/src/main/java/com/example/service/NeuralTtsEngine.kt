package com.example.service

/**
 * Common contract for GameVox free neural and fallback speech synthesis backends.
 */
interface NeuralTtsEngine {
    val backendId: String
    val isReady: Boolean
    val currentBackendLabel: String
    val numSpeakers: Int

    /**
     * Synthesizes [text] using speaker ID [sid], rate [speed], and [pitch],
     * streaming PCM audio to the device speaker while verifying that the active
     * [epoch] has not been cancelled by a newer dialogue line or pause/stop event.
     */
    suspend fun synthesizeAndPlay(
        text: String,
        sid: Int,
        speed: Float,
        pitch: Float,
        epoch: Long,
        onFirstAudioStarted: (latencyMs: Long) -> Unit = {},
        onCompleted: () -> Unit = {},
        onError: (errorMessage: String) -> Unit = {}
    ): Boolean

    /**
     * Halts active audio playback and clears internal buffers.
     */
    fun stop()

    /**
     * Releases hardware AudioTrack and native resources.
     */
    fun release()
}
