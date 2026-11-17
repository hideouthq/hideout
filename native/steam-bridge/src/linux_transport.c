#define _POSIX_C_SOURCE 200809L
#include "linux_transport.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

__attribute__((weak)) void hb_linux_connected(int connected) { (void)connected; }
__attribute__((weak)) void hb_linux_background_tick(void) {}

static void be32(unsigned char *p, uint32_t n) { for (unsigned i=0;i<4;i++) p[i]=(unsigned char)(n>>(24-8*i)); }
static void be64(unsigned char *p, uint64_t n) { for (unsigned i=0;i<8;i++) p[i]=(unsigned char)(n>>(56-8*i)); }
static uint32_t u32(const unsigned char *p) { return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3]; }
static uint64_t u64(const unsigned char *p) { return (uint64_t)u32(p)<<32 | u32(p+4); }
uint64_t hb_monotonic_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000; }
static int write_all(int fd,const void *buffer,size_t size) {
    const unsigned char *p=buffer;
    while (size) { ssize_t n=send(fd,p,size,MSG_NOSIGNAL); if(n<0&&errno==EINTR)continue; if(n<=0)return 0; p+=n;size-=(size_t)n; } return 1;
}
static int read_all(int fd,void *buffer,size_t size) {
    unsigned char *p=buffer;
    while(size) { ssize_t n=recv(fd,p,size,0); if(n<0&&errno==EINTR)continue; if(n<=0)return 0;p+=n;size-=(size_t)n; } return 1;
}
static int packet(int fd,uint32_t kind,hb_session s,uint64_t job,uint32_t message,const void *body,uint32_t size) {
    unsigned char h[36]; be32(h,32+size);be32(h+4,HB_PROTOCOL_MAGIC);be32(h+8,kind);
    be64(h+12,s.generation);be64(h+20,job);be32(h+28,s.account);be32(h+32,message);
    return write_all(fd,h,sizeof(h)) && (!size || write_all(fd,body,size));
}
static int hexval(char c) { if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;return -1; }
static int endpoint_read(const char *path,uint16_t *port,unsigned char token[32]) {
    int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC); if(fd<0)return 0;
    struct stat st;
    if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid!=getuid()||(st.st_mode&077)||st.st_size>128) {close(fd);return 0;}
    char data[129]={0};ssize_t n=read(fd,data,128);close(fd);if(n<=0)return 0;
    if(n<72 || memcmp(data,"HSG2\n",5))return 0;
    unsigned p=0,digits=0;char *cursor=data+5;
    while(*cursor>='0'&&*cursor<='9') { if(++digits>5)return 0;p=p*10+(unsigned)(*cursor++-'0'); }
    if(!digits||*cursor++!='\n'||p==0||p>65535||cursor+65!=data+n||cursor[64]!='\n')return 0;
    for(unsigned i=0;i<32;i++){int a=hexval(cursor[i*2]),b=hexval(cursor[i*2+1]);if(a<0||b<0)return 0;token[i]=(unsigned char)(a*16+b);}
    *port=(uint16_t)p;return 1;
}
int hb_linux_transport(hb_bridge *bridge,const char *path,atomic_int *stop) {
    uint16_t port;unsigned char hello[40];
    if(!endpoint_read(path,&port,hello))return 0;
    be32(hello+32,(uint32_t)getpid());be32(hello+36,(uint32_t)(sizeof(void*)*8));
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return 0;
    struct timeval timeout={5,0};
    if(setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))||setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout))) {close(fd);return 0;}
    struct sockaddr_in addr={0};addr.sin_family=AF_INET;addr.sin_port=htons(port);addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    hb_session empty={0,0},last={0,0};unsigned char h[36],arg[8];
    if(connect(fd,(struct sockaddr*)&addr,sizeof(addr))||!packet(fd,1,empty,0,0,hello,sizeof(hello))||!read_all(fd,h,sizeof(h))||u32(h)!=32||u32(h+4)!=HB_PROTOCOL_MAGIC||u32(h+8)!=5) {close(fd);return 0;}
    unsigned char *payload=malloc(HB_MAX_FRAME);if(!payload){close(fd);return 0;}
    uint64_t heartbeat=0,tick=0;uint32_t dropped=hb_dropped(bridge),pauses=hb_gc_pause_count(bridge);int readiness=-1;int ok=1;
    hb_linux_connected(1);
    while(!atomic_load(stop)) {
        uint64_t background_now=hb_monotonic_ms();
        if(background_now>=tick){hb_linux_background_tick();tick=background_now+1000;}
        hb_session s;
        if(hb_get_session(bridge,&s)&&(s.generation!=last.generation||s.account!=last.account)) {
            if(!packet(fd,2,s,0,s.generation?1:0,NULL,0)){ok=0;break;}last=s;readiness=-1;pauses=hb_gc_pause_count(bridge);
        }
        if(last.generation) {
            uint32_t pause=hb_gc_pause_count(bridge);
            if(pause!=pauses) {
                if(!packet(fd,8,last,0,HB_GC_WAITING,NULL,0)){ok=0;break;}
                pauses=pause;readiness=HB_GC_WAITING;
            }
            int state=hb_gc_readiness(bridge);
            if(state!=readiness) {
                if(!packet(fd,8,last,0,(uint32_t)state,NULL,0)){ok=0;break;}
                readiness=state;
            }
        }
        hb_request result;hb_ack outcome;
        if(hb_pop_result(bridge,&result,&outcome) &&
           !packet(fd,5,result.session,result.job,(uint32_t)outcome,NULL,0)){ok=0;break;}
        hb_capture capture;
        for(unsigned budget=0;budget<HB_SLOTS && hb_pop_capture(bridge,&capture,payload);budget++) {
            if(capture.session.generation!=last.generation || capture.session.account!=last.account) {
                if(!packet(fd,2,capture.session,0,1,NULL,0)){ok=0;break;}
                last=capture.session;readiness=-1;pauses=hb_gc_pause_count(bridge);
            }
            if(!packet(fd,3,capture.session,0,capture.message,payload,capture.size)){ok=0;break;}
        }
        if(!ok)break;
        uint32_t loss=hb_dropped(bridge);
        if(loss!=dropped){be64(arg,loss);if(!packet(fd,4,last,0,0,arg,8)){ok=0;break;}dropped=loss;}
        uint64_t now=hb_monotonic_ms();
        if(now>=heartbeat){if(!packet(fd,6,empty,0,0,NULL,0)){ok=0;break;}heartbeat=now+5000;}
        struct pollfd pollfd={fd,POLLIN,0};int ready=poll(&pollfd,1,50);
        if(ready<0&&errno==EINTR)continue;
        if(ready<0||(ready>0&&(pollfd.revents&(POLLHUP|POLLERR|POLLNVAL)))){ok=0;break;}
        if(ready>0&&(pollfd.revents&POLLIN)) {
            if(!read_all(fd,h,sizeof(h))||u32(h)!=40||u32(h+4)!=HB_PROTOCOL_MAGIC||u32(h+8)!=7||!read_all(fd,arg,8)){ok=0;break;}
            hb_request r={{u64(h+12),u32(h+28)},u64(h+20),u64(arg),hb_monotonic_ms()+25000,u32(h+32)};
            int accepted=hb_queue_request(bridge,&r);
            if(!packet(fd,5,r.session,r.job,accepted?0:1,NULL,0)){ok=0;break;}
        }
    }
    hb_linux_connected(0);hb_disconnect(bridge);free(payload);close(fd);return ok;
}
