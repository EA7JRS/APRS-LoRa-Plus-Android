/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later. PulseAudio stand-in (see pulseaudio.h). */
#ifndef PA_STUB_SIMPLE_H
#define PA_STUB_SIMPLE_H
#include "pulseaudio.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct pa_simple pa_simple;
pa_simple *pa_simple_new(const char *server, const char *name, pa_stream_direction_t dir, const char *dev, const char *stream_name,
                         const pa_sample_spec *ss, const pa_channel_map *map, const pa_buffer_attr *attr, int *error);
int pa_simple_write(pa_simple *s, const void *data, size_t bytes, int *error);
int pa_simple_read(pa_simple *s, void *data, size_t bytes, int *error);
pa_usec_t pa_simple_get_latency(pa_simple *s, int *error);
void pa_simple_free(pa_simple *s);
#ifdef __cplusplus
}
#endif
#endif
