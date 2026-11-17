#ifndef HB_LINUX_TRANSPORT_H
#define HB_LINUX_TRANSPORT_H
#include "bridge.h"
#include <stdatomic.h>
uint64_t hb_monotonic_ms(void);
int hb_linux_transport(hb_bridge *bridge, const char *endpoint, atomic_int *stop);
/* Optional adapter lifecycle; always called on the transport worker, never a Steam callback. */
void hb_linux_connected(int connected);
void hb_linux_background_tick(void);
#endif
