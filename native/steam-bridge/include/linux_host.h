#ifndef HB_LINUX_HOST_H
#define HB_LINUX_HOST_H
#include "bridge.h"
#include "steam_abi.h"
int hb_linux_prepare_worker(hb_bridge *bridge,const char *endpoint);
void hb_linux_commit_worker(void);
void hb_linux_cancel_worker(void);
int hb_linux_online(void);
int hb_linux_game_running(void);
uint32_t hb_linux_game_epoch(void);
uint64_t hb_linux_process_start(long pid);
int hb_linux_take_tick(void);
int hb_linux_take_rebind(void);
void hb_linux_retry_tick(void);
void hb_linux_diagnostic(const char *endpoint,const char *stage,hb_abi_reason reason);
void hb_linux_diagnostic_pid(const char *endpoint,long pid,const char *stage,hb_abi_reason reason);
#endif
