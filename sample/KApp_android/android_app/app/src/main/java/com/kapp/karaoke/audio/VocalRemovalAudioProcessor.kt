package com.kapp.karaoke.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time stereo vocal suppression audio processor for Media3 ExoPlayer.
 * 
 * Works by subtracting center-panned audio (L - R) in 16-bit PCM stereo,
 * effectively attenuating lead vocals which are panned center in studio mixes,
 * enabling instantaneous "Vocals: ON" <-> "Vocals: OFF (Karaoke)" toggling.
 */
class VocalRemovalAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isVocalRemovalEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        // Only works for stereo (2 channels)
        if (inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (!isVocalRemovalEnabled) {
            // Passthrough original stereo audio
            outputBuffer.put(inputBuffer)
        } else {
            // Process 16-bit PCM stereo samples
            // 4 bytes per frame: 2 bytes Left + 2 bytes Right
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.short
                val right = inputBuffer.short

                // Center channel cancellation: (Left - Right)
                // Lead vocals are typically panned dead center (Left == Right),
                // so subtracting them eliminates or heavily suppresses the vocals.
                val diff = ((left.toInt() - right.toInt()) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                // Output in-phase stereo so mono phone speakers don't cancel out to silence
                outputBuffer.putShort(diff)
                outputBuffer.putShort(diff)
            }
        }
        outputBuffer.flip()
    }
}
