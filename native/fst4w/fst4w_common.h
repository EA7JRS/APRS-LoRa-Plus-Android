/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * FST4W pieces shared by the decoder and the test generator. C port of parts of WSJT-X 3.3.0-beta5 (K1JT, K9AN and the
 * WSJT-X team; GPL-3.0): get_crc24.f90, encode240_74.f90, get_fst4_tones_from_bits (genfst4.f90), the WSPR type 1/2/3
 * payloads of packjt77 and ihashcall.
 */
#pragma once
#include <stdint.h>
#include <string.h>
#include "ldpc_240_74.h"

#define FST4_N 240
#define FST4_K 74
#define FST4_M 166
#define FST4_NN 160
#define PACK77_NTOKENS 2063592
#define PACK77_MAX22 4194304
#define PACK77_WSPR_NZZZ 46656

static const int FST4_SYNC1[8] = {0, 1, 3, 2, 1, 0, 2, 3};
static const int FST4_SYNC2[8] = {2, 3, 1, 0, 3, 2, 0, 1};

/* Remainder of bits[0..n) divided by x^24 + 0x65B (polynomial 0x100065b). With the last 24 bits zero it is the CRC;
 * over message + CRC it is 0 when they agree (get_crc24). */
static uint32_t fst4_crc24(const uint8_t *bits, int n) {
    uint32_t reg = 0;
    for (int i = 0; i < n; i++) {
        uint32_t top = (reg >> 23) & 1u;
        reg = ((reg << 1) | (bits[i] & 1u)) & 0xFFFFFFu;
        if (top) reg ^= 0x00065Bu;
    }
    return reg;
}

static uint8_t fst4_gen[FST4_M][FST4_K];
static int fst4_gen_ready = 0;

static void fst4_gen_init(void) {
    if (fst4_gen_ready) return;
    for (int i = 0; i < FST4_M; i++)
        for (int j = 0; j < 19; j++) {
            char c = FST4_GEN[i][j];
            int v = (c >= '0' && c <= '9') ? c - '0' : c - 'a' + 10;
            int nb = (j == 18) ? 2 : 4;
            for (int b = 0; b < nb; b++) fst4_gen[i][j * 4 + b] = (uint8_t)((v >> (3 - b)) & 1);
        }
    fst4_gen_ready = 1;
}

/* encode240_74: codeword = message (74) + 166 parity bits. */
static void fst4_encode(const uint8_t m[FST4_K], uint8_t cw[FST4_N]) {
    fst4_gen_init();
    memcpy(cw, m, FST4_K);
    for (int i = 0; i < FST4_M; i++) {
        int s = 0;
        for (int j = 0; j < FST4_K; j++) s ^= m[j] & fst4_gen[i][j];
        cw[FST4_K + i] = (uint8_t)s;
    }
}

/* 50 message bits -> 160 tones (CRC24, LDPC, Gray map, five 8-symbol sync words). */
static void fst4_tones(const uint8_t msg50[50], int tones[FST4_NN]) {
    uint8_t m[FST4_K] = {0}, cw[FST4_N];
    memcpy(m, msg50, 50);
    uint32_t crc = fst4_crc24(m, FST4_K);
    for (int i = 0; i < 24; i++) m[50 + i] = (uint8_t)((crc >> (23 - i)) & 1u);
    fst4_encode(m, cw);
    int d[120];
    for (int i = 0; i < 120; i++) {
        int s = cw[2 * i + 1] + 2 * cw[2 * i];
        d[i] = s <= 1 ? s : (s == 2 ? 3 : 2);
    }
    int k = 0;
    for (int b = 0; b < 4; b++) {
        const int *s = (b % 2 == 0) ? FST4_SYNC1 : FST4_SYNC2;
        for (int i = 0; i < 8; i++) tones[k++] = s[i];
        for (int i = 0; i < 30; i++) tones[k++] = d[b * 30 + i];
    }
    for (int i = 0; i < 8; i++) tones[k++] = FST4_SYNC1[i];
}

/* ihashcall(c13, m): top m bits of 47055833459 * (callsign in base 38, 11 characters). */
static int fst4_hash(const char *call, int m) {
    static const char B38[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";
    uint64_t n8 = 0;
    size_t len = strlen(call);
    for (int i = 0; i < 11; i++) {
        char c = i < (int)len ? call[i] : ' ';
        const char *p = strchr(B38, c);
        int j = (p && c) ? (int)(p - B38) : -1;
        n8 = 38u * n8 + (uint64_t)(int64_t)j;
    }
    return (int)((n8 * 47055833459ULL) >> (64 - m));
}

static uint32_t fst4_bits(const uint8_t *b, int start1, int width) {  /* 1-based start, MSB first */
    uint32_t v = 0;
    for (int i = 0; i < width; i++) v = (v << 1) | b[start1 - 1 + i];
    return v;
}

/* Standard callsign -> n28 (pack28 for standard calls only); -1 if it is not one. */
static int32_t fst4_pack28(const char *call) {
    static const char C1[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", C2[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                      C4[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    char c6[7] = "      ";
    size_t n = strlen(call);
    if (n < 3 || n > 6) return -1;
    if (call[2] >= '0' && call[2] <= '9') memcpy(c6, call, n);
    else if (call[1] >= '0' && call[1] <= '9' && n <= 5) memcpy(c6 + 1, call, n);
    else return -1;
    const char *p;
    int i1, i2, i3, i4, i5, i6;
    if (!(p = strchr(C1, c6[0])) || !c6[0]) return -1;
    i1 = (int)(p - C1);
    if (!(p = strchr(C2, c6[1])) || !c6[1]) return -1;
    i2 = (int)(p - C2);
    if (c6[2] < '0' || c6[2] > '9') return -1;
    i3 = c6[2] - '0';
    if (!(p = strchr(C4, c6[3])) || !c6[3]) return -1;
    i4 = (int)(p - C4);
    if (!(p = strchr(C4, c6[4])) || !c6[4]) return -1;
    i5 = (int)(p - C4);
    if (!(p = strchr(C4, c6[5])) || !c6[5]) return -1;
    i6 = (int)(p - C4);
    int32_t v = ((((i1 * 36 + i2) * 10 + i3) * 27 + i4) * 27 + i5) * 27 + i6;
    return v + PACK77_NTOKENS + PACK77_MAX22;
}
