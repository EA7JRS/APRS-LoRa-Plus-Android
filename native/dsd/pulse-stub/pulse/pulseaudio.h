/* Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later.
 * Minimal PulseAudio stand-in so dsd-fme links on Android, where there is no PulseAudio. Every call fails: the app runs
 * dsd-fme with TCP input and stdout output only. */
#ifndef PA_STUB_PULSEAUDIO_H
#define PA_STUB_PULSEAUDIO_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef enum { PA_SAMPLE_S16NE = 3, PA_SAMPLE_FLOAT32NE = 5 } pa_sample_format_t;
typedef struct { pa_sample_format_t format; uint32_t rate; uint8_t channels; } pa_sample_spec;
typedef struct { uint32_t maxlength, tlength, prebuf, minreq, fragsize; } pa_buffer_attr;
typedef struct { uint8_t channels; int map[32]; } pa_channel_map;
typedef enum { PA_STREAM_NODIRECTION, PA_STREAM_PLAYBACK, PA_STREAM_RECORD, PA_STREAM_UPLOAD } pa_stream_direction_t;
typedef enum { PA_CONTEXT_UNCONNECTED, PA_CONTEXT_CONNECTING, PA_CONTEXT_AUTHORIZING, PA_CONTEXT_SETTING_NAME, PA_CONTEXT_READY,
               PA_CONTEXT_FAILED, PA_CONTEXT_TERMINATED } pa_context_state_t;
typedef enum { PA_OPERATION_RUNNING, PA_OPERATION_DONE, PA_OPERATION_CANCELLED } pa_operation_state_t;
typedef uint64_t pa_usec_t;
typedef struct pa_mainloop pa_mainloop;
typedef struct pa_mainloop_api pa_mainloop_api;
typedef struct pa_context pa_context;
typedef struct pa_operation pa_operation;
typedef struct { const char *name; uint32_t index; const char *description; } pa_sink_info;
typedef struct { const char *name; uint32_t index; const char *description; } pa_source_info;
typedef void (*pa_context_notify_cb_t)(pa_context *c, void *userdata);
typedef void (*pa_sink_info_cb_t)(pa_context *c, const pa_sink_info *i, int eol, void *userdata);
typedef void (*pa_source_info_cb_t)(pa_context *c, const pa_source_info *i, int eol, void *userdata);
pa_mainloop *pa_mainloop_new(void);
pa_mainloop_api *pa_mainloop_get_api(pa_mainloop *m);
int pa_mainloop_iterate(pa_mainloop *m, int block, int *retval);
void pa_mainloop_free(pa_mainloop *m);
pa_context *pa_context_new(pa_mainloop_api *api, const char *name);
void pa_context_set_state_callback(pa_context *c, pa_context_notify_cb_t cb, void *userdata);
int pa_context_connect(pa_context *c, const char *server, int flags, const void *api);
pa_context_state_t pa_context_get_state(pa_context *c);
void pa_context_disconnect(pa_context *c);
void pa_context_unref(pa_context *c);
pa_operation *pa_context_get_sink_info_list(pa_context *c, pa_sink_info_cb_t cb, void *userdata);
pa_operation *pa_context_get_source_info_list(pa_context *c, pa_source_info_cb_t cb, void *userdata);
pa_operation_state_t pa_operation_get_state(pa_operation *o);
void pa_operation_unref(pa_operation *o);
#ifdef __cplusplus
}
#endif
#endif
