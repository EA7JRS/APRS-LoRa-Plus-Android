/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
 * The few FFTW calls of wsprd, done with KISS FFT (BSD) so the decoder needs no FFTW on Android. */
#ifndef WSPR_FFTW_SHIM_H
#define WSPR_FFTW_SHIM_H
#include <stdio.h>
#include <stdlib.h>
#include "kiss_fft.h"
typedef float fftwf_complex[2];
typedef struct fftwf_plan_s { kiss_fft_cfg cfg; fftwf_complex *in, *out; } *fftwf_plan;
#define FFTW_FORWARD (-1)
#define FFTW_BACKWARD (+1)
#define FFTW_ESTIMATE (1U << 6)
#define FFTW_MEASURE (0U)
#define FFTW_PATIENT (1U << 5)
#define FFTW_EXHAUSTIVE (1U << 3)
static inline void *fftwf_malloc(size_t n) { return malloc(n); }
static inline void fftwf_free(void *p) { free(p); }
static inline fftwf_plan fftwf_plan_dft_1d(int n, fftwf_complex *in, fftwf_complex *out, int sign, unsigned flags) {
    (void)flags;
    fftwf_plan p = (fftwf_plan)malloc(sizeof(*p));
    p->cfg = kiss_fft_alloc(n, sign == FFTW_BACKWARD, NULL, NULL);
    p->in = in; p->out = out;
    return p;
}
static inline void fftwf_execute(fftwf_plan p) { kiss_fft(p->cfg, (const kiss_fft_cpx *)p->in, (kiss_fft_cpx *)p->out); }
static inline void fftwf_destroy_plan(fftwf_plan p) { if (p) { kiss_fft_free(p->cfg); free(p); } }
static inline int fftwf_import_wisdom_from_file(FILE *f) { (void)f; return 0; }
static inline void fftwf_export_wisdom_to_file(FILE *f) { (void)f; }
#endif
