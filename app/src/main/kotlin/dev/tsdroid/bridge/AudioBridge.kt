package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.tsdroid.data.SettingsStore
import dev.tslib.AudioConfig
import dev.tslib.OpusCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class AudioBridge(
    private val context: Context,
    private val tsClient: TsClient,
) {
    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 48000
        const val CODEC_OPUS_VOICE = 4
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample
        private const val MAX_QUEUE_FRAMES = 10 // Max buffered frames per user
        private const val LOCAL_VOICE_ACTIVE_THRESHOLD_DB = -46.8f
        private const val VOICE_ACTIVITY_RELEASE_MARGIN_DB = 6.0f
        private const val VOICE_ACTIVITY_HOLD_FRAMES = 12 // 240 ms at 20 ms/frame
        private const val VOICE_ACTIVITY_PRE_ROLL_FRAMES = 3 // 60 ms at 20 ms/frame
    }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    private val userQueues = ConcurrentHashMap<Int, ArrayDeque<ByteArray>>()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var rnNoise: RnNoise? = null

    // Dedicated single-thread scope for audio playback
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    @Volatile
    var gainFactor: Float = 1.0f

    @Volatile
    var voiceActivityDetectionEnabled: Boolean = false

    @Volatile
    var voiceActivityThresholdDb: Float = SettingsStore.DEFAULT_VOICE_ACTIVITY_THRESHOLD_DB

    @Volatile
    var noiseSuppressionEnabled: Boolean = false

    @Volatile
    var noiseSuppressionLevel: Int = SettingsStore.DEFAULT_NOISE_SUPPRESSION_LEVEL
        set(value) {
            field = value.coerceIn(
                SettingsStore.MIN_NOISE_SUPPRESSION_LEVEL,
                SettingsStore.MAX_NOISE_SUPPRESSION_LEVEL,
            )
        }

    @Volatile
    private var mutedUserIds: Set<Int> = emptySet()

    private val _isMuted = MutableStateFlow(true) // Start muted (PTT default)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalVoiceActive = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> = _isLocalVoiceActive.asStateFlow()

    fun initialize() {
        try {
            // Explicitly release any old audio stream resources if lingering
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            encoder = OpusCodec(audioConfig)
            initAudioTrack()
            startPlaybackLoop()
        } catch (e: Exception) {
            android.util.Log.e("TS6_DEBUG", "Caught audio initialization friction safely", e)
            // We don't throw here to prevent JE_AppCustomException
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (_isCapturing.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE_BYTES * 4),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is not initialized")
            record.release()
            return
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start microphone capture", e)
            record.release()
            return
        }
        audioRecord = record
        _isCapturing.value = true
        rnNoise = RnNoise()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            val vadPreRollFrames = ArrayDeque<ShortArray>(VOICE_ACTIVITY_PRE_ROLL_FRAMES)
            var vadGateOpen = false
            var vadHoldFrames = 0
            val codec = encoder ?: run {
                Log.e(TAG, "Cannot start capture: Opus encoder is not initialized")
                _isCapturing.value = false
                return@launch
            }
            while (isActive && _isCapturing.value) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_SIZE_SAMPLES) ?: break
                } catch (e: Throwable) {
                    Log.e(TAG, "Microphone read failed", e)
                    break
                }
                if (read < 0) {
                    Log.e(TAG, "Microphone read returned error $read")
                    break
                }
                if (read == FRAME_SIZE_SAMPLES && !_isMuted.value) {
                    val rmsDb = calculateRmsDb(buffer, read)
                    if (noiseSuppressionEnabled) {
                        applyNoiseSuppression(buffer, read)
                    }
                    val vadEnabled = voiceActivityDetectionEnabled
                    if (vadEnabled) {
                        val wasGateOpen = vadGateOpen
                        val thresholdDb = voiceActivityThresholdDb
                        val releaseThresholdDb = thresholdDb - VOICE_ACTIVITY_RELEASE_MARGIN_DB

                        when {
                            rmsDb >= thresholdDb -> {
                                vadGateOpen = true
                                vadHoldFrames = VOICE_ACTIVITY_HOLD_FRAMES
                            }
                            vadGateOpen && rmsDb >= releaseThresholdDb -> {
                                vadHoldFrames = VOICE_ACTIVITY_HOLD_FRAMES
                            }
                            vadGateOpen && vadHoldFrames > 0 -> {
                                vadHoldFrames--
                            }
                            else -> {
                                vadGateOpen = false
                            }
                        }

                        _isLocalVoiceActive.value = vadGateOpen

                        if (!vadGateOpen) {
                            if (vadPreRollFrames.size == VOICE_ACTIVITY_PRE_ROLL_FRAMES) {
                                vadPreRollFrames.removeFirst()
                            }
                            vadPreRollFrames.addLast(buffer.copyOf())
                            continue
                        }

                        if (!wasGateOpen) {
                            while (vadPreRollFrames.isNotEmpty()) {
                                sendPcmFrame(codec, vadPreRollFrames.removeFirst())
                            }
                        }
                    } else {
                        vadGateOpen = false
                        vadHoldFrames = 0
                        vadPreRollFrames.clear()
                        _isLocalVoiceActive.value = rmsDb >= LOCAL_VOICE_ACTIVE_THRESHOLD_DB
                    }

                    sendPcmFrame(codec, buffer)
                } else {
                    vadGateOpen = false
                    vadHoldFrames = 0
                    vadPreRollFrames.clear()
                    _isLocalVoiceActive.value = false
                }
            }
            _isCapturing.value = false
            _isLocalVoiceActive.value = false
            val finishedRecord = audioRecord
            audioRecord = null
            releaseRnNoise()
            try {
                finishedRecord?.stop()
            } catch (_: Throwable) {
            }
            try {
                finishedRecord?.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun stopCapture() {
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null
        releaseRnNoise()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SIZE_BYTES * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack?.play()
    }

    /**
     * Playback loop: decodes one opus frame per user, mixes PCM, writes
     * one combined frame to AudioTrack. All decoder access is on this
     * single thread so no synchronization is needed on decoders.
     */
    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch {
            val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)

            while (isActive) {
                var hasData = false
                mixBuffer.fill(0)

                for ((userId, queue) in userQueues) {
                    val opusData = synchronized(queue) { queue.pollFirst() } ?: continue
                    val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
                    try {
                        val pcmBytes = decoder.decode(opusData)
                        bytesToShorts(pcmBytes, decodeBuffer)
                        hasData = true
                        // Mix: sum with clipping
                        for (i in mixBuffer.indices) {
                            val sum = mixBuffer[i].toInt() + decodeBuffer[i].toInt()
                            mixBuffer[i] = sum.coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt(),
                            ).toShort()
                        }
                    } catch (_: Exception) {}
                }

                if (hasData) {
                    val gain = gainFactor
                    if (gain != 1.0f) {
                        for (i in mixBuffer.indices) {
                            mixBuffer[i] = (mixBuffer[i] * gain).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        }
                    }
                    val bytes = shortsToBytes(mixBuffer)
                    audioTrack?.write(bytes, 0, bytes.size)
                } else {
                    // No audio data — sleep briefly to avoid busy-waiting
                    delay(5)
                }
            }
        }
    }

    /**
     * Queue an opus packet for a specific user. Called from any thread;
     * decoding happens on the playback thread.
     */
    fun setMutedUserIds(userIds: Set<Int>) {
        mutedUserIds = userIds
    }

    fun playAudio(userId: Int, opusData: ByteArray) {
        if (userId in mutedUserIds) return
        if (_isOutputMuted.value) return // Global output mute — discard incoming audio
        val queue = userQueues.getOrPut(userId) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size < MAX_QUEUE_FRAMES) {
                queue.addLast(opusData)
            }
            // Drop oldest if queue is full (prevents unbounded lag)
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        tsClient.setInputMuted(muted)
    }

    fun toggleMute() {
        val newState = !_isMuted.value
        _isMuted.value = newState
        tsClient.setInputMuted(newState)
    }

    fun setOutputMuted(muted: Boolean) {
        _isOutputMuted.value = muted
        // When output is muted, clear all queued audio so nothing plays
        if (muted) {
            for ((_, queue) in userQueues) {
                synchronized(queue) { queue.clear() }
            }
        }
    }

    fun toggleOutputMute() {
        setOutputMuted(!_isOutputMuted.value)
    }

    fun release() {
        stopCapture()
        playbackJob?.cancel()
        playbackJob = null
        playbackScope.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        encoder?.close()
        encoder = null
        // Close per-user decoders
        for (decoder in userDecoders.values) {
            try { decoder.close() } catch (_: Exception) {}
        }
        userDecoders.clear()
        userQueues.clear()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray, out: ShortArray) {
        val count = minOf(bytes.size / 2, out.size)
        for (i in 0 until count) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or
                    (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    private fun sendPcmFrame(codec: OpusCodec, samples: ShortArray) {
        val pcmBytes = shortsToBytes(samples)
        try {
            val encoded = codec.encode(pcmBytes)
            tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
        } catch (_: Exception) {
        }
    }

    private fun calculateRmsDb(samples: ShortArray, count: Int): Float {
        if (count <= 0) return Float.NEGATIVE_INFINITY
        var energy = 0L
        for (i in 0 until count) {
            val sample = samples[i].toLong()
            energy += sample * sample
        }
        val rms = Math.sqrt(energy.toDouble() / count)
        if (rms <= 0.0) return Float.NEGATIVE_INFINITY
        return (20.0 * Math.log10(rms / Short.MAX_VALUE)).toFloat()
    }

    private fun applyNoiseSuppression(samples: ShortArray, count: Int) {
        val processor = rnNoise ?: return
        val frameSize = RnNoise.frameSize
        if (frameSize <= 0 || count < frameSize) return
        val mix = rnNoiseMix()
        var offset = 0
        while (offset + frameSize <= count) {
            processor.process(samples, offset, mix)
            offset += frameSize
        }
    }

    private fun rnNoiseMix(): Float {
        val level = noiseSuppressionLevel.coerceIn(
            SettingsStore.MIN_NOISE_SUPPRESSION_LEVEL,
            SettingsStore.MAX_NOISE_SUPPRESSION_LEVEL,
        )
        return when (level) {
            0 -> 0.55f
            1 -> 0.70f
            2 -> 0.85f
            else -> 1.0f
        }
    }

    private fun releaseRnNoise() {
        try {
            rnNoise?.close()
        } catch (_: Throwable) {
        }
        rnNoise = null
    }
}
