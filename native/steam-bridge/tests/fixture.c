#define _POSIX_C_SOURCE 200809L
#include "linux_transport.h"
#include <pthread.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

struct fixture { hb_bridge *bridge; const char *endpoint; atomic_int stop; };
static void *transport(void *context) {
    struct fixture *f=context;
    hb_linux_transport(f->bridge,f->endpoint,&f->stop);atomic_store(&f->stop,1);return NULL;
}
int main(int argc,char **argv) {
    if(argc==4 && !strcmp(argv[1],"--mapped-only")) {
        if(!dlopen(argv[2],RTLD_NOW|RTLD_LOCAL) || !dlopen(argv[3],RTLD_NOW|RTLD_LOCAL))return 1;
        puts("ready");fflush(stdout);(void)getchar();return 0;
    }
    if(argc<2 || argc>3 || (argc==3 && strcmp(argv[2],"--drop-first-history"))){fprintf(stderr,"usage: bridge_fixture ENDPOINT [--drop-first-history]\n");return 2;}
    int drop_history=argc==3;
    struct fixture f={.bridge=hb_create(),.endpoint=argv[1]};atomic_init(&f.stop,0);if(!f.bridge)return 1;
    hb_session session={42,555};hb_set_session(f.bridge,session,HB_GC_UNKNOWN);
    pthread_t worker;if(pthread_create(&worker,NULL,transport,&f)){hb_destroy(f.bridge);return 1;}
    uint64_t until=hb_monotonic_ms()+60000;
    while(!atomic_load(&f.stop)&&hb_monotonic_ms()<until) {
        hb_request request;
        int taken=hb_take_request(f.bridge,session,hb_monotonic_ms(),&request);
        if(taken) {
            hb_request_result(f.bridge,&request,taken<0?HB_ACK_EXPIRED:HB_ACK_SUBMITTED);
            if(taken<0)continue;
            if(drop_history && request.message==9112){drop_history=0;continue;}
            unsigned char frame[64]={0};uint32_t msg=(request.message+1)|0x80000000u;
            for(unsigned i=0;i<4;i++)frame[i]=(unsigned char)(msg>>(8*i));
            frame[4]=9;frame[8]=0x59;
            for(unsigned i=0;i<8;i++)frame[9+i]=(unsigned char)(request.job>>(8*i));
            static const unsigned char history[]={8,1,16,0,26,9,8,123,16,17,24,0xb0,9,56,5};
            static const unsigned char account[]={8,1,18,3,8,0xab,4};
            static const unsigned char metadata[]={8,1,24,42,40,1};
            const unsigned char *body=history;size_t size=sizeof(history);
            if(request.message==9164){body=account;size=sizeof(account);}
            if(request.message==9167){body=metadata;size=sizeof(metadata);}
            memcpy(frame+17,body,size);
            while(!atomic_load(&f.stop) && !hb_capture_gc(f.bridge,session,HB_APP_ID,msg,frame,(uint32_t)(17+size))) {
                struct timespec retry={0,1000000};nanosleep(&retry,NULL);
            }
        }
        struct timespec wait={0,1000000};nanosleep(&wait,NULL);
    }
    atomic_store(&f.stop,1);pthread_join(worker,NULL);hb_destroy(f.bridge);return 0;
}
