#include <jni.h>
#include <android/log.h>
#include <string.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_initContext(
        JNIEnv *env, jclass clazz, jstring model_path_str) {
    UNUSED(clazz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_freeContext(
        JNIEnv *env, jclass clazz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT jint JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_fullTranscribe(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring language_hint) {
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    const char *language_chars = NULL;
    if (language_hint != NULL) {
        language_chars = (*env)->GetStringUTFChars(env, language_hint, NULL);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = (language_chars && language_chars[0] != '\0') ? language_chars : "en";
    params.detect_language = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);
    int rc = whisper_full(context, params, audio_data_arr, audio_data_length);

    if (language_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_hint, language_chars);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_getTextSegmentCount(
        JNIEnv *env, jclass clazz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_getTextSegment(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_getTextSegmentStartMs(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return (jlong) (whisper_full_get_segment_t0(context, index) * 10);
}

JNIEXPORT jlong JNICALL
Java_com_convoy_androidtranscriber_engine_WhisperCppLib_getTextSegmentEndMs(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(clazz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return (jlong) (whisper_full_get_segment_t1(context, index) * 10);
}
