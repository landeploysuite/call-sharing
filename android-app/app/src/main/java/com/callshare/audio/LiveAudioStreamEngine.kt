package com.callshare.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.callshare.native.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiveAudioStreamEngine
 *
 * Real-time, low-latency, full-duplex VoIP audio capture and playback engine.
 * Captures live 20ms PCM audio frames from the hardware microphone using AudioRecord
 * (with hardware AcousticEchoCanceler and NoiseSuppressor applied when supported),
 * transfers frames directly into the Rust NDK Opus/UDP pipeline via direct ByteBuffer,
 * extracts incoming playback frames from the Rust jitter buffer, and outputs immediately
 * to the hardware speaker/earpiece via AudioTrack.
 */
class LiveAudioStreamEngine private constructor() {

    companion object {
        private const val TAG = "LiveAudioStreamEngine"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        
        // 20ms frame at 16kHz mono 16-bit = 320 samples = 640 bytes
        const val FRAME_SIZE_SAMPLES = 320
        const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2

        @Volatile
        private var instance: LiveAudioStreamEngine? = null

        fun getInstance(): LiveAudioStreamEngine {
            return instance ?: synchronized(this) {
                instance ?: LiveAudioStreamEngine().also { instance = it }
            }
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var streamThread: Thread? = null

    fun isStreaming(): Boolean = isRunning.get()

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        NativeBridge.safeSetMicMute(muted)
        Log.i(TAG, "Live mic mute state changed: $muted")
    }

    @Synchronized
    fun startStreaming(context: Context): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Audio engine already running")
            return true
        }

        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordPermission) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; cannot initialize hardware mic")
            return false
        }

        try {
            // 1. Calculate minimum buffer sizes
            val minRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val recordBufferSize = maxOf(minRecordBufferSize * 2, FRAME_SIZE_BYTES * 4)

            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val trackBufferSize = maxOf(minTrackBufferSize * 2, FRAME_SIZE_BYTES * 4)

            // 2. Initialize hardware AudioRecord
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                recordBufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord hardware device")
                record.release()
                return false
            }

            // 3. Attach Hardware Acoustic Echo Canceler and Noise Suppressor
            val audioSessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "AcousticEchoCanceler hardware filter attached")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Echo canceler attachment bypassed: ${e.message}")
                }
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "NoiseSuppressor hardware filter attached")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Noise suppressor attachment bypassed: ${e.message}")
                }
            }

            // 4. Initialize low-latency AudioTrack for voice communication
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .build()

            val track = AudioTrack(
                attributes,
                format,
                trackBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioTrack output hardware")
                record.release()
                track.release()
                return false
            }

            this.audioRecord = record
            this.audioTrack = track
            this.isRunning.set(true)

            // 5. Start capture and playback hardware streams
            record.startRecording()
            track.play()

            // 6. Launch Dedicated Real-Time Audio Worker Thread
            streamThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runAudioLoop(record, track)
            }, "CallShare-UrgentAudioLoop").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Real-time Live Audio Stream Engine started successfully (16kHz 16-bit PCM)")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error starting Live Audio Stream Engine: ${e.message}", e)
            stopStreaming()
            return false
        }
    }

    private fun runAudioLoop(record: AudioRecord, track: AudioTrack) {
        val micBuffer = ByteBuffer.allocateDirect(FRAME_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val playBuffer = ByteBuffer.allocateDirect(FRAME_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val zeroBytes = ByteArray(FRAME_SIZE_BYTES)

        while (isRunning.get()) {
            try {
                // A. Read 20ms of live microphone PCM
                micBuffer.clear()
                val bytesRead = record.read(micBuffer, FRAME_SIZE_BYTES)

                if (bytesRead > 0) {
                    if (isMuted.get()) {
                        // Send silence packet if muted
                        micBuffer.clear()
                        micBuffer.put(zeroBytes)
                    }
                    micBuffer.position(0)
                    micBuffer.limit(bytesRead)
                    // Push live PCM direct buffer to Rust core Opus encoder + UDP streamer
                    NativeBridge.safeProcessMicCaptureDirect(micBuffer, bytesRead)
                }

                // B. Pull 20ms of incoming voice PCM from Rust core jitter buffer
                playBuffer.clear()
                val samplesPulled = NativeBridge.safePullPlaybackPcmDirect(playBuffer, FRAME_SIZE_SAMPLES)
                if (samplesPulled > 0) {
                    val bytesToWrite = samplesPulled * 2
                    playBuffer.position(0)
                    playBuffer.limit(bytesToWrite)
                    track.write(playBuffer, bytesToWrite, AudioTrack.WRITE_BLOCKING)
                }
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    Log.e(TAG, "Exception inside audio stream loop: ${t.message}")
                }
            }
        }
        Log.i(TAG, "Audio stream loop terminated cleanly")
    }

    @Synchronized
    fun stopStreaming() {
        if (!isRunning.getAndSet(false)) return

        try {
            streamThread?.interrupt()
            streamThread = null

            audioRecord?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                }
            }
            audioRecord = null

            audioTrack?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
                }
            }
            audioTrack = null

            try {
                echoCanceler?.release()
                noiseSuppressor?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error releasing audio filters: ${e.message}")
            }
            echoCanceler = null
            noiseSuppressor = null

            Log.i(TAG, "LiveAudioStreamEngine stopped and hardware devices released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error during audio engine teardown: ${e.message}")
        }
    }
}
