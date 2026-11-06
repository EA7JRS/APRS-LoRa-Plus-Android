/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
 * Test only (PC): writes a 2-minute 12 kHz WAV with one WSPR signal ("CALL LOC DBM") at audio 1500+offset Hz, from t = 1 s. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include "wsprd.h"
#include "wsprsim_utils.h"
int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: wspr_gen \"K1JT FN20 30\" out.wav offsetHz [noise]\n"); return 1; }
    char msg[64]; snprintf(msg, sizeof msg, "%s", argv[1]);
    double off = atof(argv[3]), noise = argc > 4 ? atof(argv[4]) : 0.05;
    static char hashtab[HASHTAB_SIZE * HASHTAB_ENTRY_LEN], loctab[HASHTAB_SIZE * LOCTAB_ENTRY_LEN];
    unsigned char sym[162];
    get_wspr_channel_symbols(msg, hashtab, loctab, sym);
    int rate = 12000, n = 120 * rate;
    int16_t *pcm = calloc(n, 2);
    double ph = 0, df = 12000.0 / 8192.0;
    srand(1);
    for (int i = 0; i < n; i++) {
        double t = (double)i / rate - 1.0, v = 0;
        int s = (int)(t / (8192.0 / 12000.0));
        if (t >= 0 && s < 162) { ph += 2 * M_PI * (1500 + off + (sym[s] - 1.5) * df) / rate; v = 0.3 * sin(ph); }
        double u1 = (rand() + 1.0) / (RAND_MAX + 2.0), u2 = rand() / (RAND_MAX + 1.0);
        v += noise * sqrt(-2 * log(u1)) * cos(2 * M_PI * u2);
        pcm[i] = (int16_t)fmax(-32767, fmin(32767, v * 32767));
    }
    FILE *f = fopen(argv[2], "wb");
    unsigned char h[44] = "RIFF";
    int data = n * 2, x;
    memcpy(h + 8, "WAVEfmt ", 8);
    x = 36 + data; memcpy(h + 4, &x, 4); x = 16; memcpy(h + 16, &x, 4);
    short one = 1; memcpy(h + 20, &one, 2); memcpy(h + 22, &one, 2); memcpy(h + 24, &rate, 4); x = rate * 2; memcpy(h + 28, &x, 4);
    short ba = 2, bps = 16; memcpy(h + 32, &ba, 2); memcpy(h + 34, &bps, 2); memcpy(h + 36, "data", 4); memcpy(h + 40, &data, 4);
    fwrite(h, 1, 44, f); fwrite(pcm, 2, n, f); fclose(f);
    return 0;
}
