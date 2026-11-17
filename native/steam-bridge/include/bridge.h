#ifndef HIDEOUT_STEAM_BRIDGE_H
#define HIDEOUT_STEAM_BRIDGE_H
#include <stddef.h>
#include <stdint.h>
#define HB_MAX_FRAME (4u * 1024u * 1024u)
#define HB_PROTOCOL_MAGIC 0x48534732u
#define HB_APP_ID 1422450u
#define HB_SLOTS 4u
typedef struct hb_bridge hb_bridge;
typedef enum { HB_GC_UNKNOWN, HB_GC_READY, HB_GC_WAITING } hb_gc_state;
typedef enum { HB_ACK_QUEUED, HB_ACK_QUEUE_REJECTED, HB_ACK_EXPIRED, HB_ACK_SEND_REJECTED, HB_ACK_SUBMITTED } hb_ack;

typedef struct { uint64_t generation; uint32_t account; } hb_session;
typedef struct {
    hb_session session;
    uint64_t job;
    uint64_t argument;
    uint64_t deadline_ms;
    uint32_t message;
} hb_request;
typedef struct { hb_session session; uint32_t message; uint32_t size; } hb_capture;

/* Allocate before hooks become reachable; destroy only after every caller is gone. */
hb_bridge *hb_create(void);
void hb_destroy(hb_bridge *bridge);
/* These callback-facing functions try once, never wait. A missed session change disables captures. */
int hb_set_session(hb_bridge *bridge, hb_session session, hb_gc_state state);
int hb_get_session(hb_bridge *bridge, hb_session *session);
int hb_capture_gc(hb_bridge *bridge, hb_session session, uint32_t app,
                  uint32_t message, const void *data, uint32_t size);
int hb_pop_capture(hb_bridge *bridge, hb_capture *capture, void *data);
/* Identity snapshot remains readable across a transport loss; captures stay disabled. */
int hb_get_context(hb_bridge *bridge, hb_session *session, uint32_t *stamp);
void hb_gc_readiness_for_context(hb_bridge *bridge, uint32_t stamp, hb_gc_state state);
uint32_t hb_gc_pause_count(hb_bridge *bridge);
void hb_note_drop(hb_bridge *bridge, uint32_t message);
hb_gc_state hb_gc_readiness(hb_bridge *bridge);
void hb_gc_readiness_set(hb_bridge *bridge, hb_gc_state state);
void hb_request_result(hb_bridge *bridge, const hb_request *request, hb_ack outcome);
int hb_pop_result(hb_bridge *bridge, hb_request *request, hb_ack *outcome);
uint32_t hb_dropped(hb_bridge *bridge);
void hb_disconnect(hb_bridge *bridge);
int hb_queue_request(hb_bridge *bridge, const hb_request *request);
/* Returns 1 for a live request, -1 for expiry, 0 for no request. */
/* Only a verified adapter on Steam's owning thread may call this. */
int hb_take_request(hb_bridge *bridge, hb_session session, uint64_t now_ms, hb_request *request);
size_t hb_encode_request(const hb_request *request, unsigned char output[64]);

#endif
