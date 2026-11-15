package com.callshare.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.callshare.native.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioRouteManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isSessionActive = false
    private var recordJob: Job? = null
    private var trackJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    companion object {
        private const val TAG = "AudioRouteManager"
        private const val SAMPLE_RATE = 16000 // 16 kHz Wideband speech
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE_SAMPLES = 320 // 20ms @ 16kHz
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 640 bytes
    }

    /**
     * Configure Android audio hardware for VoIP / Telecom bridging
     */
    fun startBridgedAudioSession() {
        if (isSessionActive) return
        isSessionActive = true

        Log.i(TAG, "Configuring hardware audio mode to MODE_IN_COMMUNICATION")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        requestAudioFocus()
        selectOptimalCommunicationDevice()
        startAudioLoops()
    }

    private fun requestAudioFocus() {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Audio focus changed: $focusChange")
            }
            .build()
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun selectOptimalCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val earpiece = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

            // Default to earpiece for private call; fallback to speaker
            val selected = earpiece ?: speaker
            if (selected != null) {
                audioManager.setCommunicationDevice(selected)
                Log.i(TAG, "Communication device set to: ${selected.productName} (${selected.type})")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    fun setSpeakerphone(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val targetType = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val target = devices.find { it.type == targetType }
            if (target != null) {
                audioManager.setCommunicationDevice(target)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private fun startAudioLoops() {
        val minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val recordBufSize = minRecordBuf.coerceAtLeast(FRAME_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                recordBufSize
            )
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission missing for AudioRecord: ${e.message}")
            return
        }

        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val trackBufSize = minTrackBuf.coerceAtLeast(FRAME_SIZE_BYTES * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        // 1. Microphone capture loop -> Rust native encoder & UDP streamer
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val pcmChunk = ByteArray(FRAME_SIZE_BYTES)
            while (isActive && isSessionActive) {
                val bytesRead = audioRecord?.read(pcmChunk, 0, pcmChunk.size) ?: -1
                if (bytesRead > 0) {
                    NativeBridge.processMicCapture(pcmChunk)
                }
            }
        }

        // 2. Playback loop <- Rust native jitter buffer & PLC -> AudioTrack
        trackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isSessionActive) {
                val pcmSamples = NativeBridge.pullPlaybackPcm(FRAME_SIZE_SAMPLES)
                if (pcmSamples.isNotEmpty()) {
                    audioTrack?.write(pcmSamples, 0, pcmSamples.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }

        Log.i(TAG, "Audio capture and playback pipelines running")
    }

    fun stopBridgedAudioSession() {
        if (!isSessionActive) return
        isSessionActive = false

        recordJob?.cancel()
        trackJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "Hardware audio restored to MODE_NORMAL")
    }
}
