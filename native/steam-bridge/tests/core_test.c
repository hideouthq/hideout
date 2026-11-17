#include "bridge.h"
#include "gc_state.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"check failed at %d: %s\n",__LINE__,#x); exit(1); } } while(0)
int main(void) {
    hb_bridge *b = hb_create(); CHECK(b);
    hb_session s = {42, 555}; CHECK(hb_set_session(b,s,HB_GC_UNKNOWN));
    unsigned char bytes[64] = {0}, *copy = malloc(HB_MAX_FRAME); CHECK(copy);
    CHECK(!hb_capture_gc(b,s,123,9166,bytes,8));
    CHECK(!hb_capture_gc(b,s,HB_APP_ID,9999,bytes,8));
    CHECK(!hb_capture_gc(b,s,HB_APP_ID,9166,bytes,HB_MAX_FRAME+1));
    for (unsigned i=0;i<HB_SLOTS;i++) CHECK(hb_capture_gc(b,s,HB_APP_ID,9166,bytes,8));
    CHECK(!hb_capture_gc(b,s,HB_APP_ID,9166,bytes,8)); CHECK(hb_dropped(b)==2);
    hb_capture capture; CHECK(hb_pop_capture(b,&capture,copy)); CHECK(capture.size==8 && !memcmp(copy,bytes,8));
    hb_request request = {s, 0xfedcba9876543210ull, 0, 500, 9112}, result;
    CHECK(hb_queue_request(b,&request)); CHECK(!hb_queue_request(b,&request));
    CHECK(hb_take_request(b,s,501,&result)==-1);
    CHECK(hb_queue_request(b,&request)); CHECK(hb_take_request(b,s,499,&result));
    size_t size = hb_encode_request(&result,bytes);
    CHECK(size==20 && bytes[4]==9 && bytes[8]==0x51 && bytes[9]==0x10 && bytes[16]==0xfe);
    CHECK(bytes[17]==8 && bytes[18]==0xab && bytes[19]==4);
    CHECK(hb_gc_readiness(b)==HB_GC_UNKNOWN);
    unsigned char reply[19]={0x99,0x23,0,0x80,9,0,0,0,0x59};
    for(unsigned i=0;i<8;i++)reply[9+i]=(unsigned char)(result.job>>(8*i));
    reply[17]=8;reply[18]=1;
    CHECK(hb_capture_gc(b,s,HB_APP_ID,9113,reply,sizeof(reply)));
    CHECK(hb_gc_readiness(b)==HB_GC_READY);
    hb_request_result(b,&result,HB_ACK_SUBMITTED);
    hb_ack outcome;CHECK(hb_pop_result(b,&result,&outcome) && outcome==HB_ACK_SUBMITTED);
    hb_gc_readiness_set(b,HB_GC_UNKNOWN);
    CHECK(hb_queue_request(b,&request));
    hb_gc_readiness_set(b,HB_GC_WAITING);hb_gc_readiness_set(b,HB_GC_READY);
    CHECK(!hb_take_request(b,s,499,&result));
    CHECK(hb_gc_pause_count(b)==1);
    request.message=4006; CHECK(!hb_queue_request(b,&request));
    request.message=9167; request.argument=0; CHECK(!hb_queue_request(b,&request));
    request.argument=123; CHECK(hb_queue_request(b,&request));
    hb_session next = {43,666}; CHECK(hb_set_session(b,next,HB_GC_UNKNOWN));
    CHECK(!hb_take_request(b,s,0,&result)); CHECK(!hb_pop_capture(b,&capture,copy));
    CHECK(!hb_queue_request(b,&request));
    hb_gc_state state;
    unsigned char status[10]={0xa9,0x0f,0,0x80,0,0,0,0,8,0};
    for(unsigned n=0;n<=6;n++) { status[9]=(unsigned char)n;CHECK(hb_normalize_gc_state(4009,status,10,&state));CHECK(state==(n?HB_GC_WAITING:HB_GC_READY)); }
    status[9]=7;CHECK(!hb_normalize_gc_state(4009,status,10,&state));
    status[9]=0;status[4]=3;CHECK(!hb_normalize_gc_state(4009,status,10,&state));status[4]=0;
    unsigned char duplicate[12];memcpy(duplicate,status,10);duplicate[10]=8;duplicate[11]=2;CHECK(!hb_normalize_gc_state(4009,duplicate,12,&state));
    status[0]=0xa4;CHECK(hb_normalize_gc_state(4004,status,10,&state) && state==HB_GC_READY);
    CHECK(!hb_normalize_gc_state(4004,status,9,&state));
    hb_disconnect(b); hb_session disconnected;CHECK(hb_get_session(b,&disconnected) && !disconnected.generation && !disconnected.account);
    CHECK(!hb_capture_gc(b,next,HB_APP_ID,9166,bytes,8));
    free(copy); hb_destroy(b); puts("bridge core checks passed"); return 0;
}
