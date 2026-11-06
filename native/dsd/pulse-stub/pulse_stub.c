/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later. PulseAudio stand-in: everything fails, nothing is played. */
#include "pulse/simple.h"
#include "pulse/error.h"
pa_mainloop *pa_mainloop_new(void) { return 0; }
pa_mainloop_api *pa_mainloop_get_api(pa_mainloop *m) { (void)m; return 0; }
int pa_mainloop_iterate(pa_mainloop *m, int block, int *retval) { (void)m; (void)block; if (retval) *retval = -1; return -1; }
void pa_mainloop_free(pa_mainloop *m) { (void)m; }
pa_context *pa_context_new(pa_mainloop_api *api, const char *name) { (void)api; (void)name; return 0; }
void pa_context_set_state_callback(pa_context *c, pa_context_notify_cb_t cb, void *u) { (void)c; (void)cb; (void)u; }
int pa_context_connect(pa_context *c, const char *s, int f, const void *a) { (void)c; (void)s; (void)f; (void)a; return -1; }
pa_context_state_t pa_context_get_state(pa_context *c) { (void)c; return PA_CONTEXT_FAILED; }
void pa_context_disconnect(pa_context *c) { (void)c; }
void pa_context_unref(pa_context *c) { (void)c; }
pa_operation *pa_context_get_sink_info_list(pa_context *c, pa_sink_info_cb_t cb, void *u) { (void)c; (void)cb; (void)u; return 0; }
pa_operation *pa_context_get_source_info_list(pa_context *c, pa_source_info_cb_t cb, void *u) { (void)c; (void)cb; (void)u; return 0; }
pa_operation_state_t pa_operation_get_state(pa_operation *o) { (void)o; return PA_OPERATION_DONE; }
void pa_operation_unref(pa_operation *o) { (void)o; }
pa_simple *pa_simple_new(const char *server, const char *name, pa_stream_direction_t dir, const char *dev, const char *sn,
                         const pa_sample_spec *ss, const pa_channel_map *map, const pa_buffer_attr *attr, int *error) {
    (void)server; (void)name; (void)dir; (void)dev; (void)sn; (void)ss; (void)map; (void)attr;
    if (error) *error = 1;
    return 0;
}
int pa_simple_write(pa_simple *s, const void *d, size_t b, int *e) { (void)s; (void)d; (void)b; if (e) *e = 1; return -1; }
int pa_simple_read(pa_simple *s, void *d, size_t b, int *e) { (void)s; (void)d; (void)b; if (e) *e = 1; return -1; }
pa_usec_t pa_simple_get_latency(pa_simple *s, int *e) { (void)s; if (e) *e = 1; return 0; }
void pa_simple_free(pa_simple *s) { (void)s; }
const char *pa_strerror(int error) { (void)error; return "no PulseAudio on Android"; }
