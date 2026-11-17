#include "../src/steam_adapter.inc"
#include "../src/core.c"
#include <assert.h>
#include <stdio.h>
static int is_online=1,is_game=1,is_due=1,is_rebind=1;
int hb_linux_online(void){return is_online;}
int hb_linux_game_running(void){return is_game;}
static unsigned game_epoch=1;
uint32_t hb_linux_game_epoch(void){return game_epoch;}
int hb_linux_take_tick(void){int v=is_due;is_due=0;return v;}
int hb_linux_take_rebind(void){int v=is_rebind;is_rebind=0;return v;}
void hb_linux_retry_tick(void){is_rebind=is_due=1;}
int hb_linux_prepare_worker(hb_bridge *b,const char *ep){(void)b;(void)ep;return 0;}
void hb_linux_commit_worker(void){}
void hb_linux_cancel_worker(void){}
void hb_linux_diagnostic(const char *ep,const char *stage,hb_abi_reason r){(void)ep;(void)stage;(void)r;}
static uint32_t object[64],nodes[8],owner[32];
static uintptr_t gc_interface[4],user_interface[4];
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static unsigned delivered,frames,sends;static int send_outcome;
static steam_id identity(void *p){assert(p==owner || p==user_interface);return (steam_id){0x011000010000022bull};}
static uintptr_t receiver(void *self,uint32_t app,uint32_t type,const void *data,uint32_t size){
    assert(self==owner+16 && app==HB_APP_ID && type && data && size>=8);delivered++;return 0x87654321u;
}
static void original(void *client){assert(client==object);frames++;}
static void *get_user(void *client,int user,int pipe){assert(client==object && user==1 && pipe==2);return user_interface;}
static void *get_gc(void *client,int user,int pipe){assert(client==object && user==1 && pipe==2);return gc_interface;}
static int sender(void *gc,uint32_t app,uint32_t type,const void *data,uint32_t size){
    assert(gc==gc_interface && app==HB_APP_ID && type==(9112u|0x80000000u) && data && size>=17);sends++;return send_outcome;
}
static uintptr_t addr(const void *p){return (uintptr_t)p;}
static void put_pointer(void *p,uintptr_t value){memcpy(p,&value,sizeof(value));}
static void *other_thread(void *unused){(void)unused;frame_hook(object);return NULL;}
int main(void) {
    bridge=hb_create();assert(bridge);base=0;
    abi.singleton=addr(object);abi.client_vtable=0x1234;object[0]=abi.client_vtable;
    abi.user_vtable=0x2345;abi.gc_vtable=0x3456;owner[0]=abi.user_vtable;owner[16]=abi.gc_vtable;
    abi.gc_owner=sizeof(void*);abi.owner_gc=64;put_pointer(owner+16+sizeof(void*)/4,addr(owner));abi.owner_get_id=addr(identity);
    abi.map_mutex=addr(&mutex);abi.map_root=32;abi.map_count=36;abi.map_nodes=40;abi.map_capacity=40+sizeof(void*);
    abi.node_stride=24+sizeof(void*);abi.node_left=0;abi.node_right=4;abi.node_user=16;abi.node_pipe=20;abi.node_value=24;
    object[8]=0;object[9]=1;put_pointer(object+10,addr(nodes));object[10+sizeof(void*)/4]=1;
    nodes[0]=nodes[1]=UINT32_MAX;nodes[4]=1;nodes[5]=2;
    abi.user_map_vtable=0x4567;abi.gc_map_vtable=0x5678;user_interface[0]=abi.user_map_vtable;gc_interface[0]=abi.gc_map_vtable;
    abi.get_user=addr(get_user);abi.get_gc=addr(get_gc);abi.get_id=addr(identity);abi.send=addr(sender);
    original_receive=receiver;original_frame=original;
    frame_hook(object);assert(current.account==555 && current.generation);
    hb_session before=current;hb_request request={current,1,0,UINT64_MAX,9112};
    assert(hb_queue_request(bridge,&request));
    pthread_t other;pthread_create(&other,NULL,other_thread,NULL);pthread_join(other,NULL);assert(frames==2 && !sends);
    pthread_mutex_lock(&mutex);is_due=1;frame_hook(object);pthread_mutex_unlock(&mutex);
    assert(current.generation==before.generation && !sends);
    is_due=1;frame_hook(object);assert(sends==1);
    hb_request result;hb_ack outcome;assert(hb_pop_result(bridge,&result,&outcome) && outcome==HB_ACK_SUBMITTED);
    request.job=2;assert(hb_queue_request(bridge,&request));send_outcome=1;is_due=1;frame_hook(object);
    assert(hb_pop_result(bridge,&result,&outcome) && outcome==HB_ACK_SEND_REJECTED);
    unsigned char bytes[10]={0xa9,0x0f,0,0x80,0,0,0,0,8,2};
    assert(receive_hook(owner+16,HB_APP_ID,4009,bytes,10)==0x87654321u && delivered==1);
    assert(hb_gc_readiness(bridge)==HB_GC_WAITING && !hb_queue_request(bridge,&request));
    bytes[8]=8;bytes[9]=0;
    assert(!atomic_flag_test_and_set(&bridge->lock));
    assert(receive_hook(owner+16,HB_APP_ID,4009,bytes,10)==0x87654321u && delivered==2);
    assert(hb_gc_readiness(bridge)==HB_GC_READY);
    receive_hook(owner+16,HB_APP_ID,9166,bytes,10);assert(delivered==3 && hb_dropped(bridge)==1);
    atomic_flag_clear(&bridge->lock);
    is_rebind=1;assert(!atomic_flag_test_and_set(&bridge->lock));assert(!bind_session(555,1,2));atomic_flag_clear(&bridge->lock);
    assert(bind_session(555,1,2));assert(current.generation>before.generation);
    assert(hb_gc_readiness(bridge)==HB_GC_READY);
    is_online=0;hb_disconnect(bridge);bytes[9]=2;
    receive_hook(owner+16,HB_APP_ID,4009,bytes,10);assert(hb_gc_readiness(bridge)==HB_GC_WAITING);
    is_online=1;is_rebind=1;assert(bind_session(555,1,2));assert(hb_gc_readiness(bridge)==HB_GC_WAITING);
    before=current;assert(bind_session(555,1,3));assert(hb_gc_readiness(bridge)==HB_GC_UNKNOWN);assert(current.generation>before.generation);
    hb_gc_readiness_set(bridge,HB_GC_READY);before=current;game_epoch++;
    assert(bind_session(555,1,3) && current.generation>before.generation && hb_gc_readiness(bridge)==HB_GC_UNKNOWN);
    uint32_t stamp;hb_session snap;assert(hb_get_context(bridge,&snap,&stamp));
    is_game=0;is_due=1;frame_hook(object);assert(!current.generation);
    hb_gc_readiness_for_context(bridge,stamp,HB_GC_READY);assert(hb_gc_readiness(bridge)==HB_GC_UNKNOWN);
    unsigned char initialized=1;uint32_t engine[8]={0x6789};uintptr_t engine_global=addr(engine);
    abi.initialized=addr(&initialized);abi.engine_global=addr(&engine_global);abi.engine_vtable=0x6789;abi.engine_thread=16;
    object[9]=0;assert(early());
    initialized=0;assert(!early());initialized=1;
    engine[4]=1;assert(!early());engine[4]=0;
    object[9]=1;assert(!early());
    hb_destroy(bridge);puts("native identity return, delivery, main-thread dispatch, contention, readiness, outcomes and epochs passed");return 0;
}
