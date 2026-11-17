#ifndef HB_GC_STATE_H
#define HB_GC_STATE_H
#include "bridge.h"
/* Returns 1 only for a well-formed passive readiness message. */
int hb_normalize_gc_state(uint32_t message, const void *data, uint32_t size, hb_gc_state *state);
uint64_t hb_gc_target_job(uint32_t message, const void *data, uint32_t size);
#endif
