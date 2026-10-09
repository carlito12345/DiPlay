#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    SpeexEchoState *echo;
    SpeexPreprocessState *preprocess;
    int frame;
} Canceller;

static void destroy(Canceller *c) {
    if (c == NULL) return;
    if (c->preprocess != NULL) speex_preprocess_state_destroy(c->preprocess);
    if (c->echo != NULL) speex_echo_state_destroy(c->echo);
    free(c);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeCreate(
        JNIEnv *env, jclass clazz, jint frame, jint filter, jint rate, jint suppress, jint suppressActive) {
    (void) env;
    (void) clazz;
    if (frame <= 0 || filter < frame || rate <= 0) return 0;
    Canceller *c = calloc(1, sizeof(Canceller));
    if (c == NULL) return 0;
    c->frame = frame;
    c->echo = speex_echo_state_init(frame, filter);
    c->preprocess = speex_preprocess_state_init(frame, rate);
    if (c->echo == NULL || c->preprocess == NULL) {
        destroy(c);
        return 0;
    }
    spx_int32_t value = rate;
    speex_echo_ctl(c->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &value);
    // The recorder's own noise suppression is disabled while this runs (it would distort the echo the
    // filter models), so denoise here, after cancelling. Gain stays with the platform.
    value = 1;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &value);
    value = 0;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_AGC, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_VAD, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_DEREVERB, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, c->echo);
    value = suppress;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &value);
    value = suppressActive;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &value);
    return (jlong) (intptr_t) c;
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeProcess(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray mic, jshortArray reference, jshortArray out) {
    (void) clazz;
    Canceller *c = (Canceller *) (intptr_t) handle;
    if (c == NULL || mic == NULL || reference == NULL || out == NULL) return JNI_FALSE;
    if ((*env)->GetArrayLength(env, mic) < c->frame ||
        (*env)->GetArrayLength(env, reference) < c->frame ||
        (*env)->GetArrayLength(env, out) < c->frame) return JNI_FALSE;
    jshort *m = NULL;
    jshort *r = NULL;
    jshort *o = NULL;
    jboolean ok = JNI_FALSE;
    m = (*env)->GetShortArrayElements(env, mic, NULL);
    if (m == NULL) goto cleanup;
    r = (*env)->GetShortArrayElements(env, reference, NULL);
    if (r == NULL) goto cleanup;
    o = (*env)->GetShortArrayElements(env, out, NULL);
    if (o == NULL) goto cleanup;
    speex_echo_cancellation(c->echo, m, r, o);
    speex_preprocess_run(c->preprocess, o);
    ok = JNI_TRUE;
cleanup:
    if (o != NULL) (*env)->ReleaseShortArrayElements(env, out, o, ok ? 0 : JNI_ABORT);
    if (r != NULL) (*env)->ReleaseShortArrayElements(env, reference, r, JNI_ABORT);
    if (m != NULL) (*env)->ReleaseShortArrayElements(env, mic, m, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SpeexEchoCanceller_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    destroy((Canceller *) (intptr_t) handle);
}
