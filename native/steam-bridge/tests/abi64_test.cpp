#include "../src/steam_abi.cpp"
#include <cassert>
int main(int argc,char **argv) {
    assert(argc==3);hb_module *original=nullptr,*shifted=nullptr;
    assert(hb_module_file(argv[1],&original)==HB_ABI_OK);
    assert(hb_module_file(argv[2],&shifted)==HB_ABI_OK);
    ResolvedSteamAbi a{},b{};
    assert(hb_resolve_steam_abi(original,&a)==HB_ABI_OK);
    assert(hb_resolve_steam_abi(shifted,&b)==HB_ABI_OK);
    assert(a.frame!=b.frame && a.singleton!=b.singleton);
    assert(a.map_root==216 && a.map_nodes==240 && a.map_count==220 && a.map_capacity==248);
    assert(a.node_stride==32 && a.node_value==24 && a.gc_owner==8 && a.owner_gc==4816);
    auto reason=[](hb_module &m){ResolvedSteamAbi abi{};return hb_resolve_steam_abi(&m,&abi);};
    auto changed=*original;changed.build_id="unrelated";assert(reason(changed)==HB_ABI_OK);
    changed=*original;changed.put_pointer(a.client_vtable+27*8,a.get_user);assert(reason(changed)==HB_ABI_MAP_DRIFT);
    changed=*original;changed.bytes[a.owner_get_id]=0x90;assert(reason(changed)==HB_ABI_IDENTITY_DRIFT);
    changed=*original;std::fill_n(changed.bytes.begin()+a.receive_call,5,0x90);assert(reason(changed)==HB_ABI_RECEIVE_DRIFT);
    changed=*original;
    for(auto &i:decode(changed,a.get_user))if(mov(i,ZYDIS_REGISTER_EBX,ZYDIS_REGISTER_ESI)){changed.bytes[i.address+1]^=1;break;}
    assert(reason(changed)==HB_ABI_MAP_DRIFT);
    changed=*original;changed.functions.erase(a.frame);assert(reason(changed)!=HB_ABI_OK);
    size_t size=original->bytes.size();void *memory=mmap(nullptr,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && (uintptr_t)memory>UINT32_MAX);memcpy(memory,original->bytes.data(),size);
    for(auto address:original->relative){uint64_t value=original->pointer(address)+(uintptr_t)memory;memcpy((char*)memory+address,&value,8);}
    hb_module *live=nullptr;assert(hb_module_live(argv[1],(uintptr_t)memory,&live)==HB_ABI_OK);
    assert(hb_resolve_steam_abi(live,&b)==HB_ABI_OK && !memcmp(&a,&b,sizeof(a)));
    hb_module_close(live);munmap(memory,size);hb_module_close(original);hb_module_close(shifted);
    puts("x86-64 relocation parity, shifted layouts, identity ABI, arguments and receive contract drift passed");
}
