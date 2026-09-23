package com.kapp.karaoke.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioVocalRemover {

    /**
     * Decodes source audio/video file to 16-bit PCM WAV,
     * performs center-vocal cancellation (L - R),
     * and produces two WAV files:
     * 1. originalWav: original stereo mix
     * 2. instrumentalWav: center-vocal removed instrumental track
     */
    suspend fun processAudio(
        sourceFile: File,
        originalWavFile: File,
        instrumentalWavFile: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var origOut: FileOutputStream? = null
        var instOut: FileOutputStream? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext false
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            origOut = FileOutputStream(originalWavFile)
            instOut = FileOutputStream(instrumentalWavFile)

            // Write placeholder 44-byte WAV headers
            val headerPlaceholder = ByteArray(44)
            origOut.write(headerPlaceholder)
            instOut.write(headerPlaceholder)

            var totalPcmBytes = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val timeoutUs = 10000L

            val tempOrigBytes = ByteArray(8192)
            val tempInstBytes = ByteArray(8192)

            while (!isEos) {
                // Feed input
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()

                            if (durationUs > 0) {
                                val pct = ((sampleTime.toFloat() / durationUs) * 100).toInt().coerceIn(0, 99)
                                onProgress(pct)
                            }
                        }
                    }
                }

                // Drain output
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val frames = bufferInfo.size / 4 // 16-bit stereo = 4 bytes/frame
                        var byteIdx = 0

                        for (f in 0 until frames) {
                            if (outBuffer.remaining() < 4) break
                            val left = outBuffer.short
                            val right = outBuffer.short

                            // Original PCM frame
                            tempOrigBytes[byteIdx] = (left.toInt() and 0xFF).toByte()
                            tempOrigBytes[byteIdx + 1] = ((left.toInt() shr 8) and 0xFF).toByte()
                            tempOrigBytes[byteIdx + 2] = (right.toInt() and 0xFF).toByte()
                            tempOrigBytes[byteIdx + 3] = ((right.toInt() shr 8) and 0xFF).toByte()

                            // Instrumental PCM frame (L - R center-vocal cancellation)
                            // Both channels must be in-phase (diff, diff) so that phone speakers
                            // (which downmix stereo to mono) do NOT cancel out the audio to silence!
                            val diff = ((left.toInt() - right.toInt()) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                            tempInstBytes[byteIdx] = (diff.toInt() and 0xFF).toByte()
                            tempInstBytes[byteIdx + 1] = ((diff.toInt() shr 8) and 0xFF).toByte()
                            tempInstBytes[byteIdx + 2] = (diff.toInt() and 0xFF).toByte()
                            tempInstBytes[byteIdx + 3] = ((diff.toInt() shr 8) and 0xFF).toByte()

                            byteIdx += 4
                            if (byteIdx >= tempOrigBytes.size - 4) {
                                origOut.write(tempOrigBytes, 0, byteIdx)
                                instOut.write(tempInstBytes, 0, byteIdx)
                                totalPcmBytes += byteIdx
                                byteIdx = 0
                            }
                        }

                        if (byteIdx > 0) {
                            origOut.write(tempOrigBytes, 0, byteIdx)
                            instOut.write(tempInstBytes, 0, byteIdx)
                            totalPcmBytes += byteIdx
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                }
            }

            origOut.flush()
            instOut.flush()
            try { origOut.close() } catch (_: Exception) {}
            try { instOut.close() } catch (_: Exception) {}
            origOut = null
            instOut = null

            if (totalPcmBytes <= 0) {
                android.util.Log.e("AudioVocalRemover", "No PCM bytes decoded from audio source.")
                originalWavFile.delete()
                instrumentalWavFile.delete()
                return@withContext false
            }

            // Overwrite proper WAV header with actual data size
            writeWavHeader(originalWavFile, totalPcmBytes, sampleRate, 2)
            writeWavHeader(instrumentalWavFile, totalPcmBytes, sampleRate, 2)

            android.util.Log.i("AudioVocalRemover", "Vocal removal complete. Decoded $totalPcmBytes bytes.")
            onProgress(100)
            true
        } catch (e: Exception) {
            android.util.Log.e("AudioVocalRemover", "Error processing audio: ${e.message}", e)
            try { originalWavFile.delete() } catch (_: Exception) {}
            try { instrumentalWavFile.delete() } catch (_: Exception) {}
            false
        } finally {
            try { origOut?.close() } catch (_: Exception) {}
            try { instOut?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(file: File, totalAudioLen: Long, sampleRate: Int, channels: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}
