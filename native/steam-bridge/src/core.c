#include "bridge.h"
#include "gc_state.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "Hook counters must be lock-free");
struct slot { hb_capture capture; unsigned char data[HB_MAX_FRAME]; };
struct hb_bridge {
    atomic_flag lock;
    atomic_uint dropped;
    atomic_uint connected;
    atomic_uint readiness, context_seq, generation_low, generation_high, account, pauses;
    unsigned request_pause;
    hb_request result;
    hb_ack outcome;
    atomic_uint has_result;
    hb_session session;
    unsigned head, count;
    struct slot slots[HB_SLOTS];
    hb_request request;
    int has_request;
};
static int same(hb_session a, hb_session b) { return a.generation && a.generation == b.generation && a.account == b.account; }
static int enter(hb_bridge *b) { return !atomic_flag_test_and_set_explicit(&b->lock, memory_order_acquire); }
static void leave(hb_bridge *b) { atomic_flag_clear_explicit(&b->lock, memory_order_release); }
hb_bridge *hb_create(void) {
    hb_bridge *b = calloc(1, sizeof(*b));
    if (b) { atomic_flag_clear(&b->lock); atomic_init(&b->dropped, 0); atomic_init(&b->connected, 0); atomic_init(&b->readiness, HB_GC_UNKNOWN); atomic_init(&b->context_seq, 0);
        atomic_init(&b->generation_low, 0); atomic_init(&b->generation_high, 0); atomic_init(&b->account, 0); atomic_init(&b->pauses, 0); atomic_init(&b->has_result, 0); }
    return b;
}
void hb_destroy(hb_bridge *b) { free(b); }
int hb_set_session(hb_bridge *b, hb_session session, hb_gc_state state) {
    atomic_store(&b->connected, 0);
    if (!enter(b)) return 0;
    unsigned stamp=(atomic_load(&b->context_seq)+2)&0x3ffffffeu;
    atomic_store(&b->context_seq, stamp|1);
    atomic_store(&b->generation_low, (uint32_t)session.generation);
    atomic_store(&b->generation_high, (uint32_t)(session.generation>>32));
    atomic_store(&b->account, session.account);
    atomic_store(&b->readiness, stamp<<2|(unsigned)state);
    atomic_store(&b->context_seq, stamp);
    b->session = session; b->head = b->count = 0; b->has_request = 0;
    atomic_store(&b->connected, session.generation != 0);
    leave(b); return 1;
}
int hb_get_session(hb_bridge *b, hb_session *s) {
    if (!enter(b)) return 0;
    *s = b->session;
    if (!atomic_load(&b->connected)) *s = (hb_session){0,0};
    leave(b); return 1;
}
void hb_disconnect(hb_bridge *b) { atomic_store(&b->connected, 0); }
int hb_get_context(hb_bridge *b, hb_session *s, uint32_t *stamp) {
    unsigned seq=atomic_load(&b->context_seq);
    if(seq&1)return 0;
    s->generation=(uint64_t)atomic_load(&b->generation_high)<<32|atomic_load(&b->generation_low);
    s->account=atomic_load(&b->account);
    *stamp=seq;
    return seq==atomic_load(&b->context_seq) && atomic_load(&b->readiness)>>2==seq && s->generation!=0;
}
hb_gc_state hb_gc_readiness(hb_bridge *b) { return (hb_gc_state)(atomic_load(&b->readiness)&3); }
void hb_gc_readiness_for_context(hb_bridge *b, uint32_t stamp, hb_gc_state state) {
    unsigned expected=atomic_load(&b->readiness);
    if(state>HB_GC_WAITING || expected>>2!=stamp || (expected&3)==(unsigned)state)return;
    if(atomic_compare_exchange_strong(&b->readiness,&expected,stamp<<2|(unsigned)state) && state==HB_GC_WAITING)atomic_fetch_add(&b->pauses,1);
}
void hb_gc_readiness_set(hb_bridge *b, hb_gc_state state) {
    hb_gc_readiness_for_context(b,atomic_load(&b->context_seq),state);
}
uint32_t hb_gc_pause_count(hb_bridge *b) { return atomic_load(&b->pauses); }
void hb_note_drop(hb_bridge *b, uint32_t message) {
    message&=0x7fffffffu;
    if(message==9113 || message==9165 || message==9166 || message==9168)atomic_fetch_add(&b->dropped,1);
}
void hb_request_result(hb_bridge *b, const hb_request *r, hb_ack outcome) {
    b->result = *r; b->outcome = outcome;
    atomic_store_explicit(&b->has_result, 1, memory_order_release);
}
int hb_pop_result(hb_bridge *b, hb_request *r, hb_ack *outcome) {
    if (!atomic_load_explicit(&b->has_result, memory_order_acquire)) return 0;
    *r = b->result; *outcome = b->outcome;
    atomic_store_explicit(&b->has_result, 0, memory_order_release); return 1;
}
uint32_t hb_dropped(hb_bridge *b) { return atomic_load(&b->dropped); }
int hb_capture_gc(hb_bridge *b, hb_session s, uint32_t app, uint32_t msg, const void *data, uint32_t size) {
    msg &= 0x7fffffffu;
    if (app != HB_APP_ID || (msg != 9113 && msg != 9165 && msg != 9166 && msg != 9168)) return 0;
    if (!atomic_load(&b->connected)) return 0;
    if (!data || size < 8 || size > HB_MAX_FRAME) { atomic_fetch_add(&b->dropped, 1); return 0; }
    if (!enter(b)) { atomic_fetch_add(&b->dropped, 1); return 0; }
    if (same(s,b->session) && !b->has_request && same(s,b->request.session) &&
        b->request.message+1==msg && hb_gc_target_job(msg,data,size)==b->request.job && hb_gc_readiness(b)!=HB_GC_WAITING)
        hb_gc_readiness_set(b,HB_GC_READY);
    if (!same(s, b->session) || b->count == HB_SLOTS) { atomic_fetch_add(&b->dropped, 1); leave(b); return 0; }
    struct slot *slot = &b->slots[(b->head + b->count) % HB_SLOTS];
    slot->capture = (hb_capture){s, msg, size};
    memcpy(slot->data, data, size); ++b->count;
    leave(b); return 1;
}
int hb_pop_capture(hb_bridge *b, hb_capture *capture, void *data) {
    if (!enter(b)) return 0;
    if (!b->count || !atomic_load(&b->connected)) { leave(b); return 0; }
    struct slot *slot = &b->slots[b->head];
    *capture = slot->capture; memcpy(data, slot->data, capture->size);
    b->head = (b->head + 1) % HB_SLOTS; --b->count;
    leave(b); return 1;
}
int hb_queue_request(hb_bridge *b, const hb_request *r) {
    if ((r->message != 9112 && r->message != 9164 && r->message != 9167) ||
        (r->message == 9164 && r->argument) ||
        (r->message == 9167 && (!r->argument || r->argument > 10000000000ull)) || !r->job) return 0;
    if (!atomic_load(&b->connected) || !enter(b)) return 0;
    if (hb_gc_readiness(b) == HB_GC_WAITING || atomic_load(&b->has_result) || b->has_request || !same(b->session, r->session)) { leave(b); return 0; }
    b->request = *r; b->request_pause=atomic_load(&b->pauses); b->has_request = 1;
    leave(b); return 1;
}
int hb_take_request(hb_bridge *b, hb_session s, uint64_t now, hb_request *r) {
    if (!atomic_load(&b->connected) || !enter(b)) return 0;
    if (!b->has_request || atomic_load(&b->has_result)) { leave(b); return 0; }
    int valid = same(s, b->session) && same(s, b->request.session) && hb_gc_readiness(b) != HB_GC_WAITING && b->request_pause==atomic_load(&b->pauses);
    *r = b->request; b->has_request = 0;
    leave(b); return valid ? (now < r->deadline_ms ? 1 : -1) : 0;
}
static size_t varint(unsigned char *p, uint64_t n) {
    size_t count = 0;
    do { p[count++] = (n & 127u) | (n > 127u ? 128u : 0u); n >>= 7u; } while (n);
    return count;
}
size_t hb_encode_request(const hb_request *r, unsigned char out[64]) {
    uint32_t msg = r->message | 0x80000000u;
    for (unsigned i = 0; i < 4; ++i) out[i] = (unsigned char)(msg >> (8u * i));
    out[4] = 9; out[5] = out[6] = out[7] = 0; out[8] = 0x51;
    for (unsigned i = 0; i < 8; ++i) out[9+i] = (unsigned char)(r->job >> (8u * i));
    size_t n = 17;
    out[n++] = 8;
    n += varint(out+n, r->message == 9167 ? r->argument : r->session.account);
    if (r->message == 9112 && r->argument) { out[n++] = 16; n += varint(out+n, r->argument); }
    if (r->message == 9167) { out[n++] = 32; n += varint(out+n, r->session.account); }
    return n;
}
