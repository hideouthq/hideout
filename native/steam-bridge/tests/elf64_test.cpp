#include "../src/steam_abi.cpp"
#include <cassert>
#include <fcntl.h>
#include <unistd.h>
int main(int argc,char **argv) {
    assert(argc==3);
    for(int n=1;n<3;n++) {
        hb_module *m=nullptr;assert(hb_module_file(argv[n],&m)==HB_ABI_OK && m->bits==64);
        assert(n==1?m->origin==0:m->origin>UINT32_MAX);
        assert(!m->relative.empty() && !m->functions.empty() && !m->imports.empty());
        size_t size=m->bytes.size();void *memory=mmap(nullptr,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(memory!=MAP_FAILED && (uintptr_t)memory>m->origin && (uintptr_t)memory>UINT32_MAX);
        uintptr_t bias=(uintptr_t)memory-m->origin;memcpy(memory,m->bytes.data(),size);
        for(auto a:m->relative) {uint64_t relocated=m->pointer(a)+bias;memcpy((char*)memory+a-m->origin,&relocated,8);}
        hb_module *live=nullptr;assert(hb_module_live(argv[n],bias,&live)==HB_ABI_OK);
        assert(live->bytes==m->bytes && live->functions.size()==m->functions.size());hb_module_close(live);
        for(auto seg:m->segments)if(seg.flags&PF_X) {*((char*)memory+seg.address-m->origin)^=1;break;}
        assert(hb_module_live(argv[n],bias,&live)==HB_ABI_INTERFACE_DRIFT && live==nullptr);
        munmap(memory,size);hb_module_close(m);
        auto data=file(argv[n]);char temporary[]="/tmp/hideout-elf64-XXXXXX";int fd=mkstemp(temporary);assert(fd>=0);
        auto *eh=(Elf64_Ehdr*)data.data();eh->e_phoff=UINT64_MAX-1;
        assert(write(fd,data.data(),data.size())==(ssize_t)data.size());close(fd);
        assert(hb_module_file(temporary,&m)==HB_ABI_INVALID_ELF && m==nullptr);unlink(temporary);
    }
    puts("ELF64 RELA, high virtual addresses, high load bias, live parity and malformed bounds passed");
}
