uint64_t rip(const Instruction &i,unsigned operand) {
    const auto &o=i.op[operand];
    return o.type==ZYDIS_OPERAND_TYPE_MEMORY && o.mem.base==ZYDIS_REGISTER_RIP && o.mem.index==ZYDIS_REGISTER_NONE
        ?i.address+i.info.length+o.mem.disp.value:0;
}
bool mov(const Instruction &i,ZydisRegister to,ZydisRegister from) {
    return i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],to) && reg(i.op[1],from);
}
bool x64_string(const hb_module &m,const std::vector<Instruction> &code,const char *text) {
    for(auto &i:code)if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA) {
        auto a=rip(i,1);size_t n=std::strlen(text)+1;
        if(a && m.mapped(a,n) && !std::memcmp(m.at(a,n),text,n))return true;
    }
    return false;
}
std::string x64_import(const hb_module &m,const Instruction &i) {
    auto a=call(i);if(!a || !m.mapped(a,6,PF_R|PF_X))return {};
    auto jump=instruction(m,a,6);
    if(jump.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(jump.op[0],ZYDIS_REGISTER_R11D) && jump.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE)
        jump=instruction(m,a+jump.info.length,6);
    auto dest=rip(jump,0);
    if(jump.info.mnemonic!=ZYDIS_MNEMONIC_JMP || !m.imports.count(dest))return {};
    return m.imports.at(dest);
}
struct Getter64 { uint64_t mutex=0;uint32_t root=0,nodes=0,stride=0,user=0,pipe=0,node=0,interface=0; };
Getter64 getter64(const hb_module &m,const std::vector<Instruction> &c) {
    cdecl(c);Getter64 g;bool self=false,user=false,pipe=false,left=false,right=false,join=false;unsigned locks=0,unlocks=0;bool mutex_arg=false;
    for(size_t n=0;n<c.size();n++) {
        auto &i=c[n];
        self|=mov(i,ZYDIS_REGISTER_R12,ZYDIS_REGISTER_RDI);
        user|=mov(i,ZYDIS_REGISTER_EBX,ZYDIS_REGISTER_ESI);
        pipe|=mov(i,ZYDIS_REGISTER_EBP,ZYDIS_REGISTER_EDX);
        if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA && reg(i.op[0],ZYDIS_REGISTER_R13)) {
            auto a=rip(i,1);require(a && m.mapped(a,40,PF_R|PF_W),HB_ABI_MAP_DRIFT);g.mutex=a;
        }
        if(mov(i,ZYDIS_REGISTER_RDI,ZYDIS_REGISTER_R13))mutex_arg=true;
        else for(unsigned k=0;k<i.info.operand_count;k++)if((reg(i.op[k],ZYDIS_REGISTER_RDI) || reg(i.op[k],ZYDIS_REGISTER_EDI)) && (i.op[k].actions&ZYDIS_OPERAND_ACTION_MASK_WRITE))mutex_arg=false;
        auto name=x64_import(m,i);
        if(name=="pthread_mutex_lock" || name=="pthread_mutex_unlock") {
            require(self && user && pipe && g.mutex && mutex_arg,HB_ABI_MAP_DRIFT);
            if(name=="pthread_mutex_lock")locks++;else unlocks++;
        }
        if(call(i))mutex_arg=false;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVSXD && reg(i.op[0],ZYDIS_REGISTER_RAX) && i.op[1].mem.base==ZYDIS_REGISTER_R12) {
            require(n+1<c.size() && c[n+1].info.mnemonic==ZYDIS_MNEMONIC_CMP && reg(c[n+1].op[0],ZYDIS_REGISTER_EAX) && imm(c[n+1].op[1],UINT32_MAX),HB_ABI_MAP_DRIFT);
            g.root=(uint32_t)i.op[1].mem.disp.value;
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_RSI) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_R12 && i.op[1].size==64)g.nodes=(uint32_t)i.op[1].mem.disp.value;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_SHL && reg(i.op[0],ZYDIS_REGISTER_RAX) && i.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE) {
            require(i.op[1].imm.value.u>=5 && i.op[1].imm.value.u<=7,HB_ABI_MAP_DRIFT);g.stride=1u<<i.op[1].imm.value.u;
        }
        join|=i.info.mnemonic==ZYDIS_MNEMONIC_LEA && reg(i.op[0],ZYDIS_REGISTER_RCX) && i.op[1].mem.base==ZYDIS_REGISTER_RSI && i.op[1].mem.index==ZYDIS_REGISTER_RAX && i.op[1].mem.scale==1 && !i.op[1].mem.disp.value;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_CMP && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_RCX) {
            if(reg(i.op[0],ZYDIS_REGISTER_EBX))g.user=(uint32_t)i.op[1].mem.disp.value;
            if(reg(i.op[0],ZYDIS_REGISTER_EBP))g.pipe=(uint32_t)i.op[1].mem.disp.value;
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_RBX) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_RSI && i.op[1].mem.index==ZYDIS_REGISTER_RAX && i.op[1].mem.scale==1)g.node=(uint32_t)i.op[1].mem.disp.value;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_RAX) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_RBX)g.interface=(uint32_t)i.op[1].mem.disp.value;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVSXD && reg(i.op[0],ZYDIS_REGISTER_RAX)) {
            left|=mem(i.op[1],ZYDIS_REGISTER_RCX,0);right|=mem(i.op[1],ZYDIS_REGISTER_RCX,4);
        }
    }
    require(locks==1 && unlocks==2 && self && user && pipe && join && left && right && g.root>32 && g.root<4096 && g.nodes>g.root && g.nodes<4096 && g.stride && g.user>=12 && g.pipe==g.user+4 && g.node==g.pipe+4 && g.node+8<=g.stride && g.interface && g.interface<4096,HB_ABI_MAP_DRIFT);
    return g;
}
uint64_t unique64(const std::set<uint64_t> &s,hb_abi_reason r) { require(s.size()==1,r);return *s.begin(); }
void resolve64(const hb_module &m,ResolvedSteamAbi &a) {
    a.client_vtable=vtable(m,"12CSteamClient",28);a.engine_vtable=vtable(m,"12CSteamEngine",16);
    a.user_vtable=vtable(m,"5CUser",141);a.gc_vtable=vtable(m,"22CClientGameCoordinator",3);
    a.user_map_vtable=vtable(m,"14IClientUserMap",11);a.gc_map_vtable=vtable(m,"25IClientGameCoordinatorMap",3);
    auto job=vtable(m,"27CClientJobGCMessageReceived",19);
    a.frame=m.pointer(a.client_vtable+19*8);a.get_user=m.pointer(a.client_vtable+8*8);a.get_gc=m.pointer(a.client_vtable+27*8);
    auto frame=decode(m,a.frame);cdecl(frame);require(x64_string(m,frame,"CSteamClient::RunFrame"),HB_ABI_INTERFACE_DRIFT);
    auto u=getter64(m,decode(m,a.get_user)),g=getter64(m,decode(m,a.get_gc));
    require(u.root==g.root && u.nodes==g.nodes && u.stride==g.stride && u.mutex==g.mutex && u.user==g.user && u.pipe==g.pipe && u.node==g.node && u.interface!=g.interface,HB_ABI_MAP_DRIFT);
    a.map_mutex=u.mutex;a.map_root=u.root;a.map_nodes=u.nodes;a.node_stride=u.stride;a.node_user=u.user;a.node_pipe=u.pipe;a.node_value=u.node;
    a.user_interface=u.interface;a.gc_interface=g.interface;a.map_count=u.root+4;a.map_capacity=u.nodes+8;a.node_left=0;a.node_right=4;
    a.get_id=m.pointer(a.user_map_vtable+10*8);a.owner_get_id=m.pointer(a.user_vtable+5*8);a.send=m.pointer(a.gc_map_vtable);
    auto id=decode(m,a.get_id),owner=decode(m,a.owner_get_id,64),send=decode(m,a.send);
    cdecl(id);cdecl(owner);cdecl(send);
    require(x64_string(m,id,"GetSteamID") && x64_string(m,send,"SendMessage"),HB_ABI_IDENTITY_DRIFT);
    // SysV AMD64 returns this trivially copied eight-byte SteamID in RAX, without an sret argument.
    require(owner.size()==2 && owner[0].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(owner[0].op[0],ZYDIS_REGISTER_RAX) && owner[0].op[1].size==64 && owner[0].op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && owner[0].op[1].mem.base==ZYDIS_REGISTER_RDI && owner[0].op[1].mem.index==ZYDIS_REGISTER_NONE && owner[0].op[1].mem.disp.value>0 && owner[0].op[1].mem.disp.value<65536 && owner[1].info.mnemonic==ZYDIS_MNEMONIC_RET,HB_ABI_IDENTITY_DRIFT);
    bool id_self=false,id_return=false;for(auto &i:id) {id_self|=mov(i,ZYDIS_REGISTER_RBP,ZYDIS_REGISTER_RDI);id_return|=mov(i,ZYDIS_REGISTER_RAX,ZYDIS_REGISTER_RBX);}
    require(id_self && id_return,HB_ABI_IDENTITY_DRIFT);
    bool send_self=false,send_app=false,send_type=false,send_data=false,send_size=false;
    for(auto &i:send) {send_self|=mov(i,ZYDIS_REGISTER_RBP,ZYDIS_REGISTER_RDI);send_app|=mov(i,ZYDIS_REGISTER_R15D,ZYDIS_REGISTER_ESI);send_type|=mov(i,ZYDIS_REGISTER_R14D,ZYDIS_REGISTER_EDX);send_data|=mov(i,ZYDIS_REGISTER_R13,ZYDIS_REGISTER_RCX);send_size|=mov(i,ZYDIS_REGISTER_R12D,ZYDIS_REGISTER_R8D);}
    require(send_self && send_app && send_type && send_data && send_size,HB_ABI_INTERFACE_DRIFT);
    std::set<uint64_t> instances,guards,ctors;
    for(auto entry:m.functions) {
        if(entry.second.size<60 || entry.second.size>192)continue;
        auto c=decode(m,entry.first,192);uint64_t guard=0,instance=0;
        for(size_t n=0;n<c.size();n++) {
            auto &i=c[n];
            if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVZX && reg(i.op[0],ZYDIS_REGISTER_EAX) && i.op[1].size==8)guard=rip(i,1);
            if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA && reg(i.op[0],ZYDIS_REGISTER_RBX) && n+1<c.size() && c[n+1].info.mnemonic==ZYDIS_MNEMONIC_AND && reg(c[n+1].op[0],ZYDIS_REGISTER_RBX) && c[n+1].op[1].imm.value.u==UINT64_MAX-7)instance=rip(i,1)&~7ull;
            auto target=call(i);
            if(!guard || !instance || !target || !m.functions.count(target) || m.functions.at(target).size>8192 || n<2 || !mov(c[n-2],ZYDIS_REGISTER_RDI,ZYDIS_REGISTER_RBX) || c[n-1].info.mnemonic!=ZYDIS_MNEMONIC_MOV || !rip(c[n-1],0) || !imm(c[n-1].op[1],1))continue;
            auto ctor=decode(m,target,8192);bool self=false,load=false,write=false;
            for(auto &ci:ctor) {
                self|=mov(ci,ZYDIS_REGISTER_RBX,ZYDIS_REGISTER_RDI);
                if(ci.info.mnemonic==ZYDIS_MNEMONIC_MOVQ && reg(ci.op[0],ZYDIS_REGISTER_XMM0)) {auto r=rip(ci,1);load=r && m.relative.count(r) && m.pointer(r)==a.client_vtable;}
                if(load && ci.info.mnemonic==ZYDIS_MNEMONIC_MOVUPS && mem(ci.op[0],ZYDIS_REGISTER_RBX,0) && reg(ci.op[1],ZYDIS_REGISTER_XMM0))write=true;
            }
            if(self && write) {instances.insert(instance);guards.insert(guard);ctors.insert(target);}
        }
    }
    a.singleton=unique64(instances,HB_ABI_LIFECYCLE_DRIFT);a.initialized=unique64(guards,HB_ABI_LIFECYCLE_DRIFT);
    auto ctor=decode(m,unique64(ctors,HB_ABI_LIFECYCLE_DRIFT),8192);
    require(m.mapped(a.singleton,a.map_capacity+4,PF_R|PF_W) && m.mapped(a.initialized,1,PF_R|PF_W),HB_ABI_LIFECYCLE_DRIFT);
    std::map<uint32_t,uint32_t> initial;std::map<ZydisRegister,uint64_t> vectors;
    for(auto &i:ctor) {
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVDQA && i.op[0].type==ZYDIS_OPERAND_TYPE_REGISTER && rip(i,1))vectors[i.op[0].reg.value]=rip(i,1);
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVUPS && i.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[0].mem.base==ZYDIS_REGISTER_RBX && i.op[1].type==ZYDIS_OPERAND_TYPE_REGISTER && vectors.count(i.op[1].reg.value)) {
            auto source=vectors.at(i.op[1].reg.value);for(unsigned k=0;k<4;k++)initial[(uint32_t)i.op[0].mem.disp.value+k*4]=m.word(source+k*4);
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && i.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[0].mem.base==ZYDIS_REGISTER_RBX && i.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE) {
            initial[(uint32_t)i.op[0].mem.disp.value]=(uint32_t)i.op[1].imm.value.u;
            if(i.op[0].size==64)initial[(uint32_t)i.op[0].mem.disp.value+4]=(uint32_t)(i.op[1].imm.value.u>>32);
        }
    }
    require(initial.count(a.map_root) && initial[a.map_root]==UINT32_MAX,HB_ABI_MAP_DRIFT);
    for(auto field:{a.map_count,a.map_nodes,a.map_nodes+4,a.map_capacity})require(initial.count(field) && initial[field]==0,HB_ABI_MAP_DRIFT);
    std::set<uint64_t> global1,global2,both;
    for(auto &i:decode(m,m.pointer(a.client_vtable+2*8)))if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA && m.mapped(rip(i,1),8,PF_R|PF_W))global1.insert(rip(i,1));
    for(auto &i:decode(m,m.pointer(a.user_vtable+140*8),128))if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA && m.mapped(rip(i,1),8,PF_R|PF_W))global2.insert(rip(i,1));
    std::set_intersection(global1.begin(),global1.end(),global2.begin(),global2.end(),std::inserter(both,both.end()));a.engine_global=unique64(both,HB_ABI_LIFECYCLE_DRIFT);
    auto thread=decode(m,m.pointer(a.engine_vtable+3*8),512);require(x64_string(m,thread,"m_EngineThreadID"),HB_ABI_LIFECYCLE_DRIFT);
    std::set<uint64_t> fields;for(auto &i:thread)if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_EBP) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_RDI)fields.insert(i.op[1].mem.disp.value);
    a.engine_thread=(uint32_t)unique64(fields,HB_ABI_LIFECYCLE_DRIFT);require(a.engine_thread<65536,HB_ABI_LIFECYCLE_DRIFT);
    bool stored=false;for(auto &i:decode(m,m.pointer(a.engine_vtable+15*8)))stored|=i.info.mnemonic==ZYDIS_MNEMONIC_MOV && mem(i.op[0],ZYDIS_REGISTER_RBX,a.engine_thread) && reg(i.op[1],ZYDIS_REGISTER_EAX);
    require(stored,HB_ABI_LIFECYCLE_DRIFT);
    auto handler=decode(m,m.pointer(job+17*8));cdecl(handler);unsigned candidates=0;
    for(size_t n=7;n<handler.size();n++) {
        auto &i=handler[n];if(!call(i) || i.info.length!=5)continue;
        if(handler[n-2].info.mnemonic!=ZYDIS_MNEMONIC_ADD || !reg(handler[n-2].op[0],ZYDIS_REGISTER_RDI) || handler[n-2].op[1].type!=ZYDIS_OPERAND_TYPE_IMMEDIATE)continue;
        require(handler[n-1].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(handler[n-1].op[0],ZYDIS_REGISTER_R8D) && mem(handler[n-1].op[1],ZYDIS_REGISTER_RCX,-24) &&
            handler[n-3].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(handler[n-3].op[0],ZYDIS_REGISTER_RCX) && mem(handler[n-3].op[1],ZYDIS_REGISTER_RAX,0) &&
            handler[n-5].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(handler[n-5].op[0],ZYDIS_REGISTER_ESI) && mem(handler[n-5].op[1],ZYDIS_REGISTER_RBP,40) &&
            handler[n-6].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(handler[n-6].op[0],ZYDIS_REGISTER_EDX) && mem(handler[n-6].op[1],ZYDIS_REGISTER_RBP,44) &&
            handler[n-7].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(handler[n-7].op[0],ZYDIS_REGISTER_RDI) && handler[n-7].op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && handler[n-7].op[1].mem.base==ZYDIS_REGISTER_R12,HB_ABI_RECEIVE_DRIFT);
        a.owner_gc=(uint32_t)handler[n-2].op[1].imm.value.u;a.receive=call(i);a.receive_call=i.address;candidates++;
    }
    require(candidates==1 && a.owner_gc && a.owner_gc<65536,HB_ABI_RECEIVE_DRIFT);
    auto receiver=decode(m,a.receive);cdecl(receiver);std::set<uint64_t> owners;
    bool self=false,app=false,type=false,data=false,size=false;
    for(auto &i:receiver) {
        self|=mov(i,ZYDIS_REGISTER_RBP,ZYDIS_REGISTER_RDI);app|=mov(i,ZYDIS_REGISTER_R15D,ZYDIS_REGISTER_ESI);type|=mov(i,ZYDIS_REGISTER_R14D,ZYDIS_REGISTER_EDX);size|=mov(i,ZYDIS_REGISTER_R12D,ZYDIS_REGISTER_R8D);
        data|=i.info.mnemonic==ZYDIS_MNEMONIC_MOV && mem(i.op[0],ZYDIS_REGISTER_RSP,0) && reg(i.op[1],ZYDIS_REGISTER_RCX);
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_RDI) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_RBP && i.op[1].mem.disp.value>0 && i.op[1].mem.disp.value<32)owners.insert(i.op[1].mem.disp.value);
    }
    a.gc_owner=(uint32_t)unique64(owners,HB_ABI_RECEIVE_DRIFT);require(self && app && type && data && size,HB_ABI_RECEIVE_DRIFT);
    std::memcpy(a.receive_bytes,m.at(a.receive_call,5,PF_R|PF_X),5);
}
