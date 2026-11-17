#ifndef HB_LINUX_PATCH_H
#define HB_LINUX_PATCH_H
#include <stddef.h>
#include <stdint.h>
typedef struct { void *address; size_t size; unsigned char before[8],after[8]; } hb_patch;
/* Early lifecycle only. Restores original bytes and original permissions on any failure. */
int hb_linux_patch(hb_patch *patches,size_t count);
typedef struct { void *mapping; size_t size; } hb_jump_stub;
/* Produces rel32 bytes and, only if required, owns a nearby RX tail-jump stub.
   Release after a clean rollback; retain after commit or incomplete rollback. */
int hb_linux_call_target(uintptr_t call,uintptr_t target,hb_jump_stub *stub,unsigned char displacement[4]);
void hb_linux_release_stub(hb_jump_stub *stub);
int hb_linux_readable(const void *address,size_t size);
#endif
