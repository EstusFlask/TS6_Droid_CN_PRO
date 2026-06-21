#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>

#include "rnnoise/rnnoise.h"

static int16_t clamp_i16(float value) {
    if (value > 32767.0f) return 32767;
    if (value < -32768.0f) return -32768;
    return (int16_t)lrintf(value);
}

JNIEXPORT jint JNICALL
Java_dev_tsdroid_bridge_RnNoise_nativeFrameSize(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return rnnoise_get_frame_size();
}

JNIEXPORT jlong JNICALL
Java_dev_tsdroid_bridge_RnNoise_nativeCreate(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_dev_tsdroid_bridge_RnNoise_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    DenoiseState *state = (DenoiseState *)(intptr_t)handle;
    if (state != NULL) {
        rnnoise_destroy(state);
    }
}

JNIEXPORT jfloat JNICALL
Java_dev_tsdroid_bridge_RnNoise_nativeProcessFrame(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jshortArray samples,
        jint offset,
        jfloat mix
) {
    (void)clazz;
    DenoiseState *state = (DenoiseState *)(intptr_t)handle;
    const int frame_size = rnnoise_get_frame_size();
    if (state == NULL || samples == NULL || offset < 0) return 0.0f;
    if ((*env)->GetArrayLength(env, samples) < offset + frame_size) return 0.0f;

    jshort pcm[480];
    float in[480];
    float out[480];
    if (frame_size > 480) {
        return 0.0f;
    }

    (*env)->GetShortArrayRegion(env, samples, offset, frame_size, pcm);
    if ((*env)->ExceptionCheck(env)) return 0.0f;

    for (int i = 0; i < frame_size; ++i) {
        in[i] = (float)pcm[i];
    }

    float speech_probability = rnnoise_process_frame(state, out, in);
    const float wet = fminf(fmaxf(mix, 0.0f), 1.0f);
    const float dry = 1.0f - wet;

    for (int i = 0; i < frame_size; ++i) {
        pcm[i] = clamp_i16(out[i] * wet + in[i] * dry);
    }

    (*env)->SetShortArrayRegion(env, samples, offset, frame_size, pcm);
    return speech_probability;
}
