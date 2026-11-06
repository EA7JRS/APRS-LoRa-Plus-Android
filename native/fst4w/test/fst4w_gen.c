/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * Test signal for the FST4W decoder (host only): one or more FST4W transmissions in Gaussian noise, 12 kHz mono WAV.
 * Waveform as gen_fst4wave.f90 of WSJT-X (GFSK, BT 2, h = 1, ramped ends).
 *   fst4w_gen out.wav period seed noise(0/1) "CALL GRID DBM|<CALL> GRID6" f0 snr dt [more message f0 snr dt ...]
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../fst4w_common.h"

static double gauss(void) {
    double u = (rand() + 1.0) / (RAND_MAX + 2.0), v = (rand() + 1.0) / (RAND_MAX + 2.0);
    return sqrt(-2 * log(u)) * cos(2 * M_PI * v);
}

static void put(uint8_t *b, int start1, int width, uint32_t v) {
    for (int i = 0; i < width; i++) b[start1 - 1 + i] = (uint8_t)((v >> (width - 1 - i)) & 1u);
}

static int pack(const char *text, uint8_t b[50]) {
    char a[32], g[16]; int dbm = 0;
    memset(b, 0, 50);
    int n = sscanf(text, "%31s %15s %d", a, g, &dbm);
    if (n == 2 && a[0] == '<' && strlen(g) == 6) {                       /* type 3 */
        char call[16]; snprintf(call, sizeof call, "%.*s", (int)strlen(a) - 2, a + 1);
        uint32_t n22 = (uint32_t)fst4_hash(call, 22);
        uint32_t ig = (uint32_t)(g[0] - 'A') * 18 * 10 * 10 * 25 * 25 + (uint32_t)(g[1] - 'A') * 10 * 10 * 25 * 25 +
                      (uint32_t)(g[2] - '0') * 10 * 25 * 25 + (uint32_t)(g[3] - '0') * 25 * 25 + (uint32_t)(g[4] - 'A') * 25 + (uint32_t)(g[5] - 'A');
        put(b, 1, 22, n22); put(b, 23, 25, ig); put(b, 48, 3, 2);
        return 1;
    }
    if (n != 3 || strlen(g) != 4) return 0;
    int32_t n28 = fst4_pack28(a);
    if (n28 < 0) return 0;
    uint32_t ig = (uint32_t)(g[0] - 'A') * 1800 + (uint32_t)(g[1] - 'A') * 100 + (uint32_t)(g[2] - '0') * 10 + (uint32_t)(g[3] - '0');
    put(b, 1, 28, (uint32_t)n28); put(b, 29, 15, ig); put(b, 44, 5, (uint32_t)lround(0.3 * dbm));
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 9 || (argc - 5) % 4) { fprintf(stderr, "usage: %s out.wav period seed noise msg f0 snr dt [msg f0 snr dt ...]\n", argv[0]); return 1; }
    int ntr = atoi(argv[2]), nsps = ntr == 120 ? 8200 : ntr == 300 ? 21504 : ntr == 900 ? 66560 : 134400;
    srand((unsigned)atoi(argv[3]));
    int noise = atoi(argv[4]), nmax = ntr * 12000;
    double *w = calloc(nmax, sizeof(double));
    const double c = M_PI * sqrt(2.0 / log(2.0));
    double *pulse = malloc(sizeof(double) * 3 * nsps);
    for (int i = 0; i < 3 * nsps; i++) {
        double t = (i - 1.5 * nsps) / nsps;
        pulse[i] = 0.5 * (erf(c * 2.0 * (t + 0.5)) - erf(c * 2.0 * (t - 0.5)));
    }
    double sigma = 1.0;                                                  /* noise per sample */
    for (int a = 5; a < argc; a += 4) {
        uint8_t b[50];
        if (!pack(argv[a], b)) { fprintf(stderr, "bad message %s\n", argv[a]); return 1; }
        int tones[FST4_NN];
        fst4_tones(b, tones);
        double f0 = atof(argv[a + 1]), snr = atof(argv[a + 2]), dt = atof(argv[a + 3]);
        /* SNR in 2500 Hz: amplitude A gives power A^2/2; noise power in 2500 Hz = sigma^2 * 2500 / 6000 */
        double amp = sqrt(2.0 * sigma * sigma * 2500.0 / 6000.0 * pow(10.0, snr / 10.0));
        double baud = 12000.0 / nsps, dphi_peak = 2 * M_PI / nsps, carrier = 2 * M_PI * (f0 - 1.5 * baud) / 12000.0, phi = 0;
        int start = (int)lround((1.0 + dt) * 12000), total = FST4_NN * nsps;
        double *dphi = malloc(sizeof(double) * nsps);
        int k = 0;
        for (int ib = 0; ib < FST4_NN; ib++) {
            for (int j = 0; j < nsps; j++) dphi[j] = carrier;
            for (int s = ib - 1; s <= ib + 1; s++) {
                if (s < 0 || s >= FST4_NN) continue;
                int ip = (ib - s + 1) * nsps;
                for (int j = 0; j < nsps; j++) dphi[j] += dphi_peak * pulse[ip + j] * tones[s];
            }
            for (int j = 0; j < nsps; j++, k++) {
                double env = 1.0;
                if (k < nsps / 4) env = (1 - cos(2 * M_PI * k / (nsps / 2.0))) / 2;
                int k1 = (FST4_NN - 1) * nsps + 3 * nsps / 4;
                if (k >= k1) env = (1 + cos(2 * M_PI * (k - k1) / (nsps / 2.0))) / 2;
                int o = start + k;
                if (o >= 0 && o < nmax && k < total) w[o] += amp * env * sin(phi);
                phi += dphi[j];
                if (phi > 2 * M_PI) phi -= 2 * M_PI;
            }
        }
        free(dphi);
    }
    FILE *f = fopen(argv[1], "wb");
    if (!f) return 1;
    uint32_t data = (uint32_t)nmax * 2, riff = 36 + data;
    unsigned char h[44] = {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,1,0,0xE0,0x2E,0,0,0xC0,0x5D,0,0,2,0,16,0,'d','a','t','a'};
    for (int i = 0; i < 4; i++) { h[4 + i] = (unsigned char)(riff >> (8 * i)); h[40 + i] = (unsigned char)(data >> (8 * i)); }
    fwrite(h, 1, 44, f);
    double scale = 300.0;
    for (int i = 0; i < nmax; i++) {
        double v = (w[i] + (noise ? sigma * gauss() : 0)) * scale;
        if (v > 32767) v = 32767;
        if (v < -32767) v = -32767;
        int16_t s = (int16_t)lround(v);
        fwrite(&s, 2, 1, f);
    }
    fclose(f);
    return 0;
}
