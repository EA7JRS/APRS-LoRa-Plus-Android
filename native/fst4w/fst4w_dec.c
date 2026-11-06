/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * FST4W decoder: C port of the FST4W path of WSJT-X 3.3.0-beta5 (lib/fst4_decode.f90 and lib/fst4/: decode240_74,
 * fastosd240_74, get_fst4_bitmetrics, fst4_baseline; K1JT, K9AN and the WSJT-X team; GPL-3.0). FFTW is replaced by KISS FFT.
 * No noise blanker, no Doppler spread, no AP decoding (FST4W has none).
 *
 *   libfst4wdec.so <wav 12 kHz mono 16 bit> [dialHz] [periodSec] [rxHz] [tolHz] [stateFile]
 *
 * One line per decode: "FST4W snr dt freqMHz 0 message" (freqMHz = (dial + lowest tone) / 1e6; same layout as the WSPR
 * decoder). stateFile keeps the 22-bit hashes of the calls heard (type 3 messages) and the call + locator pairs that allow
 * the deeper Keff=50 decoding, as WSJT-X does with fst4w_calls.txt.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "kiss_fft.h"
#include "kiss_fftr.h"
#include "fst4w_common.h"

typedef struct { float r, i; } cpx;

#define NN FST4_NN
#define N FST4_N

static inline cpx cmul(cpx a, cpx b) { cpx c = {a.r * b.r - a.i * b.i, a.r * b.i + a.i * b.r}; return c; }
static inline cpx cmulc(cpx a, cpx b) { cpx c = {a.r * b.r + a.i * b.i, a.i * b.r - a.r * b.i}; return c; }  /* a*conj(b) */
static inline float cabs2(cpx a) { return a.r * a.r + a.i * a.i; }
static inline float cabsf_(cpx a) { return sqrtf(cabs2(a)); }

/* ------------------------------------------------------------------ state file (hashes, known calls) */

#define MAXHASH 1000
#define MAXWCALLS 100
static int nhash = 0, hashv[MAXHASH];
static char hashc[MAXHASH][24];
static int nwcalls = 0;
static char wcalls[MAXWCALLS][48];
static int state_dirty = 0;

static void load_state(const char *path) {
    FILE *f = path ? fopen(path, "r") : NULL;
    if (!f) return;
    char line[128];
    while (fgets(line, sizeof line, f)) {
        int h; char a[24], b[24];
        if (sscanf(line, "H %d %23s", &h, a) == 2 && nhash < MAXHASH) { hashv[nhash] = h; strcpy(hashc[nhash++], a); }
        else if (sscanf(line, "W %13s %8s", a, b) == 2 && nwcalls < MAXWCALLS) snprintf(wcalls[nwcalls++], 48, "%s %s", a, b);
    }
    fclose(f);
}

static void save_state(const char *path) {
    if (!path || !state_dirty) return;
    FILE *f = fopen(path, "w");
    if (!f) return;
    for (int i = 0; i < nhash; i++) fprintf(f, "H %d %s\n", hashv[i], hashc[i]);
    for (int i = 0; i < nwcalls; i++) fprintf(f, "W %s\n", wcalls[i]);
    fclose(f);
}

static void save_hash(const char *call) {
    int h = fst4_hash(call, 22);
    for (int i = 0; i < nhash; i++) if (hashv[i] == h) { if (strcmp(hashc[i], call)) { strcpy(hashc[i], call); state_dirty = 1; } return; }
    if (nhash == MAXHASH) { memmove(hashv, hashv + 1, sizeof(int) * (MAXHASH - 1)); memmove(hashc, hashc + 1, 24 * (MAXHASH - 1)); nhash--; }
    hashv[nhash] = h; snprintf(hashc[nhash++], 24, "%s", call); state_dirty = 1;
}

static void save_wcall(const char *callgrid) {
    for (int i = 0; i < nwcalls; i++) if (!strcmp(wcalls[i], callgrid)) return;
    if (nwcalls == MAXWCALLS) { memmove(wcalls, wcalls + 1, 48 * (MAXWCALLS - 1)); nwcalls--; }
    snprintf(wcalls[nwcalls++], 48, "%s", callgrid); state_dirty = 1;
}

/* ------------------------------------------------------------------ unpack (i3=0, n3=6: WSPR types 1, 2, 3) */

static int callok(const char *w) {
    int n = (int)strlen(w), i;
    if (n < 3 || w[0] == 'Q') return 0;
    for (i = n - 1; i >= 0; i--) if (w[i] >= '0' && w[i] <= '9') break;
    int i0 = i + 1;                                                      /* 1-based position of the call area digit */
    if (i0 != 2 && i0 != 3) return 0;
    return 1;
}

/* unpack28 for the WSPR payloads: 1 if OK. Tokens (CQ, DE...) are not accepted here. */
static int unpack28(uint32_t n28, char *out) {
    static const char C1[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", C2[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                      C4[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    if (n28 < PACK77_NTOKENS) return 0;
    uint32_t n = n28 - PACK77_NTOKENS;
    if (n < PACK77_MAX22) {
        for (int i = 0; i < nhash; i++) if (hashv[i] == (int)n) { snprintf(out, 24, "<%s>", hashc[i]); return 1; }
        strcpy(out, "<...>");
        return 1;
    }
    n -= PACK77_MAX22;
    int i1 = n / (36 * 10 * 27 * 27 * 27); n -= 36 * 10 * 27 * 27 * 27 * i1;
    int i2 = n / (10 * 27 * 27 * 27); n -= 10 * 27 * 27 * 27 * i2;
    int i3 = n / (27 * 27 * 27); n -= 27 * 27 * 27 * i3;
    int i4 = n / (27 * 27); n -= 27 * 27 * i4;
    int i5 = n / 27; int i6 = n - 27 * i5;
    if (i1 > 36 || i2 > 35 || i3 > 9) return 0;
    char c[7] = {C1[i1], C2[i2], (char)('0' + i3), C4[i4], C4[i5], C4[i6], 0};
    char *s = c; while (*s == ' ') s++;
    int len = (int)strlen(s); while (len > 0 && s[len - 1] == ' ') s[--len] = 0;
    for (int k = 0; k < len; k++) if (s[k] == ' ') return 0;
    if (!callok(s)) return 0;
    strcpy(out, s);
    return 1;
}

/* 50 decoded bits -> text; *type = 1, 2 or 3; hashcall: the call to remember for type 3 messages; callgrid: call and
 * locator of type 1 for the known-calls list. Nothing is stored here: only once the decode is accepted. */
static int unpack50(const uint8_t *b, char *msg, int *type, char *callgrid, char *hashcall) {
    static const char B36[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    char call[24], grid[24];
    callgrid[0] = 0; hashcall[0] = 0;
    if (b[49]) {                                                         /* j50 = 1: type 2 (prefix / suffix) */
        uint32_t n28 = fst4_bits(b, 1, 28), npfx = fst4_bits(b, 29, 16), idbm = fst4_bits(b, 45, 5);
        if (idbm > 18 || !unpack28(n28, call) || call[0] == '<') return 0;
        char p[4] = {0}; int isp = npfx < PACK77_WSPR_NZZZ;
        if (isp) {
            int n = (int)npfx; char t[4] = "   ";
            for (int i = 2; i >= 0; i--) { t[i] = B36[n % 36]; n /= 36; if (n == 0) break; }
            int k = 0; for (int i = 0; i < 3; i++) if (t[i] != ' ') p[k++] = t[i];
        } else {
            int n = (int)npfx - PACK77_WSPR_NZZZ;
            if (n <= 35) { p[0] = B36[n]; }
            else if (n <= 1295) { p[0] = B36[n / 36]; p[1] = B36[n % 36]; }
            else if (n <= 12959) { p[0] = B36[n / 360]; p[1] = B36[(n / 10) % 36]; p[2] = B36[n % 10]; }
            else return 0;
        }
        char full[40];
        if (isp) snprintf(full, sizeof full, "%s/%s", p, call); else snprintf(full, sizeof full, "%s/%s", call, p);
        sprintf(msg, "%s %d", full, (int)lround(idbm * 10.0 / 3.0));
        snprintf(hashcall, 24, "%.23s", full);
        *type = 2;
        return 1;
    }
    if (!b[48]) {                                                        /* j49 = 0: type 1 */
        uint32_t n28 = fst4_bits(b, 1, 28), ig = fst4_bits(b, 29, 15), idbm = fst4_bits(b, 44, 5);
        if (idbm > 18 || !unpack28(n28, call) || call[0] == '<') return 0;
        int j1 = ig / 1800, r = ig - j1 * 1800, j2 = r / 100; r -= j2 * 100;
        if (j1 > 17 || j2 > 17) return 0;
        sprintf(grid, "%c%c%d%d", 'A' + j1, 'A' + j2, r / 10, r % 10);
        sprintf(msg, "%s %s %d", call, grid, (int)lround(idbm * 10.0 / 3.0));
        snprintf(hashcall, 24, "%s", call);
        if (!strchr(call, '/')) snprintf(callgrid, 48, "%s %s", call, grid);
        *type = 1;
        return 1;
    }
    if (fst4_bits(b, 48, 3) != 2) return 0;                              /* type 3: hashed call + 6-character locator */
    uint32_t n22 = fst4_bits(b, 1, 22), g = fst4_bits(b, 23, 25);
    if (!unpack28(n22 + PACK77_NTOKENS, call)) return 0;
    int j1 = g / (18 * 10 * 10 * 25 * 25); g -= j1 * 18 * 10 * 10 * 25 * 25;
    int j2 = g / (10 * 10 * 25 * 25); g -= j2 * 10 * 10 * 25 * 25;
    int j3 = g / (10 * 25 * 25); g -= j3 * 10 * 25 * 25;
    int j4 = g / (25 * 25); g -= j4 * 25 * 25;
    int j5 = g / 25, j6 = g - j5 * 25;
    if (j1 > 17 || j2 > 17 || j3 > 9 || j4 > 9 || j5 > 24 || j6 > 24) return 0;
    if (j5 != 24 || j6 != 24) sprintf(grid, "%c%c%d%d%c%c", 'A' + j1, 'A' + j2, j3, j4, 'A' + j5, 'A' + j6);
    else sprintf(grid, "%c%c%d%d", 'A' + j1, 'A' + j2, j3, j4);
    sprintf(msg, "%s %s", call, grid);
    *type = 3;
    return 1;
}

/* ------------------------------------------------------------------ LDPC(240,74): BP + ordered statistics */

static float platanh(float x) {
    int s = x < 0 ? -1 : 1;
    float z = fabsf(x);
    if (z <= 0.664f) return x / 0.83f;
    if (z <= 0.9217f) return s * (z - 0.4064f) / 0.322f;
    if (z <= 0.9951f) return s * (z - 0.8378f) / 0.0524f;
    if (z <= 0.9998f) return s * (z - 0.9914f) / 0.0012f;
    return s * 7.0f;
}

static int crc_ok74(const uint8_t *m74) { return fst4_crc24(m74, 74) == 0; }

/* generator rows of the code with the first k-50 CRC bits free (fastosd240_74) */
static uint8_t osd_gen[2][FST4_K][N];
static int osd_gen_k[2] = {0, 0};

static uint8_t (*gen_for(int k))[N] {
    int slot = (k == 66) ? 0 : 1;
    if (osd_gen_k[slot] != k) {
        for (int i = 0; i < k; i++) {
            uint8_t m[FST4_K] = {0};
            m[i] = 1;
            if (i < 50) {
                uint32_t c = fst4_crc24(m, 74);
                for (int j = 0; j < 24; j++) m[50 + j] = (uint8_t)((c >> (23 - j)) & 1u);
                for (int j = 50; j < k; j++) m[j] = 0;
            }
            fst4_encode(m, osd_gen[slot][i]);
        }
        osd_gen_k[slot] = k;
    }
    return osd_gen[slot];
}

static void nextpat(uint8_t *mi, int k, int iorder, int *iflag) {
    int ind = -1;
    for (int i = 0; i < k - 1; i++) if (mi[i] == 0 && mi[i + 1] == 1) ind = i;
    if (ind < 0) { *iflag = -1; return; }
    uint8_t ms[FST4_K];
    memset(ms, 0, sizeof ms);
    memcpy(ms, mi, ind);
    ms[ind] = 1; ms[ind + 1] = 0;
    if (ind + 1 < k - 1) {
        int s = 0; for (int i = 0; i < k; i++) s += ms[i];
        int nz = iorder - s;
        for (int i = k - nz; i < k; i++) ms[i] = 1;
    }
    memcpy(mi, ms, k);
    for (int i = 0; i < k; i++) if (mi[i]) { *iflag = i; break; }
}

static const float *absrx_sort;
static int cmp_abs(const void *a, const void *b) {
    float x = absrx_sort[*(const int *)a], y = absrx_sort[*(const int *)b];
    return (x < y) ? -1 : (x > y);
}

/* fastosd240_74: returns nhardmin (<0: CRC failed); cw and message74 out. */
static int fastosd(const float *llr, int k, int ndeep, uint8_t *message74, uint8_t *cw, float *dminout) {
    uint8_t (*gen)[N] = gen_for(k);
    static uint8_t genmrb[FST4_K][N], g2[N][FST4_K];
    int indices[N], indices2[N], indx[N];
    uint8_t hdec[N], hdeco[N], c0[N], ce[N], m0[FST4_K], me[FST4_K], mi[FST4_K], cwb[N];
    float absrx[N], absr0[N];
    int nhardmin = 0;
    float dmin = 0;
    const int nswap = 20, np = 32;
    for (int ibasis = 1; ibasis <= 2; ibasis++) {
        for (int i = 0; i < N; i++) { hdeco[i] = llr[i] >= 0; absr0[i] = fabsf(llr[i]); indx[i] = i; }
        absrx_sort = absr0;
        qsort(indx, N, sizeof(int), cmp_abs);
        for (int i = 0; i < N; i++) {
            int c = indx[N - 1 - i];
            for (int j = 0; j < k; j++) genmrb[j][i] = gen[j][c];
            indices[i] = c;
        }
        if (ibasis == 2)
            for (int i = k - nswap; i < k; i++) {
                for (int j = 0; j < k; j++) { uint8_t t = genmrb[j][i]; genmrb[j][i] = genmrb[j][i + nswap]; genmrb[j][i + nswap] = t; }
                int t = indices[i]; indices[i] = indices[i + nswap]; indices[i + nswap] = t;
            }
        int icol = 0, nskipped = 0;
        for (int i = 0; i < N; i++) indices2[i] = -1;
        for (int id = 0; id < k; id++) {
            int iflag = 0;
            while (!iflag) {
                if (icol >= N) return -1;
                if (genmrb[id][icol] != 1) {
                    for (int j = id + 1; j < k; j++)
                        if (genmrb[j][icol] == 1) {
                            uint8_t t[N];
                            memcpy(t, genmrb[id], N); memcpy(genmrb[id], genmrb[j], N); memcpy(genmrb[j], t, N);
                            iflag = 1;
                        }
                    if (!iflag) { indices2[k + nskipped] = icol; nskipped++; icol++; }
                } else iflag = 1;
            }
            indices2[id] = icol;
            for (int j = 0; j < k; j++)
                if (j != id && genmrb[j][icol] == 1)
                    for (int s = 0; s < N; s++) genmrb[j][s] ^= genmrb[id][s];
            icol++;
        }
        for (int i = k + nskipped; i < N; i++) indices2[i] = i;
        for (int i = 0; i < k; i++) {
            uint8_t t[N];
            memcpy(t, genmrb[i], N);
            for (int j = 0; j < N; j++) genmrb[i][j] = t[indices2[j]];
        }
        int tmpi[N];
        for (int i = 0; i < N; i++) tmpi[i] = indices[indices2[i]];
        memcpy(indices, tmpi, sizeof tmpi);
        for (int i = 0; i < k; i++) for (int j = 0; j < N; j++) g2[j][i] = genmrb[i][j];

        for (int i = 0; i < N; i++) { hdec[i] = hdeco[indices[i]]; absrx[i] = absr0[indices[i]]; }
        memcpy(m0, hdec, k);
        memset(c0, 0, N);
        for (int i = 0; i < k; i++) if (m0[i]) for (int j = 0; j < N; j++) c0[j] ^= g2[j][i];
        nhardmin = 0; dmin = 0;
        for (int j = 0; j < N; j++) { int x = c0[j] ^ hdec[j]; nhardmin += x; dmin += x * absrx[j]; }
        memcpy(cwb, c0, N);

        int nord = 0, nsyndmax = 0;
        float xlambda = 0;
        if (ndeep > 4) ndeep = 4;
        if (ndeep == 1) { nord = 1; xlambda = 0; nsyndmax = np; }
        else if (ndeep == 2) { nord = 2; xlambda = 0; nsyndmax = np; }
        else if (ndeep == 3) { nord = 3; xlambda = 4.0f; nsyndmax = 11; }
        else if (ndeep == 4) { nord = 4; xlambda = 3.4f; nsyndmax = 12; }
        if (ndeep > 0) {
            float s1 = 0, s2 = 0;
            for (int i = 0; i < k; i++) s1 += absrx[i];
            for (int i = k; i < N; i++) s2 += absrx[i];
            float rho = s1 / (s1 + xlambda * s2), rhodmin = rho * dmin;
            for (int iorder = 1; iorder <= nord; iorder++) {
                memset(mi, 0, k);
                for (int i = k - iorder; i < k; i++) mi[i] = 1;
                int iflag = k - iorder;
                while (iflag >= 0) {
                    float d1 = 0;
                    for (int i = 0; i < k; i++) { me[i] = m0[i] ^ mi[i]; if (mi[i]) d1 += absrx[i]; }
                    if (d1 > rhodmin) break;
                    uint8_t sp[32] = {0};
                    for (int i = 0; i < k; i++) if (me[i]) for (int j = 0; j < np; j++) sp[j] ^= g2[k - 1 + j][i];
                    int nwhsp = 0;
                    for (int j = 0; j < np; j++) nwhsp += sp[j] ^ hdec[k - 1 + j];
                    if (nwhsp <= nsyndmax) {
                        memset(ce, 0, N);
                        for (int i = 0; i < k; i++) if (me[i]) for (int j = 0; j < N; j++) ce[j] ^= g2[j][i];
                        float dd = 0; int nh = 0;
                        for (int j = 0; j < N; j++) { int x = ce[j] ^ hdec[j]; nh += x; dd += x * absrx[j]; }
                        if (dd < dmin) { dmin = dd; rhodmin = rho * dmin; memcpy(cwb, ce, N); nhardmin = nh; }
                    }
                    nextpat(mi, k, iorder, &iflag);
                }
            }
        }
        for (int i = 0; i < N; i++) cw[indices[i]] = cwb[i];
        memcpy(message74, cw, 74);
        if (crc_ok74(message74)) break;
        nhardmin = -nhardmin;
    }
    *dminout = dmin;
    return nhardmin;
}

/* decode240_74: returns nharderror (>=0 decoded, with message74 / cw), -1 failed. */
static int decode240_74(const float *llr, int keff, int maxosd, int norder, uint8_t *message74, uint8_t *cw) {
    const int M = FST4_M;
    static float tov[N][3], toc[FST4_M][5], tanhtoc[FST4_M][5], zsave[3][N];
    float zn[N], zsum[N];
    int maxiterations = (keff == 50) ? 1 : 30, nosd = 0;
    if (maxosd > 3) maxosd = 3;
    if (maxosd == 0) { nosd = 1; memcpy(zsave[0], llr, sizeof(float) * N); }
    else if (maxosd > 0) nosd = maxosd;
    if (maxosd != 0) {
        memset(tov, 0, sizeof tov); memset(toc, 0, sizeof toc); memset(tanhtoc, 0, sizeof tanhtoc);
        for (int j = 0; j < M; j++) for (int i = 0; i < FST4_nrw[j]; i++) toc[j][i] = llr[FST4_Nm[j][i] - 1];
        int ncnt = 0, nclast = 0;
        memset(zsum, 0, sizeof zsum);
        for (int iter = 0; iter <= maxiterations; iter++) {
            for (int i = 0; i < N; i++) { zn[i] = llr[i] + tov[i][0] + tov[i][1] + tov[i][2]; zsum[i] += zn[i]; }
            if (iter > 0 && iter <= maxosd) memcpy(zsave[iter - 1], zsum, sizeof zsum);
            for (int i = 0; i < N; i++) cw[i] = zn[i] > 0;
            int ncheck = 0;
            for (int i = 0; i < M; i++) {
                int s = 0;
                for (int j = 0; j < FST4_nrw[i]; j++) s += cw[FST4_Nm[i][j] - 1];
                if (s & 1) ncheck++;
            }
            if (ncheck == 0 && crc_ok74(cw)) {
                memcpy(message74, cw, 74);
                int nh = 0;
                for (int i = 0; i < N; i++) nh += (llr[i] >= 0) != cw[i];
                return nh;
            }
            if (iter > 0) {
                int nd = ncheck - nclast;
                if (nd < 0) ncnt = 0; else ncnt++;
                if (ncnt >= 5 && iter >= 10 && ncheck > 15) break;
            }
            nclast = ncheck;
            for (int j = 0; j < M; j++)
                for (int i = 0; i < FST4_nrw[j]; i++) {
                    int ibj = FST4_Nm[j][i] - 1;
                    toc[j][i] = zn[ibj];
                    for (int kk = 0; kk < 3; kk++) if (FST4_Mn[ibj][kk] - 1 == j) toc[j][i] -= tov[ibj][kk];
                }
            for (int i = 0; i < M; i++) for (int q = 0; q < 5; q++) tanhtoc[i][q] = tanhf(-toc[i][q] / 2);
            for (int j = 0; j < N; j++)
                for (int i = 0; i < 3; i++) {
                    int ichk = FST4_Mn[j][i] - 1;
                    float t = 1.0f;
                    for (int q = 0; q < FST4_nrw[ichk]; q++) if (FST4_Nm[ichk][q] - 1 != j) t *= tanhtoc[ichk][q];
                    tov[j][i] = 2 * platanh(-t);
                }
        }
    }
    for (int i = 0; i < nosd; i++) {
        float dmin;
        int nh = fastosd(zsave[i], keff, norder, message74, cw, &dmin);
        if (nh > 0) {
            int n = 0;
            for (int j = 0; j < N; j++) n += (llr[j] >= 0) != cw[j];
            return n;
        }
    }
    return -1;
}

/* ------------------------------------------------------------------ signal processing */

static int nfft1, nfft2, nss, nsps;
static float fs2;
static kiss_fft_cpx *bigfft;                 /* nfft1/2+1 bins of the whole period */
static kiss_fft_cfg bcfg;                    /* inverse nfft2 */

static void downsample(float f0, float sigbw, cpx *c1) {
    float df = 12000.0f / nfft1;
    int i0 = (int)lroundf(f0 / df), ih = (int)lroundf((f0 + 1.3f * sigbw / 2.0f) / df), nbw = ih - i0 + 1;
    kiss_fft_cpx *in = calloc(nfft2, sizeof *in), *out = malloc(sizeof *out * nfft2);
    in[0] = bigfft[i0];
    for (int i = 1; i <= nbw; i++) {
        if (i0 + i <= nfft1 / 2) in[i] = bigfft[i0 + i];
        if (i0 - i >= 0) in[nfft2 - i] = bigfft[i0 - i];
    }
    for (int i = 0; i < nfft2; i++) { in[i].r /= nfft2; in[i].i /= nfft2; }
    kiss_fft(bcfg, in, out);
    for (int i = 0; i < nfft2; i++) { c1[i].r = out[i].r; c1[i].i = out[i].i; }
    free(in); free(out);
}

static cpx *sync1, *sync2, *synct1, *synct2;

static void sync_init(void) {
    int nz = 8 * nss;
    sync1 = malloc(sizeof(cpx) * nz); sync2 = malloc(sizeof(cpx) * nz);
    synct1 = malloc(sizeof(cpx) * nz); synct2 = malloc(sizeof(cpx) * nz);
    double twopi = 2 * M_PI, phi1 = 0, phi2 = 0;
    int k = 0;
    for (int i = 0; i < 8; i++) {
        double d1 = twopi * (FST4_SYNC1[i] - 1.5) / nss, d2 = twopi * (FST4_SYNC2[i] - 1.5) / nss;
        for (int j = 0; j < nss; j++) {
            sync1[k].r = (float)cos(phi1); sync1[k].i = (float)sin(phi1);
            sync2[k].r = (float)cos(phi2); sync2[k].i = (float)sin(phi2);
            phi1 = fmod(phi1 + d1, twopi); phi2 = fmod(phi2 + d2, twopi);
            k++;
        }
    }
}

static float sync_freq = -1e30f;

static float sync_fst4(const cpx *cd0, int i0, float f0, int np) {
    int nz = 8 * nss;
    if (f0 != sync_freq) {
        double dphi = 2 * M_PI * f0 / fs2, phi = 0;
        for (int i = 0; i < nz; i++) {
            cpx t = {(float)cos(phi), (float)sin(phi)};
            synct1[i] = cmul(t, sync1[i]); synct2[i] = cmul(t, sync2[i]);
            phi = fmod(phi + dphi, 2 * M_PI);
        }
        sync_freq = f0;
    }
    int is[5] = {i0, i0 + 38 * nss, i0 + 76 * nss, i0 + 114 * nss, i0 + 152 * nss};
    float s = 0;
    for (int w = 0; w < 5; w++) {
        if (w == 0 && is[0] < 1) continue;
        if (w == 4 && is[4] + nz - 1 >= np) continue;
        const cpx *ref = (w == 1 || w == 3) ? synct2 : synct1;
        cpx z = {0, 0};
        for (int i = 0; i < nz; i++) { cpx p = cmulc(cd0[is[w] + i], ref[i]); z.r += p.r; z.i += p.i; }
        s += cabsf_(z) / nz;
    }
    return s;
}

static int sync_search(const cpx *c2, float *fcbest_out, float *sbest_out) {
    int last_start = nfft2 - 160 * nss, nspsec = (int)fs2, isbest = -1;
    float baud = fs2 / nss, fcbest = 0, sbest = -1e30f;
    int is0 = (int)(1.5 * nspsec), ishw = (int)(1.5 * nspsec);
    int lo = is0 - ishw < 1 ? 1 : is0 - ishw, hi = is0 + ishw < last_start ? is0 + ishw : last_start;
    if (hi < lo) return -1;
    for (int f = -12; f <= 12; f++) {
        float fc = 0.1f * baud * f;
        for (int st = lo; st <= hi; st += 4) {
            float s = sync_fst4(c2, st, fc, nfft2);
            if (s > sbest) { fcbest = fc; isbest = st; sbest = s; }
        }
    }
    float fc1 = fcbest;
    is0 = isbest;
    lo = is0 - 4 < 1 ? 1 : is0 - 4; hi = is0 + 4 < last_start ? is0 + 4 : last_start;
    sbest = 0;
    for (int f = -7; f <= 7; f++) {
        float fc = fc1 + 0.02f * baud * f;
        for (int st = lo; st <= hi; st++) {
            float s = sync_fst4(c2, st, fc, nfft2);
            if (s > sbest) { fcbest = fc; isbest = st; sbest = s; }
        }
    }
    *fcbest_out = fcbest; *sbest_out = sbest;
    return isbest;
}

static int cmpf(const void *a, const void *b) { float x = *(const float *)a, y = *(const float *)b; return (x < y) ? -1 : (x > y); }

static float pctile(const float *x, int n, int npct) {
    float *t = malloc(sizeof(float) * n);
    memcpy(t, x, sizeof(float) * n);
    qsort(t, n, sizeof(float), cmpf);
    int j = (int)lround(n * 0.01 * npct);
    if (j < 1) j = 1;
    if (j > n) j = n;
    float v = t[j - 1];
    free(t);
    return v;
}

/* fst4_baseline: quadratic fit to the lower envelope (dB) of s[ia..ib]; sbase linear, 1.0 outside (as in WSJT-X). */
static void baseline(const float *s, int np, int ia, int ib, int npct, float *sbase) {
    float *sw = calloc(np, sizeof(float));
    for (int i = ia; i <= ib; i++) sw[i] = 10.0f * log10f(s[i] > 0 ? s[i] : 1e-30f);
    int nseg = 8, nlen = (ib - ia + 1) / nseg, i0 = (ib - ia + 1) / 2, kz = 0;
    double x[1000], y[1000];
    for (int n = 1; n <= nseg; n++) {
        int ja = ia + (n - 1) * nlen, jb = ja + nlen - 1;
        float base = pctile(sw + ja, nlen, npct);
        for (int i = ja; i <= jb; i++)
            if (sw[i] <= base) { if (kz < 1000) kz++; x[kz - 1] = i - i0; y[kz - 1] = sw[i]; }
    }
    double S[5] = {0}, T[3] = {0}, a[3] = {0};
    for (int i = 0; i < kz; i++) {
        double xt = 1;
        for (int n = 0; n < 5; n++) { S[n] += xt; xt *= x[i]; }
        xt = y[i];
        for (int n = 0; n < 3; n++) { T[n] += xt; xt *= x[i]; }
    }
    double A[3][4] = {{S[0], S[1], S[2], T[0]}, {S[1], S[2], S[3], T[1]}, {S[2], S[3], S[4], T[2]}};
    int ok = 1;
    for (int c = 0; c < 3 && ok; c++) {
        int p = c;
        for (int r = c + 1; r < 3; r++) if (fabs(A[r][c]) > fabs(A[p][c])) p = r;
        if (A[p][c] == 0) { ok = 0; break; }
        for (int q = 0; q < 4; q++) { double t = A[c][q]; A[c][q] = A[p][q]; A[p][q] = t; }
        for (int r = 0; r < 3; r++) if (r != c) { double f = A[r][c] / A[c][c]; for (int q = 0; q < 4; q++) A[r][q] -= f * A[c][q]; }
    }
    if (ok) for (int c = 0; c < 3; c++) a[c] = A[c][3] / A[c][c];
    for (int i = 0; i < np; i++) sbase[i] = 1.0f;
    for (int i = ia; i <= ib; i++) {
        double t = i - i0;
        sbase[i] = (float)pow(10.0, (a[0] + t * (a[1] + t * a[2]) + 0.2) / 10.0);
    }
    free(sw);
}

typedef struct { float f, sync, fsync, base; int is; } cand_t;

static int candidates(float fa, float fb, int nfa, int nfb, float minsync, cand_t *cand) {
    float fs = 12000.0f, df1 = fs / nfft1, baud = fs / nsps, df2 = baud / 2.0f;
    int nd = (int)(df2 / df1), ndh = nd / 2;
    int ia = (int)lroundf(fmaxf(100.0f, fa) / df2), ib = (int)lroundf(fminf(4800.0f, fb) / df2);
    int ina = (int)lroundf(fmaxf(100.0f, (float)nfa) / df2), inb = (int)lroundf(fminf(4800.0f, (float)nfb) / df2);
    if (ia < ina) ia = ina;
    if (ib > inb) ib = inb;
    if (ib < ia || inb - ina < 8) return 0;
    int nnw = (int)lroundf(48000.0f * nsps * 2.0f / fs);             /* WSJT-X array s(1:nnw); here index i = bin i too */
    float *s = calloc(nnw + 1, sizeof(float)), *s2 = calloc(nnw + 1, sizeof(float)), *sbase = calloc(nnw + 1, sizeof(float));
    if (inb > nnw) inb = nnw;
    for (int i = ina; i <= inb; i++) {
        int j0 = (int)lroundf(i * df2 / df1);
        for (int j = j0 - ndh; j <= j0 + ndh; j++) if (j >= 0 && j <= nfft1 / 2) s[i] += bigfft[j].r * bigfft[j].r + bigfft[j].i * bigfft[j].i;
    }
    if (ina < 4) ina = 4;
    if (inb > nnw - 3) inb = nnw - 3;
    for (int i = ina; i <= inb; i++) s2[i] = s[i - 3] + s[i - 1] + s[i + 1] + s[i + 3];
    baseline(s2, nnw + 1, ina + 3, inb - 3, 30, sbase);
    for (int i = ina; i <= inb; i++) { if (sbase[i] <= 0) { free(s); free(s2); free(sbase); return 0; } s2[i] /= sbase[i]; }
    if (ia < 3) ia = 3;
    if (ib > nnw - 2) ib = nnw - 2;
    static const float xdb[7] = {0.25f, 0.5f, 0.75f, 1.0f, 0.75f, 0.5f, 0.25f};
    int n = 0;
    while (n < 200) {
        int ip = ia;
        for (int i = ia; i <= ib; i++) if (s2[i] > s2[ip]) ip = i;
        float pval = s2[ip];
        if (pval < minsync) break;
        for (int i = -3; i <= 3; i++) {
            int k = ip + 2 * i;
            if (k >= ia && k <= ib) { float v = s2[k] - 0.9f * pval * xdb[i + 3]; s2[k] = v > 0 ? v : 0; }
        }
        cand[n].f = df2 * ip; cand[n].sync = pval; cand[n].base = sbase[ip]; cand[n].fsync = 0; cand[n].is = 0;
        n++;
    }
    free(s); free(s2); free(sbase);
    return n;
}

/* get_fst4_bitmetrics: 0 = bad sync */
static int bitmetrics(const cpx *cd, float bm[4][2 * NN], float s4[NN][4]) {
    static cpx *ci[4];
    static int ci_nss = 0;
    static float s2[65536];
    static const int graymap[4] = {0, 1, 3, 2};
    if (ci_nss != nss) {
        for (int t = 0; t < 4; t++) {
            free(ci[t]); ci[t] = malloc(sizeof(cpx) * nss);
            double dp = (t - 1.5) * 2 * M_PI / nss, phi = 0;
            for (int j = 0; j < nss; j++) { ci[t][j].r = (float)cos(phi); ci[t][j].i = (float)sin(phi); phi = fmod(phi + dp, 2 * M_PI); }
        }
        ci_nss = nss;
    }
    cpx cs[NN][4];
    for (int k = 0; k < NN; k++)
        for (int t = 0; t < 4; t++) {
            cpx z = {0, 0};
            for (int j = 0; j < nss; j++) { cpx p = cmulc(cd[k * nss + j], ci[t][j]); z.r += p.r; z.i += p.i; }
            cs[k][t] = z; s4[k][t] = cabs2(z);
        }
    int nsync = 0;
    for (int k = 0; k < 8; k++) {
        static const int off[5] = {0, 38, 76, 114, 152};
        for (int w = 0; w < 5; w++) {
            const int *sw = (w == 1 || w == 3) ? FST4_SYNC2 : FST4_SYNC1;
            int ip = 0;
            for (int t = 1; t < 4; t++) if (s4[k + off[w]][t] > s4[k + off[w]][ip]) ip = t;
            if (ip == sw[k]) nsync++;
        }
    }
    if (nsync < 16) return 0;
    memset(bm, 0, sizeof(float) * 4 * 2 * NN);
    for (int k = 0; k < NN; k += 8) {
        cpx c1[4][8], c2[16][4], c4[256][2];
        for (int m = 0; m < 8; m++) {
            for (int n = 0; n < 4; n++) { c1[n][m] = cs[k + m][graymap[n]]; s2[n] = cabsf_(c1[n][m]); }
            int ipt = k * 2 + 2 * m;
            for (int ib = 0; ib < 2; ib++) {
                float m1 = -1e30f, m0 = -1e30f;
                for (int v = 0; v < 4; v++) { if ((v >> (1 - ib)) & 1) { if (s2[v] > m1) m1 = s2[v]; } else if (s2[v] > m0) m0 = s2[v]; }
                bm[0][ipt + ib] = m1 - m0;
            }
        }
        for (int m = 0; m < 4; m++) {
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                int is = i * 4 + j;
                cpx a = c1[i][2 * m], b = c1[j][2 * m + 1];
                c2[is][m].r = a.r - b.r; c2[is][m].i = a.i - b.i;
                s2[is] = cabsf_(c2[is][m]);
            }
            int ipt = k * 2 + 4 * m;
            for (int ib = 0; ib < 4; ib++) {
                float m1 = -1e30f, m0 = -1e30f;
                for (int v = 0; v < 16; v++) { if ((v >> (3 - ib)) & 1) { if (s2[v] > m1) m1 = s2[v]; } else if (s2[v] > m0) m0 = s2[v]; }
                bm[1][ipt + ib] = m1 - m0;
            }
        }
        for (int m = 0; m < 2; m++) {
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
                int is = i * 16 + j;
                cpx a = c2[i][2 * m], b = c2[j][2 * m + 1];
                c4[is][m].r = a.r + b.r; c4[is][m].i = a.i + b.i;
                s2[is] = cabsf_(c4[is][m]);
            }
            int ipt = k * 2 + 8 * m;
            for (int ib = 0; ib < 8; ib++) {
                float m1 = -1e30f, m0 = -1e30f;
                for (int v = 0; v < 256; v++) { if ((v >> (7 - ib)) & 1) { if (s2[v] > m1) m1 = s2[v]; } else if (s2[v] > m0) m0 = s2[v]; }
                bm[2][ipt + ib] = m1 - m0;
            }
        }
        float m1[16], m0[16];
        for (int b = 0; b < 16; b++) { m1[b] = -1e30f; m0[b] = -1e30f; }
        for (int i = 0; i < 256; i++) for (int j = 0; j < 256; j++) {
            float re = c4[i][0].r + c4[j][1].r, im = c4[i][0].i + c4[j][1].i, a = sqrtf(re * re + im * im);
            int v = i * 256 + j;
            for (int b = 0; b < 16; b++) { if ((v >> (15 - b)) & 1) { if (a > m1[b]) m1[b] = a; } else if (a > m0[b]) m0[b] = a; }
        }
        for (int b = 0; b < 16; b++) bm[3][k * 2 + b] = m1[b] - m0[b];
    }
    static const int pat1[16] = {0,0,0,1,1,0,1,1,0,1,0,0,1,1,1,0}, pat2[16] = {1,1,1,0,0,1,0,0,1,0,1,1,0,0,0,1};
    static const int st[5] = {0, 76, 152, 228, 304};
    int nq = 0;
    for (int w = 0; w < 5; w++) for (int i = 0; i < 16; i++) nq += ((bm[0][st[w] + i] >= 0) ? 1 : 0) == ((w & 1) ? pat2[i] : pat1[i]);
    if (nq < 46) return 0;
    for (int c = 0; c < 4; c++) {
        double s = 0, s2v = 0;
        for (int i = 0; i < 2 * NN; i++) { s += bm[c][i]; s2v += bm[c][i] * bm[c][i]; }
        double av = s / (2 * NN), av2 = s2v / (2 * NN), var = av2 - av * av, sig = var > 0 ? sqrt(var) : sqrt(av2);
        for (int i = 0; i < 2 * NN; i++) bm[c][i] = (float)(2.83 * bm[c][i] / sig);
    }
    return 1;
}

/* ------------------------------------------------------------------ main */

static int16_t *read_wav(const char *path, int *n) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    unsigned char h[12];
    if (fread(h, 1, 12, f) != 12 || memcmp(h, "RIFF", 4) || memcmp(h + 8, "WAVE", 4)) { fclose(f); return NULL; }
    unsigned char ch[8];
    int rate = 0, bits = 0, chans = 0;
    while (fread(ch, 1, 8, f) == 8) {
        uint32_t len = ch[4] | ch[5] << 8 | ch[6] << 16 | (uint32_t)ch[7] << 24;
        if (!memcmp(ch, "fmt ", 4)) {
            unsigned char fm[40] = {0};
            size_t r = fread(fm, 1, len < 40 ? len : 40, f);
            if (len > 40) fseek(f, len - 40, SEEK_CUR);
            (void)r;
            chans = fm[2] | fm[3] << 8; rate = fm[4] | fm[5] << 8 | fm[6] << 16; bits = fm[14] | fm[15] << 8;
        } else if (!memcmp(ch, "data", 4)) {
            if (bits != 16 || chans != 1 || rate != 12000) { fprintf(stderr, "need 12 kHz mono 16 bit\n"); fclose(f); return NULL; }
            *n = (int)(len / 2);
            int16_t *d = malloc(len + 2);
            *n = (int)fread(d, 2, *n, f);
            fclose(f);
            return d;
        } else fseek(f, len + (len & 1), SEEK_CUR);
    }
    fclose(f);
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s file.wav [dialHz] [periodSec] [rxHz] [tolHz] [stateFile]\n", argv[0]); return 1; }
    double dial = argc > 2 ? atof(argv[2]) : 0;
    int ntr = argc > 3 ? atoi(argv[3]) : 120, nfqso = argc > 4 ? atoi(argv[4]) : 1500, ntol = argc > 5 ? atoi(argv[5]) : 100;
    const char *state = argc > 6 ? argv[6] : NULL;
    int ndown, nmax = ntr * 12000;
    float snr_calfac;
    switch (ntr) {
        case 120: nsps = 8200; ndown = 205; nfft1 = 7200 * 200; snr_calfac = 390; break;
        case 300: nsps = 21504; ndown = 512; nfft1 = 7020 * 512; snr_calfac = 340; break;
        case 900: nsps = 66560; ndown = 1664; nfft1 = 6480 * 1664; snr_calfac = 320; break;
        case 1800: nsps = 134400; ndown = 3360; nfft1 = 6426 * 3360; snr_calfac = 320; break;
        default: fprintf(stderr, "period must be 120, 300, 900 or 1800\n"); return 1;
    }
    (void)nmax;
    nss = nsps / ndown;
    fs2 = 12000.0f / ndown;
    nfft2 = nfft1 / ndown;
    nfft1 = nfft2 * ndown;
    float baud = 12000.0f / nsps, sigbw = 4.0f * baud;
    int nspsec = (int)lroundf(fs2);
    load_state(state);

    int nin = 0;
    int16_t *wav = read_wav(argv[1], &nin);
    if (!wav) { fprintf(stderr, "cannot read %s\n", argv[1]); return 1; }
    float *x = calloc(nfft1, sizeof(float));
    for (int i = 0; i < nin && i < nfft1; i++) x[i] = wav[i];
    free(wav);
    bigfft = malloc(sizeof(kiss_fft_cpx) * (nfft1 / 2 + 1));
    kiss_fftr_cfg fcfg = kiss_fftr_alloc(nfft1, 0, NULL, NULL);
    if (!fcfg) { fprintf(stderr, "fft size\n"); return 1; }
    kiss_fftr(fcfg, x, bigfft);
    free(fcfg); free(x);
    bcfg = kiss_fft_alloc(nfft2, 1, NULL, NULL);
    sync_init();

    int nfa = nfqso - ntol - 100 > 100 ? nfqso - ntol - 100 : 100, nfb = nfqso + ntol + 100 < 4800 ? nfqso + ntol + 100 : 4800;
    float fa = fmaxf(100.0f, (float)lroundf(nfqso + 1.5f * baud - ntol)), fb = fminf(4800.0f, (float)lroundf(nfqso + 1.5f * baud + ntol));
    cand_t cand[200];
    int ncand = candidates(fa, fb, nfa, nfb, 1.20f, cand);
    cpx *c2 = malloc(sizeof(cpx) * nfft2);
    for (int i = 0; i < ncand; i++) {
        downsample(cand[i].f, sigbw, c2);
        float fcb, sb;
        int isb = sync_search(c2, &fcb, &sb);
        if (isb < 0) { cand[i].fsync = -1; continue; }
        cand[i].fsync = cand[i].f + fcb; cand[i].is = isb;
    }
    for (int i = 0; i < ncand; i++)
        for (int j = i + 1; j < ncand; j++)
            if (cand[j].fsync > 0 && fabsf(cand[j].fsync - cand[i].fsync) < 0.10f * baud && abs(cand[j].is - cand[i].is) <= 2) cand[j].fsync = -1;
    int nc = 0;
    for (int i = 0; i < ncand; i++) if (cand[i].fsync > 0) cand[nc++] = cand[i];
    ncand = nc;

    char decodes[100][40];
    int ndecodes = 0;
    static float bm[4][2 * NN], s4[NN][4];
    cpx *frame = malloc(sizeof(cpx) * NN * nss);
    for (int ic = 0; ic < ncand; ic++) {
        float fcs = cand[ic].fsync, xdt = (float)(cand[ic].is - nspsec) / fs2;
        downsample(fcs, sigbw, c2);
        int done = 0;
        for (int ij = 0; ij <= 2 && !done; ij++) {
            int is0 = cand[ic].is + (ij == 0 ? 0 : (ij == 1 ? 1 : -1)), iend = is0 + NN * nss - 1;
            if (is0 < 0 || iend > nfft2 - 1) continue;
            memcpy(frame, c2 + is0, sizeof(cpx) * NN * nss);
            if (!bitmetrics(frame, bm, s4)) continue;
            float llrs[4][N];
            for (int il = 0; il < 4; il++) {
                memcpy(llrs[il], bm[il] + 16, 60 * sizeof(float));
                memcpy(llrs[il] + 60, bm[il] + 92, 60 * sizeof(float));
                memcpy(llrs[il] + 120, bm[il] + 168, 60 * sizeof(float));
                memcpy(llrs[il] + 180, bm[il] + 244, 60 * sizeof(float));
            }
            for (int itry = 0; itry < 4 && !done; itry++) {
                uint8_t m74[74], cw[N];
                char msg[64] = "", callgrid[48] = "", hashcall[24] = "";
                int type = 0, ok = 0, keff = 66;
                int nh = decode240_74(llrs[itry], 66, 2, 3, m74, cw);
                if (nh >= 0) {
                    int ones = 0; for (int i = 0; i < N; i++) ones += cw[i];
                    if (ones == 0) continue;
                    ok = unpack50(m74, msg, &type, callgrid, hashcall);
                }
                if (!ok && nwcalls > 0) {
                    nh = decode240_74(llrs[itry], 50, 1, 4, m74, cw);
                    int ones = 0; for (int i = 0; i < N; i++) ones += cw[i];
                    if (ones == 0) continue;
                    keff = 50;
                    if (nh >= 0 && unpack50(m74, msg, &type, callgrid, hashcall)) {
                        for (int i = 0; i < nwcalls && !ok; i++) if (strstr(msg, wcalls[i])) ok = 1;
                    }
                }
                if (nh < 0 || !ok) continue;
                if (hashcall[0]) save_hash(hashcall);
                if (keff == 66 && callgrid[0]) save_wcall(callgrid);
                int dupe = 0;
                for (int i = 0; i < ndecodes; i++) if (!strcmp(decodes[i], msg)) dupe = 1;
                done = 1;
                if (dupe) break;
                if (ndecodes < 100) snprintf(decodes[ndecodes++], 40, "%s", msg);
                int tones[NN];
                fst4_tones(m74, tones);
                double xsig = 0;
                for (int i = 0; i < NN; i++) xsig += s4[i][tones[i]];
                double arg = snr_calfac * xsig / cand[ic].base - 1.0, xsnr = arg > 0 ? 10 * log10(arg) + 10 * log10(1.46 / 2500) + 10 * log10(8200.0 / nsps) : -99.9;
                double fsig = fcs - 1.5 * baud;
                printf("FST4W %+.0f %+.1f %.6f +0 %s\n", xsnr, xdt, (dial + fsig) / 1e6, msg);
                if (getenv("FST4W_DEBUG")) fprintf(stderr, "cand %d f=%.3f sync=%.2f jitter=%d itry=%d keff=%d nh=%d audio=%.2f\n", ic, fcs, cand[ic].sync, ij, itry + 1, keff, nh, fsig);
                fflush(stdout);
            }
        }
    }
    save_state(state);
    return 0;
}
