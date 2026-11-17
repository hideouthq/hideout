#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
static unsigned fail_at,calls;
static int test_mprotect(void *p,size_t n,int prot) {
    if(++calls==fail_at) { errno=EPERM;return -1; }
    return mprotect(p,n,prot);
}
#define mprotect test_mprotect
#include "../src/linux_patch.c"
#undef mprotect
int main(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    for(unsigned failure=0;failure<=4;failure++) {
        unsigned char *memory=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);assert(memory!=MAP_FAILED);
        memset(memory,0x11,page*2);
        hb_patch p[2]={{.address=memory+8,.size=5},{.address=memory+page+4,.size=4}};
        memset(p[0].before,0x11,5);memset(p[1].before,0x11,4);memset(p[0].after,0x22,5);memset(p[1].after,0x33,4);
        assert(!mprotect(memory,page,PROT_READ|PROT_EXEC));assert(!mprotect(memory+page,page,PROT_READ));
        fail_at=failure;calls=0;
        int ok=hb_linux_patch(p,2);assert(ok==(failure==0));
        assert(!memcmp(p[0].address,failure?p[0].before:p[0].after,5));
        assert(!memcmp(p[1].address,failure?p[1].before:p[1].after,4));
        int prot;assert(protection((uintptr_t)memory,page,&prot) && prot==(PROT_READ|PROT_EXEC));
        assert(protection((uintptr_t)(memory+page),page,&prot) && prot==PROT_READ);
        munmap(memory,page*2);
    }
#if UINTPTR_MAX > UINT32_MAX
    void *anchor=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);assert(anchor!=MAP_FAILED);
    memset(anchor,0x7a,page);hb_jump_stub stub;unsigned char displacement[4];
    uintptr_t target=(uintptr_t)anchor>UINT32_MAX?(uintptr_t)anchor-UINT32_MAX:UINTPTR_MAX-4096;
    fail_at=0;calls=0;
    assert(hb_linux_call_target((uintptr_t)anchor,target,&stub,displacement) && stub.mapping);
    int32_t relative;memcpy(&relative,displacement,4);
    assert((uintptr_t)anchor+5+relative==(uintptr_t)stub.mapping);
    assert(!memcmp(stub.mapping,"\xff\x25\0\0\0\0",6));
    uintptr_t stored;memcpy(&stored,(char*)stub.mapping+6,8);assert(stored==target);
    int mode;assert(protection((uintptr_t)stub.mapping,stub.size,&mode) && mode==(PROT_READ|PROT_EXEC));
    for(size_t n=0;n<page;n++)assert(((unsigned char*)anchor)[n]==0x7a);
    hb_linux_release_stub(&stub);assert(!stub.mapping);
    fail_at=1;calls=0;assert(!hb_linux_call_target((uintptr_t)anchor,target,&stub,displacement) && !stub.mapping);
    munmap(anchor,page);
#endif
    puts("transaction rollback restores both patches and page permissions at every mprotect failure");return 0;
}
