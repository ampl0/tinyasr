/* Does a silence tail recover committed text from a short live session?
 *
 * Mirrors the app's new stopLive(): push the audio, then optionally push
 * silence, then finish, and report what came out.
 *
 * usage: tailtest model.tzf silero_vad.onnx pcm.f32 langs seconds tail_seconds
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

static int run(tinyasr_models *m, const char *f32, const char *langs,
               double seconds, double tail) {
    size_t bn;
    unsigned char *raw = slurp(f32, &bn);
    float *pcm = (float *)raw;
    size_t nsamp = bn / 4;
    /* how much speech to feed (0 = all) */
    if (seconds > 0 && (size_t)(seconds * 16000) < nsamp) nsamp = (size_t)(seconds * 16000);

    tinyasr_session *s = tinyasr_session_new(m, langs, langs, NULL, 0);
    if (!s) { printf("session_new failed\n"); return -1; }
    int text = 0;
    for (size_t off = 0; off < nsamp; off += 1600) {
        size_t n = nsamp - off < 1600 ? nsamp - off : 1600;
        const char *j = tinyasr_session_push(s, pcm + off, n);
        if (j && strstr(j, "\"commit\"")) text = 1;
    }
    int silence_chunks = (int)(tail * 10);       /* 100 ms each */
    if (silence_chunks > 0) {
        float *sil = calloc(1600, sizeof(float));
        for (int i = 0; i < silence_chunks; i++) {
            const char *j = tinyasr_session_push(s, sil, 1600);
            if (j && strstr(j, "\"commit\"")) text = 1;
        }
        free(sil);
    }
    const char *fin = tinyasr_session_finish(s);
    if (fin && strstr(fin, "\"commit\"")) text = 1;
    tinyasr_session_free(s);
    free(raw);
    return text;
}

int main(int argc, char **argv) {
    size_t an, vn;
    unsigned char *asr = slurp(argv[1], &an);
    unsigned char *vad = slurp(argv[2], &vn);
    char err[256] = {0};
    tinyasr_models *m = tinyasr_load(asr, an, vad, vn, err, sizeof err);
    if (!m) { printf("LOAD FAILED: %s\n", err); return 1; }

    for (double secs = 0.5; secs <= 4.01; secs += 0.5) {
        int no_tail = run(m, argv[3], argv[4], secs, 0);
        int tail    = run(m, argv[3], argv[4], secs, 1.6);
        printf("%4.1fs of audio:  no-tail=%-7s  1.6s-tail=%-7s\n",
               secs, no_tail ? "TEXT" : "none", tail ? "TEXT" : "none");
    }
    tinyasr_free(m);
    return 0;
}
