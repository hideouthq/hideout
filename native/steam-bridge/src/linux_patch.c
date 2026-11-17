#define _GNU_SOURCE
#include "linux_patch.h"
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
static int protection(uintptr_t a,size_t size,int *out) {
    FILE *f=fopen("/proc/self/maps","r");if(!f)return 0;
    char line[512],mode[5];unsigned long lo,hi;int found=0;
    while(fgets(line,sizeof(line),f))if(sscanf(line,"%lx-%lx %4s",&lo,&hi,mode)==3 && a>=lo && a<hi && size<=hi-a) {
        *out=(mode[0]=='r'?PROT_READ:0)|(mode[1]=='w'?PROT_WRITE:0)|(mode[2]=='x'?PROT_EXEC:0);found=1;break;
    }
    fclose(f);return found;
}
int hb_linux_readable(const void *a,size_t n) { int p;return protection((uintptr_t)a,n,&p) && (p&PROT_READ); }
int hb_linux_patch(hb_patch *patches,size_t count) {
    if(!count || count>2)return 0;
    long page=sysconf(_SC_PAGESIZE);if(page<=0 || ((unsigned long)page&((unsigned long)page-1)))return 0;
    struct { uintptr_t address;int prot; } pages[4];size_t used=0;
    for(size_t n=0;n<count;n++) {
        hb_patch *p=&patches[n];if(!p->size || p->size>8 || !hb_linux_readable(p->address,p->size) || memcmp(p->address,p->before,p->size))return 0;
        uintptr_t first=(uintptr_t)p->address&~((uintptr_t)page-1),last=((uintptr_t)p->address+p->size-1)&~((uintptr_t)page-1);
        for(uintptr_t a=first;;a+=(uintptr_t)page) {
            size_t k;for(k=0;k<used;k++)if(pages[k].address==a)break;
            if(k==used) { int prot;if(used==4 || !protection(a,(size_t)page,&prot))return 0;pages[used].address=a;pages[used++].prot=prot; }
            if(a==last)break;
        }
    }
    size_t writable=0;
    for(;writable<used;writable++)if(mprotect((void*)pages[writable].address,(size_t)page,pages[writable].prot|PROT_WRITE))break;
    if(writable!=used) {
        for(size_t k=0;k<writable;k++)(void)mprotect((void*)pages[k].address,(size_t)page,pages[k].prot);
        return 0;
    }
    for(size_t n=0;n<count;n++)memcpy(patches[n].address,patches[n].after,patches[n].size);
    int ok=1;
    for(size_t k=0;k<used;k++)if(mprotect((void*)pages[k].address,(size_t)page,pages[k].prot))ok=0;
    if(ok)return 1;
    int memfd=open("/proc/self/mem",O_RDWR|O_CLOEXEC),restored=1;
    for(size_t n=0;n<count;n++) {
        if(memfd<0 || pwrite64(memfd,patches[n].before,patches[n].size,(off64_t)(uintptr_t)patches[n].address)!=(ssize_t)patches[n].size)restored=0;
    }
    if(memfd>=0)close(memfd);
    for(size_t k=0;k<used;k++)(void)mprotect((void*)pages[k].address,(size_t)page,pages[k].prot);
    return restored?0:-1;
}

#if UINTPTR_MAX > UINT32_MAX
static int relative_call(uintptr_t call,uintptr_t target,int32_t *displacement) {
    if(call>UINTPTR_MAX-5)return 0;
    uintptr_t next=call+5;
    if(target>=next) { if(target-next>INT32_MAX)return 0;*displacement=(int32_t)(target-next); }
    else { if(next-target>(uintptr_t)INT32_MAX+1)return 0;*displacement=(int32_t)(0-(uint32_t)(next-target)); }
    return 1;
}
#endif
void hb_linux_release_stub(hb_jump_stub *stub) {
    if(stub->mapping)munmap(stub->mapping,stub->size);
    *stub=(hb_jump_stub){0};
}
int hb_linux_call_target(uintptr_t call,uintptr_t target,hb_jump_stub *stub,unsigned char displacement[4]) {
    *stub=(hb_jump_stub){0};int32_t relative;
#if UINTPTR_MAX == UINT32_MAX
    uint32_t value=(uint32_t)target-(uint32_t)(call+5);memcpy(displacement,&value,4);(void)relative;return 1;
#else
    if(relative_call(call,target,&relative)) {memcpy(displacement,&relative,4);return 1;}
    long size=sysconf(_SC_PAGESIZE);if(size<16 || ((unsigned long)size&((unsigned long)size-1)))return 0;
    uintptr_t origin=call&~((uintptr_t)size-1);
    // Bounded hints only: mmap must never replace an existing mapping, even on older kernels.
    for(unsigned n=1;n<=4096;n++) {
        uintptr_t distance=(uintptr_t)((n+1)/2)*1024*1024;
        if((n&1)?origin<distance:origin>UINTPTR_MAX-distance)continue;
        uintptr_t hint=(n&1)?origin-distance:origin+distance;
        if(hint<(uintptr_t)size || !relative_call(call,hint,&relative))continue;
        void *p=mmap((void*)hint,(size_t)size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(p==MAP_FAILED)continue;
        if(!relative_call(call,(uintptr_t)p,&relative)) {munmap(p,(size_t)size);continue;}
        const unsigned char jump[6]={0xff,0x25,0,0,0,0};
        memcpy(p,jump,6);memcpy((unsigned char*)p+6,&target,8);
        if(mprotect(p,(size_t)size,PROT_READ|PROT_EXEC)) {munmap(p,(size_t)size);return 0;}
        stub->mapping=p;stub->size=(size_t)size;memcpy(displacement,&relative,4);return 1;
    }
    return 0;
#endif
}
