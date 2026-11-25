/* Drives the live (streaming) path exactly as the app does.
 *
 * Usage: livetest <model.tzf> <silero_vad.onnx> <pcm.f32> <langs> <start_lang>
 *
 * Reads raw f32 16 kHz mono, pushes 1600-sample (100 ms) chunks into a session
 * and prints every event, then finishes. This is the same call sequence as
 * TinyAsr.nativeSessionNew/Push/Finish and MainActivity.onLiveChunk.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "tinyasr.h"

static unsigned char *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb");
    if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); long s = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(s);
    *n = fread(b, 1, s, f);
    fclose(f);
    return b;
}

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: %s model.tzf silero_vad.onnx pcm.f32 langs start_lang\n", argv[0]);
        return 2;
    }
    size_t an, vn;
    unsigned char *asr = slurp(argv[1], &an);
    unsigned char *vad = slurp(argv[2], &vn);
    char err[256] = {0};
    tinyasr_models *m = tinyasr_load(asr, an, vad, vn, err, sizeof err);
    if (!m) { printf("LOAD FAILED: %s\n", err); return 1; }

    size_t bytes = 0;
    unsigned char *raw = slurp(argv[3], &bytes);
    size_t nsamp = bytes / 4;
    float *pcm = (float *)raw;

    printf("model langs:");
    for (uint32_t i = 0; i < tinyasr_lang_count(m); i++) printf(" %s", tinyasr_lang(m, i));
    printf("\nchunk=%u ms  audio=%.2f s  langs=\"%s\" start=\"%s\"\n",
           tinyasr_chunk_ms(m), nsamp / 16000.0, argv[4], argv[5]);

    tinyasr_session *s = tinyasr_session_new(m, argv[4], argv[5], NULL, 0);
    if (!s) { printf("session_new failed\n"); return 1; }

    int text = 0;
    for (size_t off = 0; off < nsamp; off += 1600) {
        size_t n = nsamp - off < 1600 ? nsamp - off : 1600;
        const char *j = tinyasr_session_push(s, pcm + off, n);
        if (j && j[0] && !(j[1] == ']' && j[2] == 0)) {
            printf("  push %5.1fs: %s\n", off / 16000.0, j);
            if (strstr(j, "\"text\"")) text = 1;
        }
    }
    const char *fin = tinyasr_session_finish(s);
    printf("  finish    : %s\n", fin ? fin : "(null)");
    if (fin && strstr(fin, "\"text\"")) text = 1;
    printf("  lang      : %s\n", tinyasr_session_lang(s));
    printf("RESULT: %s\n", text ? "TEXT PRODUCED" : "no text");
    tinyasr_session_free(s);
    tinyasr_free(m);
    free(asr); free(vad); free(raw);
    return text ? 0 : 3;
}
