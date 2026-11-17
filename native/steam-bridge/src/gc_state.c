#include "gc_state.h"
#include <string.h>
static uint32_t le32(const unsigned char *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
static int varint(const unsigned char **p,const unsigned char *end,uint64_t *n) {
    *n=0;
    for(unsigned i=0;i<10 && *p<end;i++) {
        unsigned c=*(*p)++;
        if(i==9 && c>1)return 0;
        *n|=(uint64_t)(c&127)<<(7*i);
        if(!(c&128))return 1;
    }
    return 0;
}
int hb_normalize_gc_state(uint32_t message,const void *data,uint32_t size,hb_gc_state *state) {
    message&=0x7fffffffu;
    if((message!=4004 && message!=4009) || !data || size<8 || size>HB_MAX_FRAME)return 0;
    const unsigned char *p=data,*end=p+size;
    if((le32(p)&0x7fffffffu)!=message || !(le32(p)&0x80000000u) || le32(p+4)>size-8)return 0;
    p+=8+le32(p+4);
    unsigned status=0,seen=0;
    for(unsigned fields=0;p<end;fields++) {
        uint64_t tag,n;
        if(fields==64 || !varint(&p,end,&tag) || !(tag>>3) || tag>>3>0x1fffffff)return 0;
        switch(tag&7) {
        case 0:
            if(!varint(&p,end,&n))return 0;
            if(message==4009 && tag>>3==1) { if(seen++ || n>6)return 0;status=(unsigned)n; }
            break;
        case 1: if(end-p<8)return 0;p+=8;break;
        case 2: if(!varint(&p,end,&n) || n>(uint64_t)(end-p))return 0;p+=(size_t)n;break;
        case 5: if(end-p<4)return 0;p+=4;break;
        default:return 0;
        }
        if(message==4009 && tag>>3==1 && (tag&7)!=0)return 0;
    }
    *state=message==4004 || status==0?HB_GC_READY:HB_GC_WAITING;
    return 1;
}
uint64_t hb_gc_target_job(uint32_t message,const void *data,uint32_t size) {
    if(!data || size<8 || size>HB_MAX_FRAME)return 0;
    const unsigned char *p=data;
    uint32_t header=le32(p+4);
    if(le32(p)!=(message|0x80000000u) || header>size-8 || header>256)return 0;
    const unsigned char *end=p+8+header;p+=8;uint64_t job=0;
    for(unsigned fields=0;p<end;fields++) {
        uint64_t tag,n;
        if(fields==32 || !varint(&p,end,&tag) || !(tag>>3))return 0;
        switch(tag&7) {
        case 0:if(!varint(&p,end,&n))return 0;break;
        case 1:
            if(end-p<8)return 0;
            if(tag>>3==11) { if(job)return 0;for(unsigned i=0;i<8;i++)job|=(uint64_t)p[i]<<(8*i); }
            p+=8;break;
        case 2:if(!varint(&p,end,&n) || n>(uint64_t)(end-p))return 0;p+=(size_t)n;break;
        case 5:if(end-p<4)return 0;p+=4;break;
        default:return 0;
        }
    }
    return job;
}
