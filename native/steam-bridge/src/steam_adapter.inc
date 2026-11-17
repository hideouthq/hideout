#define _GNU_SOURCE
#include "steam_adapter.h"
#include "steam_abi.h"
#include "linux_host.h"
#include "linux_patch.h"
#include "linux_transport.h"
#include "gc_state.h"
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
typedef struct { uint64_t value; } steam_id;
typedef void (*frame_fn)(void *);
typedef void *(*interface_fn)(void *,int,int);
typedef steam_id (*id_fn)(void *);
typedef int (*send_fn)(void *,uint32_t,uint32_t,const void *,uint32_t);
typedef uintptr_t (*receive_fn)(void *,uint32_t,uint32_t,const void *,uint32_t);
static ResolvedSteamAbi abi;
static uintptr_t base;
static hb_bridge *bridge;
static frame_fn original_frame;
static receive_fn original_receive;
static hb_session current;
static uint64_t generation;
static int previous_user,previous_pipe;
static atomic_uint bound_game_epoch;
static uint32_t word(const void *p,size_t offset) { uint32_t v;memcpy(&v,(const char*)p+offset,4);return v; }
static void *pointer(uintptr_t v) { return (void*)(uintptr_t)v; }
static uintptr_t pointer_word(const void *p,size_t offset) { uintptr_t v;memcpy(&v,(const char*)p+offset,sizeof(v));return v; }
static int table(const void *p,uint64_t address) { return p && pointer_word(p,0)==base+address; }
static int bind_session(uint32_t account,int user,int pipe) {
    unsigned game_epoch=hb_linux_game_epoch(),previous_epoch=atomic_load(&bound_game_epoch);
    int same_context=account && account==current.account && user==previous_user && pipe==previous_pipe && game_epoch==previous_epoch;
    if(hb_linux_take_rebind() || account!=current.account || user!=previous_user || pipe!=previous_pipe || game_epoch!=previous_epoch) {
        hb_session next={account?++generation:0,account};
        hb_gc_state cached=same_context?hb_gc_readiness(bridge):HB_GC_UNKNOWN;
        if(!hb_set_session(bridge,next,cached)) { hb_linux_retry_tick();return 0; }
        current=next;previous_user=user;previous_pipe=pipe;atomic_store(&bound_game_epoch,game_epoch);
    }
    return 1;
}
static uintptr_t receive_hook(void *self,uint32_t app,uint32_t type,const void *data,uint32_t size) {
    hb_session context;uint32_t stamp;
    if(hb_linux_game_running() && app==HB_APP_ID && table(self,abi.gc_vtable)) {
        if(atomic_load(&bound_game_epoch)!=hb_linux_game_epoch())return original_receive(self,app,type,data,size);
        if(!hb_get_context(bridge,&context,&stamp)) { hb_note_drop(bridge,type);return original_receive(self,app,type,data,size); }
        void *owner=pointer(pointer_word(self,abi.gc_owner));
        if(owner && (uintptr_t)owner+abi.owner_gc==(uintptr_t)self && table(owner,abi.user_vtable)) {
            steam_id id=((id_fn)(uintptr_t)(base+abi.owner_get_id))(owner);
            if((id.value>>32)==0x01100001u && (uint32_t)id.value==context.account) {
                hb_gc_state readiness;
                if(hb_normalize_gc_state(type,data,size,&readiness))hb_gc_readiness_for_context(bridge,stamp,readiness);
                else if(hb_linux_online()) (void)hb_capture_gc(bridge,context,app,type,data,size);
            }
        }
    }
    return original_receive(self,app,type,data,size);
}
struct handles { int user,pipe; };
static int copy_handles(void *client,struct handles out[64]) {
    pthread_mutex_t *mutex=(void*)(uintptr_t)(base+abi.map_mutex);
    if(pthread_mutex_trylock(mutex))return -1;
    uint32_t capacity=word(client,abi.map_capacity),count=word(client,abi.map_count),root=word(client,abi.map_root);
    uintptr_t nodes=pointer_word(client,abi.map_nodes);
    unsigned copied=0,queued=0,visited=0,valid=1;uint32_t stack[128],seen[64];
    if(capacity>4096 || count>64 || count>capacity || (count && !nodes))valid=0;
    if(valid && root!=UINT32_MAX)stack[queued++]=root;
    while(valid && queued) {
        uint32_t index=stack[--queued];
        if(index>=capacity || visited>=count || visited>=64 || nodes>UINTPTR_MAX-index*abi.node_stride-abi.node_stride) { valid=0;break; }
        for(unsigned n=0;n<visited;n++)if(seen[n]==index)valid=0;
        if(!valid)break;
        seen[visited++]=index;
        const void *node=pointer(nodes+index*abi.node_stride);
        int user=(int)word(node,abi.node_user),pipe=(int)word(node,abi.node_pipe);
        if(user>0 && pipe>0)out[copied++]=(struct handles){user,pipe};
        uint32_t left=word(node,abi.node_left),right=word(node,abi.node_right);
        if(left!=UINT32_MAX) { if(queued==128){valid=0;break;}stack[queued++]=left; }
        if(right!=UINT32_MAX) { if(queued==128){valid=0;break;}stack[queued++]=right; }
    }
    pthread_mutex_unlock(mutex);
    return valid && visited==count?(int)copied:0;
}
static void frame_hook(void *client) {
    original_frame(client);
    if((pid_t)syscall(SYS_gettid)!=getpid() || !hb_linux_take_tick() || !hb_linux_online())return;
    if(!table(client,abi.client_vtable) || (uintptr_t)client!=base+abi.singleton)return;
    if(!hb_linux_game_running()) { (void)bind_session(0,0,0);return; }
    struct handles handles[64];int count=copy_handles(client,handles);
    if(count<0)return;
    void *gc=NULL;uint32_t account=0;int user=0,pipe=0;
    for(int n=0;n<count;n++) {
        void *u=((interface_fn)(uintptr_t)(base+abi.get_user))(client,handles[n].user,handles[n].pipe);
        void *g=((interface_fn)(uintptr_t)(base+abi.get_gc))(client,handles[n].user,handles[n].pipe);
        if(!table(u,abi.user_map_vtable) || !table(g,abi.gc_map_vtable))continue;
        steam_id id=((id_fn)(uintptr_t)(base+abi.get_id))(u);
        if(id.value>>32!=0x01100001u || !(uint32_t)id.value)continue;
        if(account && account!=(uint32_t)id.value) { account=0;gc=NULL;break; }
        account=(uint32_t)id.value;
        if(!gc || handles[n].user<user || (handles[n].user==user && handles[n].pipe<pipe)) {
            user=handles[n].user;pipe=handles[n].pipe;gc=g;
        }
    }
    if(!hb_linux_game_running()) { account=0;user=pipe=0; }
    if(!bind_session(account,user,pipe) || !current.generation || !gc)return;
    hb_request request;int taken=hb_take_request(bridge,current,hb_monotonic_ms(),&request);
    if(taken<0)hb_request_result(bridge,&request,HB_ACK_EXPIRED);
    if(taken>0) {
        unsigned char data[64];size_t size=hb_encode_request(&request,data);
        int result=((send_fn)(uintptr_t)(base+abi.send))(gc,HB_APP_ID,request.message|0x80000000u,data,(uint32_t)size);
        hb_request_result(bridge,&request,result==0?HB_ACK_SUBMITTED:HB_ACK_SEND_REJECTED);
    }
}
static int early(void) {
    void *client=(void*)(uintptr_t)(base+abi.singleton);
    if(!*(unsigned char*)(uintptr_t)(base+abi.initialized) || !table(client,abi.client_vtable) || word(client,abi.map_count))return 0;
    void *engine=pointer(pointer_word((void*)base,abi.engine_global));
    return hb_linux_readable(engine,abi.engine_thread+4) && table(engine,abi.engine_vtable) && !word(engine,abi.engine_thread);
}
int hb_install_steam_adapter(const struct dl_phdr_info *m,const char *endpoint) {
    if(bridge)return 0;
    hb_module *view=NULL;ResolvedSteamAbi resolved;
    hb_abi_reason reason=hb_module_live(m->dlpi_name,m->dlpi_addr,&view);
    if(reason==HB_ABI_OK)reason=hb_resolve_steam_abi(view,&resolved);
    hb_module_close(view);
    if(reason!=HB_ABI_OK) { hb_linux_diagnostic(endpoint,"incompatible",reason);return 0; }
    base=m->dlpi_addr;abi=resolved;
    hb_linux_diagnostic(endpoint,"structurally_compatible",HB_ABI_OK);
    if(!early()) { hb_linux_diagnostic(endpoint,"incompatible",HB_ABI_LATE_ATTACHMENT);return 0; }
    bridge=hb_create();if(!bridge) { hb_linux_diagnostic(endpoint,"incompatible",HB_ABI_ALLOCATION_FAILED);return 0; }
    if(!hb_linux_prepare_worker(bridge,endpoint)) {
        hb_destroy(bridge);bridge=NULL;hb_linux_diagnostic(endpoint,"incompatible",HB_ABI_WORKER_FAILED);return 0;
    }
    original_frame=(frame_fn)(uintptr_t)(base+abi.frame);original_receive=(receive_fn)(uintptr_t)(base+abi.receive);generation=hb_monotonic_ms();
    hb_patch patches[2]={0};
    patches[0].address=(void*)(uintptr_t)(base+abi.receive_call);patches[0].size=5;
    memcpy(patches[0].before,abi.receive_bytes,5);patches[0].after[0]=0xe8;
    hb_jump_stub stub={0};
    if(!hb_linux_call_target(base+(uintptr_t)abi.receive_call,(uintptr_t)receive_hook,&stub,patches[0].after+1)) {
        hb_linux_cancel_worker();hb_destroy(bridge);bridge=NULL;hb_linux_diagnostic(endpoint,"incompatible",HB_ABI_PATCH_FAILED);return 0;
    }
    patches[1].address=(void*)(uintptr_t)(base+abi.client_vtable+19*sizeof(void*));patches[1].size=sizeof(void*);
    uintptr_t before=base+(uintptr_t)abi.frame,after=(uintptr_t)frame_hook;
    memcpy(patches[1].before,&before,sizeof(before));memcpy(patches[1].after,&after,sizeof(after));
    int still_early=early();
    int installed=still_early?hb_linux_patch(patches,2):0;
    if(installed!=1) {
        hb_linux_cancel_worker();
        if(installed==0) { hb_linux_release_stub(&stub);hb_destroy(bridge);bridge=NULL; }
        hb_linux_diagnostic(endpoint,"incompatible",still_early?HB_ABI_PATCH_FAILED:HB_ABI_LATE_ATTACHMENT);return 0;
    }
    hb_linux_diagnostic(endpoint,"armed",HB_ABI_OK);hb_linux_commit_worker();return 1;
}
