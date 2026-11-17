#ifndef HB_STEAM_ABI_H
#define HB_STEAM_ABI_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef enum {
    HB_ABI_OK, HB_ABI_UNSUPPORTED_TARGET, HB_ABI_INVALID_ELF, HB_ABI_INVALID_BOUNDS,
    HB_ABI_MISSING_RTTI, HB_ABI_AMBIGUOUS_RTTI, HB_ABI_MISSING_UNWIND,
    HB_ABI_UNSUPPORTED_INSTRUCTION, HB_ABI_INTERFACE_DRIFT, HB_ABI_MAP_DRIFT,
    HB_ABI_IDENTITY_DRIFT, HB_ABI_RECEIVE_DRIFT, HB_ABI_LIFECYCLE_DRIFT,
    HB_ABI_LATE_ATTACHMENT, HB_ABI_RUNTIME_LOAD, HB_ABI_PATCH_FAILED, HB_ABI_PROBE_TIMEOUT, HB_ABI_ALLOCATION_FAILED, HB_ABI_WORKER_FAILED
} hb_abi_reason;
/* All addresses are module virtual addresses derived for this invocation. Never persisted. */
typedef struct {
    uint64_t client_vtable, engine_vtable, user_vtable, gc_vtable, user_map_vtable, gc_map_vtable;
    uint64_t singleton, initialized, engine_global, engine_thread;
    uint64_t frame, get_user, get_gc, get_id, owner_get_id, send, receive, receive_call;
    uint64_t map_mutex;
    uint32_t map_root, map_nodes, map_count, map_capacity, node_stride;
    uint32_t node_left, node_right, node_user, node_pipe, node_value, gc_owner, owner_gc;
    uint32_t user_interface, gc_interface;
    unsigned char receive_bytes[5];
} ResolvedSteamAbi;
typedef struct hb_module hb_module;
hb_abi_reason hb_module_file(const char *path, hb_module **module);
/* live_base is the module's load bias. ELF metadata is checked against the file view. */
hb_abi_reason hb_module_live(const char *path, uintptr_t live_base, hb_module **module);
void hb_module_close(hb_module *module);
hb_abi_reason hb_resolve_steam_abi(const hb_module *module, ResolvedSteamAbi *abi);
const char *hb_abi_reason_code(hb_abi_reason reason);
const char *hb_module_build_id(const hb_module *module);
#ifdef __cplusplus
}
#endif
#endif
