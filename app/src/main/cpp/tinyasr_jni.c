/*
 * JNI bridge between dev.tinyasr.app.TinyAsr and the tinyasr C API
 * (https://github.com/dkaraush/tinyasr, GPL-3.0-or-later).
 *
 * Unlike Telegram's monolithic libtmessages.so this library has no JNI_OnLoad
 * and no dependency on any Telegram Java class, so it loads in any app.
 *
 * All calls for one models object must come from the same thread while a
 * session or file of it is running; the Java side serialises them.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <jni.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "tinyasr.h"

/* Pins the calling thread to the big cores; returns their count (0: unknown). */
static int pin_to_big_cores(void) {
#ifdef __linux__
    static int cap[CPU_SETSIZE];
    int n = 0, max = 0, big = 0;
    for (; n < CPU_SETSIZE; n++) {
        char path[64];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpu_capacity", n);
        FILE *f = fopen(path, "r");
        if (f == NULL) break;
        if (fscanf(f, "%d", &cap[n]) != 1) cap[n] = 0;
        fclose(f);
        if (cap[n] > max) max = cap[n];
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < n; i++)
        if (max > 0 && cap[i] * 10 >= max * 9) {
            CPU_SET(i, &set);
            big++;
        }
    if (big > 0) sched_setaffinity(0, sizeof set, &set);
    return big;
#else
    return 0;
#endif
}

/* Reads a whole file; NULL on failure. *len gets the number of bytes read. */
static uint8_t *read_file(const char *path, size_t *len) {
    *len = 0;
    if (path == NULL) return NULL;
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long n = ftell(f);
    if (n <= 0) { fclose(f); return NULL; }
    if (fseek(f, 0, SEEK_SET) != 0) { fclose(f); return NULL; }
    uint8_t *b = (uint8_t *)malloc((size_t)n);
    if (b == NULL) { fclose(f); return NULL; }
    size_t got = fread(b, 1, (size_t)n, f);
    fclose(f);
    if (got != (size_t)n) { free(b); return NULL; }
    *len = got;
    return b;
}

static char *dup_utf(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) return NULL;
    char *d = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return d;
}

JNIEXPORT jint JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeVersion(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)tinyasr_version();
}

/* Loads a .tzf pack and silero_vad.onnx; 0 on failure. */
JNIEXPORT jlong JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeLoadPaths(JNIEnv *env, jclass clazz,
                                             jstring modelPath, jstring vadPath,
                                             jint threads) {
    (void)clazz;
    char *mp = dup_utf(env, modelPath);
    char *vp = dup_utf(env, vadPath);
    size_t mn = 0, vn = 0;
    uint8_t *mb = read_file(mp, &mn);
    uint8_t *vb = read_file(vp, &vn);
    free(mp);
    free(vp);
    tinyasr_models *m = NULL;
    if (mb != NULL && vb != NULL) {
        char err[256] = {0};
        m = tinyasr_load(mb, mn, vb, vn, err, sizeof err);
        if (m == NULL) {
            /* The caller shows this in its log. */
            __android_log_print(ANDROID_LOG_ERROR, "tinyasr", "tinyasr_load: %s", err);
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "tinyasr", "cannot read model or VAD file");
    }
    free(mb);
    free(vb);
    if (m != NULL) {
        int big = pin_to_big_cores();
        int t = threads;
        if (t <= 0) t = big > 0 ? big : 2;
        if (t > 4) t = 4;
        tinyasr_set_threads(m, (uint32_t)t);
    }
    return (jlong)(intptr_t)m;
}

JNIEXPORT void JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFree(JNIEnv *env, jclass clazz, jlong m) {
    (void)env; (void)clazz;
    tinyasr_free((tinyasr_models *)(intptr_t)m);
}

JNIEXPORT jint JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeChunkMs(JNIEnv *env, jclass clazz, jlong m) {
    (void)env; (void)clazz;
    return (jint)tinyasr_chunk_ms((const tinyasr_models *)(intptr_t)m);
}

JNIEXPORT jint JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeLangCount(JNIEnv *env, jclass clazz, jlong m) {
    (void)env; (void)clazz;
    return (jint)tinyasr_lang_count((const tinyasr_models *)(intptr_t)m);
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeLang(JNIEnv *env, jclass clazz, jlong m, jint i) {
    (void)clazz;
    const char *c = tinyasr_lang((const tinyasr_models *)(intptr_t)m, (uint32_t)i);
    return c == NULL ? NULL : (*env)->NewStringUTF(env, c);
}

JNIEXPORT jint JNICALL
Java_dev_tinyasr_app_TinyAsr_nativePinFast(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return pin_to_big_cores();
}

JNIEXPORT void JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSetThreads(JNIEnv *env, jclass clazz, jlong m, jint n) {
    (void)env; (void)clazz;
    tinyasr_set_threads((tinyasr_models *)(intptr_t)m, (uint32_t)(n < 1 ? 1 : n));
}

/* ---- streaming ---------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSessionNew(JNIEnv *env, jclass clazz, jlong m,
                                              jstring langs, jstring startLang, jint beam) {
    (void)clazz;
    char *l = dup_utf(env, langs);
    char *s = dup_utf(env, startLang);
    tinyasr_session *session = tinyasr_session_new((const tinyasr_models *)(intptr_t)m,
                                                   l, s, NULL, (uint32_t)beam);
    free(l);
    free(s);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSessionPush(JNIEnv *env, jclass clazz, jlong s,
                                               jfloatArray pcm, jint n) {
    (void)clazz;
    if (s == 0 || pcm == NULL || n <= 0) return NULL;
    jfloat *p = (*env)->GetFloatArrayElements(env, pcm, NULL);
    if (p == NULL) return NULL;
    const char *r = tinyasr_session_push((tinyasr_session *)(intptr_t)s, p, (size_t)n);
    jstring out = r == NULL ? NULL : (*env)->NewStringUTF(env, r);
    (*env)->ReleaseFloatArrayElements(env, pcm, p, JNI_ABORT);
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSessionFinish(JNIEnv *env, jclass clazz, jlong s) {
    (void)clazz;
    if (s == 0) return NULL;
    const char *r = tinyasr_session_finish((tinyasr_session *)(intptr_t)s);
    return r == NULL ? NULL : (*env)->NewStringUTF(env, r);
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSessionLang(JNIEnv *env, jclass clazz, jlong s) {
    (void)clazz;
    if (s == 0) return NULL;
    const char *r = tinyasr_session_lang((const tinyasr_session *)(intptr_t)s);
    return r == NULL ? NULL : (*env)->NewStringUTF(env, r);
}

JNIEXPORT void JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeSessionFree(JNIEnv *env, jclass clazz, jlong s) {
    (void)env; (void)clazz;
    tinyasr_session_free((tinyasr_session *)(intptr_t)s);
}

/* ---- whole file --------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFileNew(JNIEnv *env, jclass clazz, jlong m,
                                           jfloatArray pcm, jint n, jstring langs, jint beam) {
    (void)clazz;
    if (m == 0 || pcm == NULL || n <= 0) return 0;
    jfloat *p = (*env)->GetFloatArrayElements(env, pcm, NULL);
    if (p == NULL) return 0;
    char *l = dup_utf(env, langs);
    tinyasr_file *f = tinyasr_file_new((const tinyasr_models *)(intptr_t)m, p, (size_t)n, l, (uint32_t)beam);
    free(l);
    (*env)->ReleaseFloatArrayElements(env, pcm, p, JNI_ABORT);
    return (jlong)(intptr_t)f;
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFileNext(JNIEnv *env, jclass clazz, jlong f) {
    (void)clazz;
    if (f == 0) return NULL;
    const char *r = tinyasr_file_next((tinyasr_file *)(intptr_t)f);
    return r == NULL ? NULL : (*env)->NewStringUTF(env, r);
}

JNIEXPORT jstring JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFileLang(JNIEnv *env, jclass clazz, jlong f) {
    (void)clazz;
    if (f == 0) return NULL;
    const char *r = tinyasr_file_lang((const tinyasr_file *)(intptr_t)f);
    return r == NULL ? NULL : (*env)->NewStringUTF(env, r);
}

JNIEXPORT jfloat JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFileProgress(JNIEnv *env, jclass clazz, jlong f) {
    (void)env; (void)clazz;
    return (jfloat)tinyasr_file_progress((const tinyasr_file *)(intptr_t)f);
}

JNIEXPORT void JNICALL
Java_dev_tinyasr_app_TinyAsr_nativeFileFree(JNIEnv *env, jclass clazz, jlong f) {
    (void)env; (void)clazz;
    tinyasr_file_free((tinyasr_file *)(intptr_t)f);
}
