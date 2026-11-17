#include "../src/steam_abi.cpp"
#include <cassert>
#include <sys/mman.h>
#include <unistd.h>
#include <iostream>
#include <fcntl.h>
static hb_abi_reason resolve_reason(const hb_module &m) { ResolvedSteamAbi a;return hb_resolve_steam_abi(&m,&a); }
static void put(hb_module &m,uint32_t a,uint32_t v) { std::memcpy(m.bytes.data()+a,&v,4); }
int main(int argc,char **argv) {
    assert(argc==3);hb_module *original=nullptr;
    assert(hb_module_file(argv[1],&original)==HB_ABI_OK);
    hb_module *shifted=nullptr;assert(hb_module_file(argv[2],&shifted)==HB_ABI_OK);
    ResolvedSteamAbi shifted_abi;assert(hb_resolve_steam_abi(shifted,&shifted_abi)==HB_ABI_OK);
    ResolvedSteamAbi a;assert(hb_resolve_steam_abi(original,&a)==HB_ABI_OK);
    assert(a.frame!=shifted_abi.frame && a.singleton!=shifted_abi.singleton && original->build_id!=shifted->build_id);
    hb_module_close(shifted);
    assert(a.node_stride==28 && a.map_root==148 && a.map_count==152 && a.map_capacity==172);
    assert(a.gc_owner==4 && a.owner_gc==96 && a.engine_thread==320);
    auto changed=*original;changed.build_id="an unrelated build";assert(resolve_reason(changed)==HB_ABI_OK);
    changed=*original;put(changed,a.client_vtable+27*4,a.get_user);assert(resolve_reason(changed)==HB_ABI_MAP_DRIFT);
    changed=*original;
    auto getter_code=decode(changed,a.get_user);
    for(size_t n=6;n<getter_code.size();n++)if(call(getter_code[n]) && getter_code[n-1].info.mnemonic==ZYDIS_MNEMONIC_PUSH && reg(getter_code[n-1].op[0],ZYDIS_REGISTER_EDI)) {
        changed.bytes[getter_code[n-1].address]=0x50;break;
    }
    assert(resolve_reason(changed)==HB_ABI_MAP_DRIFT);
    changed=*original;changed.bytes[a.receive_call]=0x90;assert(resolve_reason(changed)!=HB_ABI_OK);
    changed=*original;auto ret=decode(changed,a.owner_get_id).back();changed.bytes[ret.address+1]=8;
    assert(resolve_reason(changed)==HB_ABI_IDENTITY_DRIFT);
    changed=*original;changed.functions.erase(a.frame);assert(resolve_reason(changed)!=HB_ABI_OK);
    changed=*original;changed.bytes[a.frame]=0x0f;changed.bytes[a.frame+1]=0xff;assert(resolve_reason(changed)!=HB_ABI_OK);
    changed=*original;uint32_t spare=(uint32_t)changed.bytes.size();
    changed.bytes.resize(spare+256);changed.segments.push_back({spare,256,PF_R|PF_W});
    put(changed,spare,0);put(changed,spare+4,changed.word(a.client_vtable-4));changed.relative.insert(spare+4);
    for(unsigned n=0;n<28;n++)put(changed,spare+8+n*4,changed.word(a.client_vtable+n*4));
    assert(resolve_reason(changed)==HB_ABI_AMBIGUOUS_RTTI);
    size_t size=original->bytes.size();int flags=MAP_PRIVATE|MAP_ANONYMOUS;
#ifdef MAP_32BIT
    flags|=MAP_32BIT;
#endif
    void *memory=mmap(nullptr,size,PROT_READ|PROT_WRITE,flags,-1,0);assert(memory!=MAP_FAILED);
    assert((uintptr_t)memory+size<=UINT32_MAX);std::memcpy(memory,original->bytes.data(),size);
    for(auto offset:original->relative) { uint32_t v=original->word(offset)+(uint32_t)(uintptr_t)memory;std::memcpy((char*)memory+offset,&v,4); }
    hb_module *live=nullptr;assert(hb_module_live(argv[1],(uintptr_t)memory,&live)==HB_ABI_OK);
    ResolvedSteamAbi b;assert(hb_resolve_steam_abi(live,&b)==HB_ABI_OK);assert(!std::memcmp(&a,&b,sizeof(a)));
    hb_module_close(live);munmap(memory,size);hb_module_close(original);
    auto data=file(argv[1]);
    char temporary[]="/tmp/hideout-elf-fixture-XXXXXX";int fd=mkstemp(temporary);assert(fd>=0);
    auto bad=data;((Elf32_Ehdr*)bad.data())->e_phoff=UINT32_MAX;
    assert(write(fd,bad.data(),bad.size())==(ssize_t)bad.size());close(fd);
    hb_module *invalid=nullptr;assert(hb_module_file(temporary,&invalid)==HB_ABI_INVALID_ELF && !invalid);
    fd=open(temporary,O_WRONLY|O_TRUNC);assert(fd>=0);bad=data;bad[EI_CLASS]=ELFCLASS64;
    assert(write(fd,bad.data(),bad.size())==(ssize_t)bad.size());close(fd);
    assert(hb_module_file(temporary,&invalid)==HB_ABI_UNSUPPORTED_TARGET && !invalid);unlink(temporary);
    std::cout<<"synthetic relocation/load-bias parity, ambiguity, ABI drift, missing unwind and unsupported instructions passed\n";
}
