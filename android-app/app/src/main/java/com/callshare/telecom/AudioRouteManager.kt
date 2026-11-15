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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRouteManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isSessionActive = false
    private var recordJob: Job? = null
    private var trackJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

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

    /// Bug 3.2: AudioFocusChangeListener implementation prevents AudioTrack write stalls
    private fun requestAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "Audio focus changed: $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.w(TAG, "Audio focus lost transiently. Pausing AudioTrack & muting mic to avoid write block.")
                    try {
                        audioTrack?.pause()
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioTrack pause error: ${e.message}")
                    }
                    NativeBridge.setMicMute(true)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.i(TAG, "Audio focus regained. Resuming AudioTrack playback.")
                    try {
                        audioTrack?.play()
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioTrack resume error: ${e.message}")
                    }
                    NativeBridge.setMicMute(false)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun selectOptimalCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val earpiece = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

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

    /// Bug 2.2: Zero-allocation direct ByteBuffer loops eliminating JNI Local Reference table overflows
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

        // 1. Pre-allocated direct buffer mic capture loop
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val directMicBuffer = ByteBuffer.allocateDirect(FRAME_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive && isSessionActive) {
                directMicBuffer.clear()
                val bytesRead = audioRecord?.read(directMicBuffer, FRAME_SIZE_BYTES) ?: -1
                if (bytesRead > 0) {
                    NativeBridge.processMicCaptureDirect(directMicBuffer, bytesRead)
                }
            }
        }

        // 2. Pre-allocated direct buffer speaker playback loop
        trackJob = CoroutineScope(Dispatchers.IO).launch {
            val directPlayBuffer = ByteBuffer.allocateDirect(FRAME_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive && isSessionActive) {
                directPlayBuffer.clear()
                val samplesPulled = NativeBridge.pullPlaybackPcmDirect(directPlayBuffer, FRAME_SIZE_SAMPLES)
                if (samplesPulled > 0) {
                    directPlayBuffer.position(0)
                    directPlayBuffer.limit(samplesPulled * 2)
                    audioTrack?.write(directPlayBuffer, samplesPulled * 2, AudioTrack.WRITE_BLOCKING)
                }
            }
        }

        Log.i(TAG, "Zero-allocation direct buffer audio capture and playback pipelines active")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }

        audioManager.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "Hardware audio restored to MODE_NORMAL")
    }
}
