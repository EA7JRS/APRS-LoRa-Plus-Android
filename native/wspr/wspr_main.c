/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
 * WSPR decoder program for the app: wsprd (K1JT, K9AN, VA2GKA; GPL-3.0) on a 2-minute slot of USB audio.
 *   libwsprdec.so <slot.wav (12 kHz, 16-bit mono, from the start of the even minute)> [dial Hz]
 * Output, one line per spot: "WSPR <snr> <dt> <freq MHz> <drift> <message>" (dt as WSJT-X: 0 = started 1 s after the minute). The hash table (hashtable.txt) is kept in the
 * current directory, so compound callsigns heard before are resolved. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include "wsprd.h"

#define IN_RATE   12000
#define OUT_RATE  375
#define DECIM     (IN_RATE / OUT_RATE)
#define CENTER_HZ 1500.0
#define TAPS      (8 * DECIM + 1)
#define MAX_OUT   (120 * OUT_RATE)

static int read_wav(const char *path, int16_t **data, int *n) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    unsigned char h[44];
    if (fread(h, 1, 44, f) != 44 || memcmp(h, "RIFF", 4) || memcmp(h + 8, "WAVE", 4)) { fclose(f); return -1; }
    int rate = h[24] | h[25] << 8 | h[26] << 16 | h[27] << 24;
    int bits = h[34] | h[35] << 8, ch = h[22] | h[23] << 8;
    if (rate != IN_RATE || bits != 16 || ch != 1) { fclose(f); return -2; }
    fseek(f, 0, SEEK_END); long len = ftell(f) - 44; fseek(f, 44, SEEK_SET);
    *n = (int)(len / 2);
    *data = (int16_t *)malloc(len);
    *n = (int)fread(*data, 2, *n, f);
    fclose(f);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s slot.wav [dialHz]\n", argv[0]); return 1; }
    int dial = argc > 2 ? atoi(argv[2]) : 0;
    int16_t *pcm; int n;
    int rc = read_wav(argv[1], &pcm, &n);
    if (rc) { fprintf(stderr, "cannot read %s (%d): 12 kHz, 16-bit mono WAV expected\n", argv[1], rc); return 1; }
    /* low-pass for the 375 S/s baseband: windowed sinc, cutoff 150 Hz */
    static float taps[TAPS];
    double sum = 0;
    for (int i = 0; i < TAPS; i++) {
        double x = i - (TAPS - 1) / 2.0, fc = 150.0 / IN_RATE;
        double s = x == 0 ? 2 * fc : sin(2 * M_PI * fc * x) / (M_PI * x);
        double w = 0.42 - 0.5 * cos(2 * M_PI * i / (TAPS - 1)) + 0.08 * cos(4 * M_PI * i / (TAPS - 1));
        taps[i] = (float)(s * w); sum += taps[i];
    }
    for (int i = 0; i < TAPS; i++) taps[i] /= (float)sum;
    static float idat[MAX_OUT], qdat[MAX_OUT];
    int m = 0;
    for (int o = 0; m < MAX_OUT; o += DECIM) {
        if (o + TAPS > n) break;
        double ai = 0, aq = 0;
        for (int k = 0; k < TAPS; k++) {
            int t = o + k;
            double ph = 2 * M_PI * CENTER_HZ * t / IN_RATE;
            double x = pcm[t] / 32768.0 * taps[k];
            ai += x * cos(ph); aq -= x * sin(ph);
        }
        idat[m] = (float)(ai * 2); qdat[m] = (float)(aq * 2); m++;
    }
    free(pcm);
    struct decoder_options opt;
    memset(&opt, 0, sizeof(opt));
    opt.freq = dial;
    opt.usehashtable = 1;
    opt.npasses = 2;
    opt.subtraction = 1;
    opt.quickmode = 0;
    static struct decoder_results res[MAX_UNIQUES];
    int nres = 0;
    wspr_decode(idat, qdat, m, opt, res, &nres);
    for (int i = 0; i < nres; i++)
        printf("WSPR %+.0f %+.1f %.6f %+.0f %s %s %s\n", res[i].snr, res[i].dt + 1.0, res[i].freq, (double)res[i].drift, res[i].call, res[i].loc, res[i].pwr);
    return 0;
}
