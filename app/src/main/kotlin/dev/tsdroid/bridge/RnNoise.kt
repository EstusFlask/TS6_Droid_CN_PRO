package dev.tsdroid.bridge

import android.util.Log

class RnNoise : AutoCloseable {
    companion object {
        private const val TAG = "RnNoise"

        val frameSize: Int by lazy {
            nativeFrameSize()
        }

        @JvmStatic
        private external fun nativeFrameSize(): Int

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeProcessFrame(
            handle: Long,
            samples: ShortArray,
            offset: Int,
            mix: Float,
        ): Float
    }

    private var handle: Long = nativeCreate()

    init {
        if (handle == 0L) {
            Log.w(TAG, "Failed to create RNNoise state")
        }
    }

    fun process(samples: ShortArray, offset: Int, mix: Float): Float {
        val currentHandle = handle
        if (currentHandle == 0L) return 0.0f
        return nativeProcessFrame(currentHandle, samples, offset, mix)
    }

    override fun close() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            nativeDestroy(currentHandle)
            handle = 0L
        }
    }
}
