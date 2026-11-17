#include "steam_abi.h"
#include "Zydis.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <elf.h>
#include <fstream>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#include <cstdio>
#include <limits>
#include <sys/mman.h>

struct Segment { uint64_t address,size; uint32_t flags; };
struct Function { uint64_t address,size; };
namespace {
struct Failure { hb_abi_reason reason; };
void require(bool ok,hb_abi_reason reason) { if(!ok)throw Failure{reason}; }
bool bounded(uint64_t start,uint64_t size,uint64_t end) { return start<=end && size<=end-start; }
struct Instruction {
    uint64_t address;
    ZydisDecodedInstruction info{};
    ZydisDecodedOperand op[ZYDIS_MAX_OPERAND_COUNT]{};
};
}
struct hb_module {
    std::vector<unsigned char> bytes;
    std::vector<Segment> segments;
    std::map<uint64_t,Function> functions;
    std::map<uint64_t,std::string> imports;
    std::set<uint64_t> relative;
    uint64_t origin=0;
    unsigned bits=0;
    std::string build_id;
    bool mapped(uint64_t a,size_t n,unsigned flags=PF_R) const {
        for(auto s:segments)if((s.flags&flags)==flags && a>=s.address && bounded(a-s.address,n,s.size))return true;
        return false;
    }
    const unsigned char *at(uint64_t a,size_t n=1,unsigned flags=PF_R) const {
        require(mapped(a,n,flags),HB_ABI_INVALID_BOUNDS);return bytes.data()+(a-origin);
    }
    uint32_t word(uint64_t a) const { uint32_t v;std::memcpy(&v,at(a,4),4);return v; }
    uint64_t pointer(uint64_t a) const { if(bits==32)return word(a);uint64_t v;std::memcpy(&v,at(a,8),8);return v; }
    void put_pointer(uint64_t a,uint64_t v) { at(a,bits/8);std::memcpy(bytes.data()+(a-origin),&v,bits/8); }
    std::string string(uint64_t a,size_t max=256) const {
        std::string s;
        for(size_t i=0;i<max;i++) { char c=(char)*at(a+i);if(!c)return s;s+=c; }
        throw Failure{HB_ABI_INVALID_BOUNDS};
    }
};
namespace {
uint32_t uleb(const hb_module &m,uint64_t &p,uint64_t end) {
    uint32_t v=0;
    for(unsigned i=0;i<5 && p<end;i++) { unsigned c=*m.at(p++);require(i<4 || c<16,HB_ABI_INVALID_ELF);v|=(c&127)<<(7*i);if(!(c&128))return v; }
    throw Failure{HB_ABI_INVALID_ELF};
}
uint64_t encoded(const hb_module &m,uint64_t &p,uint8_t enc,uint64_t base) {
    unsigned form=enc&0x0f;
    require(form==0 || form==3 || form==4 || form==0x0b || form==0x0c,HB_ABI_MISSING_UNWIND);
    unsigned width=form==0?m.bits/8:(form==4 || form==0x0c?8:4);
    uint64_t value=0,address=p;std::memcpy(&value,m.at(p,width),width);p+=width;
    bool signed_value=form==0x0b || form==0x0c;
    if(form==0x0b)value=(uint64_t)(int64_t)(int32_t)value;
    uint64_t relative=0;
    switch(enc&0x70) { case 0:break;case 0x10:relative=address;break;case 0x30:relative=base;break;default:throw Failure{HB_ABI_MISSING_UNWIND}; }
    if(relative) {
        if(signed_value && (int64_t)value<0) { uint64_t distance=0-value;require(relative>=distance,HB_ABI_INVALID_BOUNDS);return relative-distance; }
        require(value<=UINT64_MAX-relative,HB_ABI_INVALID_BOUNDS);value+=relative;
    }
    return value;
}
void unwind(hb_module &m,const Elf64_Phdr &hdr) {
    uint64_t start=hdr.p_vaddr,p=start+4;
    const unsigned char *h=m.at(start,4);
    require(h[0]==1 && h[2]==3 && h[3]==0x3b,HB_ABI_MISSING_UNWIND);
    encoded(m,p,h[1],start);uint64_t count=encoded(m,p,h[2],start);
    require(count && count<=300000 && bounded(p-start,(uint64_t)count*8,hdr.p_memsz),HB_ABI_MISSING_UNWIND);
    uint64_t previous=0,previous_end=0;
    for(uint64_t i=0;i<count;i++) {
        uint64_t address=encoded(m,p,h[3],start),fde=encoded(m,p,h[3],start);
        require(address>previous,HB_ABI_MISSING_UNWIND);previous=address;
        uint64_t length=m.word(fde),cp=fde+4;
        require(length>=12 && length<=65536,HB_ABI_MISSING_UNWIND);m.at(fde,length+4);
        uint64_t cie=cp-m.word(cp),cend=cie+4+m.word(cie),c=cie+8;
        require(m.word(cie+4)==0 && cend>=c+1 && cend-c<1024,HB_ABI_MISSING_UNWIND);
        require(*m.at(c++)==1,HB_ABI_MISSING_UNWIND);
        std::string aug=m.string(c,16);c+=(uint64_t)aug.size()+1;
        uleb(m,c,cend);uleb(m,c,cend);require(c<cend,HB_ABI_MISSING_UNWIND);c++;
        uint8_t encoding=0;
        if(!aug.empty()) {
            require(aug[0]=='z',HB_ABI_MISSING_UNWIND);
            uint64_t n=uleb(m,c,cend);require(bounded(c,n,cend),HB_ABI_MISSING_UNWIND);
            uint64_t end=c+n;
            for(size_t k=1;k<aug.size();k++) {
                require(c<end,HB_ABI_MISSING_UNWIND);
                switch(aug[k]) {
                case 'R':encoding=*m.at(c++);break;
                case 'L':c++;break;
                case 'P': { unsigned enc=*m.at(c++);encoded(m,c,(uint8_t)enc,start);break; }
                default:throw Failure{HB_ABI_MISSING_UNWIND};
                }
            }
            require(c<=end,HB_ABI_MISSING_UNWIND);
        }
        uint64_t q=fde+8,pc=encoded(m,q,encoding,start),size=encoded(m,q,encoding&0x0f,start);
        require(address>=previous_end,HB_ABI_MISSING_UNWIND);
        require(pc==address && size && size<=1024*1024 && m.mapped(pc,size,PF_R|PF_X),HB_ABI_MISSING_UNWIND);
        previous_end=pc+size;
        require(m.functions.emplace(pc,Function{pc,size}).second,HB_ABI_MISSING_UNWIND);
    }
}
std::vector<unsigned char> file(const char *path) {
    std::ifstream in(path,std::ios::binary|std::ios::ate);require((bool)in,HB_ABI_INVALID_ELF);
    auto n=in.tellg();require(n>=52 && n<=128*1024*1024,HB_ABI_INVALID_ELF);
    std::vector<unsigned char> data((size_t)n);in.seekg(0);in.read((char*)data.data(),n);require((bool)in,HB_ABI_INVALID_ELF);return data;
}
bool live_readable(uintptr_t start,size_t size) {
    FILE *f=std::fopen("/proc/self/maps","r");if(!f)return false;
    char line[512],mode[5];unsigned long lo,hi;uintptr_t cursor=start;
    while(std::fgets(line,sizeof(line),f))if(std::sscanf(line,"%lx-%lx %4s",&lo,&hi,mode)==3 && mode[0]=='r' && lo<=cursor && cursor<hi) {
        if(size<=hi-cursor) { std::fclose(f);return true; }
        size-=hi-cursor;cursor=hi;
    }
    std::fclose(f);return false;
}
template<class E,class P> std::pair<Elf64_Ehdr,std::vector<Elf64_Phdr>> headers(const std::vector<unsigned char> &data) {
    E e;require(data.size()>=sizeof(e),HB_ABI_INVALID_ELF);std::memcpy(&e,data.data(),sizeof(e));
    require(e.e_ehsize==sizeof(e) && e.e_phnum && e.e_phnum<=128 && e.e_phentsize==sizeof(P) &&
        bounded(e.e_phoff,(uint64_t)e.e_phnum*sizeof(P),data.size()),HB_ABI_INVALID_ELF);
    Elf64_Ehdr h{};h.e_phoff=e.e_phoff;h.e_phnum=e.e_phnum;h.e_phentsize=e.e_phentsize;h.e_ehsize=e.e_ehsize;
    std::vector<Elf64_Phdr> out;
    for(unsigned i=0;i<e.e_phnum;i++) {
        P p;std::memcpy(&p,data.data()+e.e_phoff+i*sizeof(P),sizeof(p));
        out.push_back({p.p_type,p.p_flags,p.p_offset,p.p_vaddr,p.p_paddr,p.p_filesz,p.p_memsz,p.p_align});
    }
    return {h,out};
}
hb_module *module(const char *path,uintptr_t live) {
    auto data=file(path);
    require(!std::memcmp(data.data(),ELFMAG,SELFMAG) && data[EI_DATA]==ELFDATA2LSB && data[EI_VERSION]==EV_CURRENT,HB_ABI_UNSUPPORTED_TARGET);
    uint16_t type,machine;std::memcpy(&type,data.data()+16,2);std::memcpy(&machine,data.data()+18,2);
    bool is64=data[EI_CLASS]==ELFCLASS64;
    require(type==ET_DYN && ((is64 && machine==EM_X86_64) || (data[EI_CLASS]==ELFCLASS32 && machine==EM_386)),HB_ABI_UNSUPPORTED_TARGET);
    auto parsed=is64?headers<Elf64_Ehdr,Elf64_Phdr>(data):headers<Elf32_Ehdr,Elf32_Phdr>(data);
    auto eh=parsed.first;auto ph=parsed.second;
    hb_module m;m.bits=is64?64:32;m.origin=UINT64_MAX;
    uint64_t end=0;Elf64_Phdr dynamic{},ehframe{};
    for(auto p:ph) {
        require(bounded(p.p_offset,p.p_filesz,data.size()) && p.p_filesz<=p.p_memsz && bounded(p.p_vaddr,p.p_memsz,UINT64_MAX),HB_ABI_INVALID_BOUNDS);
        if(p.p_type==PT_LOAD) {
            require(p.p_memsz && (is64 || bounded(p.p_vaddr,p.p_memsz,UINT32_MAX)),HB_ABI_INVALID_BOUNDS);
            for(auto seg:m.segments)require(p.p_vaddr+p.p_memsz<=seg.address || seg.address+seg.size<=p.p_vaddr,HB_ABI_INVALID_BOUNDS);
            m.segments.push_back({p.p_vaddr,p.p_memsz,p.p_flags});end=std::max(end,p.p_vaddr+p.p_memsz);m.origin=std::min(m.origin,p.p_vaddr);
        }
        if(p.p_type==PT_DYNAMIC) { require(!dynamic.p_memsz,HB_ABI_INVALID_ELF);dynamic=p; }
        if(p.p_type==PT_GNU_EH_FRAME) { require(!ehframe.p_memsz,HB_ABI_INVALID_ELF);ehframe=p; }
    }
    require(end>m.origin && end-m.origin<=128*1024*1024 && dynamic.p_memsz && ehframe.p_memsz,HB_ABI_INVALID_BOUNDS);
    m.bytes.resize((size_t)(end-m.origin));
    for(auto p:ph)if(p.p_type==PT_LOAD)std::memcpy(m.bytes.data()+p.p_vaddr-m.origin,data.data()+p.p_offset,p.p_filesz);
    std::map<int64_t,uint64_t> tags;
    unsigned dynsize=is64?sizeof(Elf64_Dyn):sizeof(Elf32_Dyn);bool terminated=false;
    require(dynamic.p_memsz%dynsize==0 && dynamic.p_memsz<=65536,HB_ABI_INVALID_ELF);
    for(uint64_t o=0;o<dynamic.p_memsz;o+=dynsize) {
        int64_t tag;uint64_t value;
        if(is64) { Elf64_Dyn d;std::memcpy(&d,m.at(dynamic.p_vaddr+o,sizeof(d)),sizeof(d));tag=d.d_tag;value=d.d_un.d_val; }
        else { Elf32_Dyn d;std::memcpy(&d,m.at(dynamic.p_vaddr+o,sizeof(d)),sizeof(d));tag=d.d_tag;value=d.d_un.d_val; }
        if(tag==DT_NULL) { terminated=true;break; }
        if(tag!=DT_NEEDED)require(tags.emplace(tag,value).second,HB_ABI_INVALID_ELF);
    }
    require(terminated,HB_ABI_INVALID_ELF);
    unsigned relsize=is64?sizeof(Elf64_Rela):sizeof(Elf32_Rel),symsize=is64?sizeof(Elf64_Sym):sizeof(Elf32_Sym);
    require(tags[is64?DT_RELAENT:DT_RELENT]==relsize && tags[DT_SYMENT]==symsize && tags[DT_PLTREL]==(is64?DT_RELA:DT_REL),HB_ABI_INVALID_ELF);
    auto relocs=[&](uint64_t address,uint64_t size,bool imports) {
        require(size%relsize==0 && size<16*1024*1024,HB_ABI_INVALID_ELF);
        for(uint64_t offset=0;offset<size;offset+=relsize) {
            uint64_t dest,symbol;uint32_t kind;int64_t addend=0;
            if(is64) { Elf64_Rela r;std::memcpy(&r,m.at(address+offset,sizeof(r)),sizeof(r));dest=r.r_offset;kind=ELF64_R_TYPE(r.r_info);symbol=ELF64_R_SYM(r.r_info);addend=r.r_addend; }
            else { Elf32_Rel r;std::memcpy(&r,m.at(address+offset,sizeof(r)),sizeof(r));dest=r.r_offset;kind=ELF32_R_TYPE(r.r_info);symbol=ELF32_R_SYM(r.r_info); }
            m.at(dest,m.bits/8);
            if(kind==(is64?R_X86_64_RELATIVE:R_386_RELATIVE)) {
                require(symbol==0 && m.relative.insert(dest).second,HB_ABI_INVALID_ELF);
                if(is64)m.put_pointer(dest,(uint64_t)addend);
            }
            if(imports && kind==(is64?R_X86_64_JUMP_SLOT:R_386_JMP_SLOT)) {
                require(symbol<UINT64_MAX/symsize && bounded(tags[DT_SYMTAB],symbol*symsize,UINT64_MAX),HB_ABI_INVALID_ELF);
                uint64_t a=tags[DT_SYMTAB]+symbol*symsize;uint32_t name;
                if(is64) { Elf64_Sym sym;std::memcpy(&sym,m.at(a,sizeof(sym)),sizeof(sym));name=sym.st_name; }
                else { Elf32_Sym sym;std::memcpy(&sym,m.at(a,sizeof(sym)),sizeof(sym));name=sym.st_name; }
                require(name<tags[DT_STRSZ] && bounded(tags[DT_STRTAB],tags[DT_STRSZ],UINT64_MAX),HB_ABI_INVALID_ELF);
                m.imports[dest]=m.string(tags[DT_STRTAB]+name,std::min<uint64_t>(256,tags[DT_STRSZ]-name));
            }
        }
    };
    relocs(tags[is64?DT_RELA:DT_REL],tags[is64?DT_RELASZ:DT_RELSZ],false);
    relocs(tags[DT_JMPREL],tags[DT_PLTRELSZ],true);
    if(live) {
        require(end<=UINTPTR_MAX-live && (is64 || (live<=UINT32_MAX && end<=UINT32_MAX-live)),HB_ABI_INVALID_BOUNDS);
        auto header=std::find_if(ph.begin(),ph.end(),[&](const auto &p){ return p.p_type==PT_LOAD && p.p_offset==0 && p.p_filesz>=eh.e_ehsize; });
        require(header!=ph.end(),HB_ABI_INVALID_ELF);
        uintptr_t h=live+header->p_vaddr;
        require(live_readable(h,eh.e_ehsize) && !std::memcmp((const void*)h,data.data(),eh.e_ehsize),HB_ABI_INVALID_BOUNDS);
        size_t phsize=eh.e_phnum*eh.e_phentsize;
        require(bounded(eh.e_phoff,phsize,header->p_filesz) && live_readable(h+eh.e_phoff,phsize) && !std::memcmp((const void*)(h+eh.e_phoff),data.data()+eh.e_phoff,phsize),HB_ABI_INVALID_BOUNDS);
        auto offline=m.bytes;
        for(auto seg:m.segments) {
            require((seg.flags&PF_R) && live_readable(live+seg.address,seg.size),HB_ABI_INVALID_BOUNDS);
            std::memcpy(m.bytes.data()+seg.address-m.origin,(const void*)(live+seg.address),seg.size);
        }
        for(auto a:m.relative) {
            uint64_t v=m.pointer(a);require(v>=live,HB_ABI_INVALID_BOUNDS);m.put_pointer(a,v-live);
        }
        for(auto seg:m.segments)if(!(seg.flags&PF_W))require(!std::memcmp(m.at(seg.address,seg.size),offline.data()+seg.address-m.origin,seg.size),HB_ABI_INTERFACE_DRIFT);
    }
    for(auto p:ph)if(p.p_type==PT_NOTE) {
        uint64_t q=p.p_vaddr,limit=q+p.p_memsz;
        while(q<limit) {
            require(limit-q>=12,HB_ABI_INVALID_ELF);uint32_t names=m.word(q),n=m.word(q+4),type_=m.word(q+8);
            uint64_t namepad=((uint64_t)names+3)&~3ull,desc=((uint64_t)n+3)&~3ull;
            require(namepad+desc<=limit-q-12,HB_ABI_INVALID_ELF);
            if(type_==NT_GNU_BUILD_ID && names==4 && n<=64 && !std::memcmp(m.at(q+12,4),"GNU",4)) {
                static const char hex[]="0123456789abcdef";
                for(uint32_t i=0;i<n;i++) { unsigned c=*m.at(q+12+namepad+i);m.build_id+=hex[c>>4];m.build_id+=hex[c&15]; }
            }
            q+=12+namepad+desc;
        }
    }
    unwind(m,ehframe);return new hb_module(std::move(m));
}
ZydisDecoder decoder(unsigned bits) { ZydisDecoder d;ZydisDecoderInit(&d,bits==64?ZYDIS_MACHINE_MODE_LONG_64:ZYDIS_MACHINE_MODE_LEGACY_32,bits==64?ZYDIS_STACK_WIDTH_64:ZYDIS_STACK_WIDTH_32);return d; }
Instruction instruction(const hb_module &m,uint64_t a,uint64_t available) {
    Instruction i{};i.address=a;auto d=decoder(m.bits);
    require(ZYAN_SUCCESS(ZydisDecoderDecodeFull(&d,m.at(a,available,PF_R|PF_X),available,&i.info,i.op)),HB_ABI_UNSUPPORTED_INSTRUCTION);
    return i;
}
std::vector<Instruction> decode(const hb_module &m,uint64_t address,uint64_t max=65536) {
    auto it=m.functions.find(address);require(it!=m.functions.end() && it->second.size<=max,HB_ABI_MISSING_UNWIND);
    uint64_t end=address+it->second.size;std::vector<Instruction> result;
    while(address<end) { auto i=instruction(m,address,end-address);result.push_back(i);address+=i.info.length; }
    require(address==end,HB_ABI_UNSUPPORTED_INSTRUCTION);return result;
}
bool reg(const ZydisDecodedOperand &o,ZydisRegister r) { return o.type==ZYDIS_OPERAND_TYPE_REGISTER && o.reg.value==r; }
bool imm(const ZydisDecodedOperand &o,uint32_t v) { return o.type==ZYDIS_OPERAND_TYPE_IMMEDIATE && (uint32_t)o.imm.value.u==v; }
bool mem(const ZydisDecodedOperand &o,ZydisRegister r,int32_t displacement) {
    return o.type==ZYDIS_OPERAND_TYPE_MEMORY && o.mem.base==r && o.mem.index==ZYDIS_REGISTER_NONE && o.mem.disp.value==displacement;
}
uint64_t call(const Instruction &i) {
    if(i.info.mnemonic!=ZYDIS_MNEMONIC_CALL || i.op[0].type!=ZYDIS_OPERAND_TYPE_IMMEDIATE || !i.op[0].imm.is_relative)return 0;
    return i.address+i.info.length+i.op[0].imm.value.s;
}
struct Constants : std::array<uint64_t,ZYDIS_REGISTER_MAX_VALUE+1> {
    std::map<int64_t,uint64_t> locals;
    bool reachable=false;
};
constexpr uint64_t unknown=UINT64_MAX;
uint64_t address(const ZydisDecodedOperand &o,const Constants &r) {
    if(o.type!=ZYDIS_OPERAND_TYPE_MEMORY || o.mem.index!=ZYDIS_REGISTER_NONE || o.mem.segment==ZYDIS_REGISTER_GS || o.mem.segment==ZYDIS_REGISTER_FS)return unknown;
    uint64_t base=o.mem.base==ZYDIS_REGISTER_NONE?0:r[o.mem.base];return base==unknown?unknown:(uint32_t)(base+o.mem.disp.value);
}
uint64_t value(const ZydisDecodedOperand &o,const Constants &r) {
    if(o.type==ZYDIS_OPERAND_TYPE_REGISTER)return r[o.reg.value];
    if(o.type==ZYDIS_OPERAND_TYPE_IMMEDIATE)return (uint32_t)o.imm.value.u;
    return unknown;
}
void step(const hb_module &m,const Instruction &i,Constants &r) {
    uint64_t v=unknown;
    if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA)v=address(i.op[1],r);
    if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV) {
        if(i.op[1].type!=ZYDIS_OPERAND_TYPE_MEMORY)v=value(i.op[1],r);
        else if(i.op[1].mem.base==ZYDIS_REGISTER_EBP && i.op[1].mem.index==ZYDIS_REGISTER_NONE && r.locals.count(i.op[1].mem.disp.value))v=r.locals.at(i.op[1].mem.disp.value);
        if(i.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[0].mem.base==ZYDIS_REGISTER_EBP && i.op[0].mem.index==ZYDIS_REGISTER_NONE)r.locals[i.op[0].mem.disp.value]=v;
    }
    if((i.info.mnemonic==ZYDIS_MNEMONIC_ADD || i.info.mnemonic==ZYDIS_MNEMONIC_AND) && i.op[0].type==ZYDIS_OPERAND_TYPE_REGISTER) {
        auto a=value(i.op[0],r),b=value(i.op[1],r);
        if(a!=unknown && b!=unknown)v=i.info.mnemonic==ZYDIS_MNEMONIC_ADD?(uint32_t)(a+b):(uint32_t)(a&b);
    }
    for(unsigned n=0;n<i.info.operand_count;n++)if(i.op[n].type==ZYDIS_OPERAND_TYPE_REGISTER && (i.op[n].actions&ZYDIS_OPERAND_ACTION_MASK_WRITE))r[i.op[n].reg.value]=unknown;
    if(i.op[0].type==ZYDIS_OPERAND_TYPE_REGISTER && v!=unknown)r[i.op[0].reg.value]=v;
    if(auto target=call(i)) {
        r[ZYDIS_REGISTER_EAX]=r[ZYDIS_REGISTER_ECX]=r[ZYDIS_REGISTER_EDX]=unknown;
        if(m.mapped(target,15,PF_R|PF_X)) {
            auto t=instruction(m,target,15);
            if(t.info.mnemonic==ZYDIS_MNEMONIC_MOV && t.op[0].type==ZYDIS_OPERAND_TYPE_REGISTER && mem(t.op[1],ZYDIS_REGISTER_ESP,0) && t.info.length==3 && *m.at(target+3)==0xc3)
                r[t.op[0].reg.value]=i.address+i.info.length;
        }
    }
}
std::vector<Constants> states(const hb_module &m,const std::vector<Instruction> &code) {
    std::vector<Constants> before(code.size());std::vector<bool> seen(code.size());
    for(auto &s:before)s.fill(unknown);
    std::vector<size_t> work{0};seen[0]=true;before[0].reachable=true;unsigned steps=0;
    auto merge=[&](size_t n,const Constants &state) {
        if(!seen[n]) { before[n]=state;seen[n]=true;work.push_back(n);return; }
        bool changed=false;
        for(size_t r=0;r<state.size();r++)if(before[n][r]!=state[r] && before[n][r]!=unknown) { before[n][r]=unknown;changed=true; }
        for(auto &entry:before[n].locals)if(entry.second!=unknown && (!state.locals.count(entry.first) || state.locals.at(entry.first)!=entry.second)) { entry.second=unknown;changed=true; }
        if(changed)work.push_back(n);
    };
    while(!work.empty()) {
        require(++steps<code.size()*32,HB_ABI_UNSUPPORTED_INSTRUCTION);
        size_t n=work.back();work.pop_back();Constants after=before[n];step(m,code[n],after);
        auto category=code[n].info.meta.category;
        if(category==ZYDIS_CATEGORY_COND_BR || category==ZYDIS_CATEGORY_UNCOND_BR) {
            if(code[n].op[0].type==ZYDIS_OPERAND_TYPE_IMMEDIATE && code[n].op[0].imm.is_relative) {
                uint32_t target=code[n].address+code[n].info.length+(uint32_t)code[n].op[0].imm.value.s;
                auto it=std::lower_bound(code.begin(),code.end(),target,[](const Instruction &i,uint32_t t){return i.address<t;});
                if(target>=code.front().address && target<code.back().address+code.back().info.length) {
                    require(it!=code.end() && it->address==target,HB_ABI_UNSUPPORTED_INSTRUCTION);merge((size_t)(it-code.begin()),after);
                }
            }
        }
        if(category!=ZYDIS_CATEGORY_RET && category!=ZYDIS_CATEGORY_UNCOND_BR && n+1<code.size())merge(n+1,after);
    }
    return before;
}
uint64_t vtable(const hb_module &m,const char *name,unsigned slots) {
    std::set<uint64_t> names,types,tables;unsigned width=m.bits/8;
    for(auto s:m.segments)if(!(s.flags&PF_X)) {
        size_t n=std::strlen(name)+1;
        if(s.size<n)continue;
        const auto *begin=m.at(s.address,s.size),*end=begin+s.size,*p=begin;
        while(p<end) {
            p=std::search(p,end,(const unsigned char*)name,(const unsigned char*)name+n);
            if(p==end)break;
            names.insert(s.address+(uint64_t)(p-begin));++p;
        }
    }
    for(auto a:m.relative)if(names.count(m.pointer(a)) && a>=width)types.insert(a-width);
    for(auto a:m.relative)if(types.count(m.pointer(a)) && a>=width && m.pointer(a-width)==0) {
        bool good=true;
        for(unsigned n=0;n<slots;n++)if(!m.mapped(a+width+n*width,width) || !m.functions.count(m.pointer(a+width+n*width)))good=false;
        if(good)tables.insert(a+width);
    }
    require(!tables.empty(),HB_ABI_MISSING_RTTI);require(tables.size()==1,HB_ABI_AMBIGUOUS_RTTI);return *tables.begin();
}
bool contains_string(const hb_module &m,const std::vector<Instruction> &code,const std::string &text) {
    auto before=states(m,code);
    for(size_t k=0;k<code.size();k++) {
        auto &i=code[k];auto r=before[k];if(!r.reachable)continue;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA) {
            auto a=address(i.op[1],r);
            if(a!=unknown && m.mapped((uint32_t)a,text.size()+1) && !std::memcmp(m.at((uint32_t)a,text.size()+1),text.c_str(),text.size()+1))return true;
        }
        step(m,i,r);
    }
    return false;
}
void cdecl(const std::vector<Instruction> &code) {
    bool found=false;
    for(auto &i:code)if(i.info.mnemonic==ZYDIS_MNEMONIC_RET) { require(i.info.operand_count_visible==0,HB_ABI_INTERFACE_DRIFT);found=true; }
    require(found,HB_ABI_INTERFACE_DRIFT);
}
void getter_arguments(const std::vector<Instruction> &code) {
    int esp=0;bool self=false,user=false,pipe=false;
    for(auto &i:code) {
        if(i.info.mnemonic==ZYDIS_MNEMONIC_PUSH)esp-=4;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_POP)esp+=4;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_SUB && reg(i.op[0],ZYDIS_REGISTER_ESP) && i.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE)esp-=(int)i.op[1].imm.value.u;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_ADD && reg(i.op[0],ZYDIS_REGISTER_ESP) && i.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE)esp+=(int)i.op[1].imm.value.u;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_ESP && i.op[1].mem.index==ZYDIS_REGISTER_NONE) {
            int offset=esp+(int)i.op[1].mem.disp.value;
            self|=offset==4 && reg(i.op[0],ZYDIS_REGISTER_EBP);
            user|=offset==8 && reg(i.op[0],ZYDIS_REGISTER_EAX);
            pipe|=offset==12 && reg(i.op[0],ZYDIS_REGISTER_ESI);
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_RET) { require(esp==0,HB_ABI_INTERFACE_DRIFT);break; }
    }
    require(self && user && pipe,HB_ABI_INTERFACE_DRIFT);
}
struct Getter { uint32_t root=0,nodes=0,stride=0,mutex=0,user=0,pipe=0,node=0,interface=0; };
Getter getter(const hb_module &m,const std::vector<Instruction> &c) {
    cdecl(c);getter_arguments(c);Getter g;auto before=states(m,c);unsigned locks=0,unlocks=0;
    for(size_t n=0;n<c.size();n++) {
        auto &i=c[n];auto r=before[n];if(!r.reachable)continue;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA && reg(i.op[0],ZYDIS_REGISTER_EDI)) {
            auto a=address(i.op[1],r);if(a!=unknown && m.mapped((uint32_t)a,24,PF_R|PF_W))g.mutex=(uint32_t)a;
        }
        if(auto target=call(i)) {
            auto plt=instruction(m,target,15);Constants p=r;
            for(unsigned k=0;k<4;k++) {
                if(plt.info.mnemonic==ZYDIS_MNEMONIC_JMP) {
                    auto a=address(plt.op[0],p);
                    if(a!=unknown && m.imports.count((uint32_t)a)) {
                        auto name=m.imports.at((uint32_t)a);
                        if(name=="pthread_mutex_lock" || name=="pthread_mutex_unlock") {
                            require(n && c[n-1].info.mnemonic==ZYDIS_MNEMONIC_PUSH && reg(c[n-1].op[0],ZYDIS_REGISTER_EDI) &&
                                r[ZYDIS_REGISTER_EDI]!=unknown && r[ZYDIS_REGISTER_EDI]==g.mutex,HB_ABI_MAP_DRIFT);
                            if(name=="pthread_mutex_lock")locks++;else unlocks++;
                        }
                    }
                    break;
                }
                step(m,plt,p);plt=instruction(m,plt.address+plt.info.length,15);
            }
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_EBP && i.op[1].mem.index==ZYDIS_REGISTER_NONE && i.op[1].mem.disp.value>0x20) {
            if(reg(i.op[0],ZYDIS_REGISTER_EAX) && n+1<c.size() && c[n+1].info.mnemonic==ZYDIS_MNEMONIC_CMP && imm(c[n+1].op[1],UINT32_MAX))g.root=(uint32_t)i.op[1].mem.disp.value;
            if(reg(i.op[0],ZYDIS_REGISTER_ECX))g.nodes=(uint32_t)i.op[1].mem.disp.value;
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_IMUL && reg(i.op[0],ZYDIS_REGISTER_EAX) && reg(i.op[1],ZYDIS_REGISTER_EAX) && i.op[2].type==ZYDIS_OPERAND_TYPE_IMMEDIATE)g.stride=(uint32_t)i.op[2].imm.value.u;
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_EAX && i.op[1].mem.index==ZYDIS_REGISTER_NONE) {
            if(reg(i.op[0],ZYDIS_REGISTER_EDX))g.user=(uint32_t)i.op[1].mem.disp.value;
            if(reg(i.op[0],ZYDIS_REGISTER_EBP))g.pipe=(uint32_t)i.op[1].mem.disp.value;
            if(reg(i.op[0],ZYDIS_REGISTER_ESI))g.node=(uint32_t)i.op[1].mem.disp.value;
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_EAX) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_ESI && i.op[1].mem.index==ZYDIS_REGISTER_NONE)g.interface=(uint32_t)i.op[1].mem.disp.value;
        step(m,i,r);
    }
    require(locks==1 && unlocks==2 && g.mutex && g.root && g.nodes && g.nodes>g.root && g.stride>=24 && g.stride<=128 && g.user>=12 && g.pipe==g.user+4 && g.node==g.pipe+4 && g.node+4<=g.stride && g.interface,HB_ABI_MAP_DRIFT);
    require(g.root<4096 && g.nodes<4096 && g.interface<4096,HB_ABI_MAP_DRIFT);return g;
}
std::set<uint32_t> writable_leas(const hb_module &m,const std::vector<Instruction> &code) {
    Constants r;r.fill(unknown);std::set<uint32_t> result;
    for(auto &i:code) {
        if(i.info.mnemonic==ZYDIS_MNEMONIC_LEA) { auto a=address(i.op[1],r);if(a!=unknown && m.mapped((uint32_t)a,4,PF_R|PF_W))result.insert((uint32_t)a); }
        step(m,i,r);
    }
    return result;
}
uint32_t unique(const std::set<uint32_t> &values,hb_abi_reason reason) { require(values.size()==1,reason);return *values.begin(); }
#include "steam_abi_x86_64.inc"
void resolve(const hb_module &m,ResolvedSteamAbi &a) {
    if(m.bits==64) { resolve64(m,a);return; }
    a.client_vtable=vtable(m,"12CSteamClient",28);
    a.engine_vtable=vtable(m,"12CSteamEngine",16);
    a.user_vtable=vtable(m,"5CUser",141);
    a.gc_vtable=vtable(m,"22CClientGameCoordinator",3);
    a.user_map_vtable=vtable(m,"14IClientUserMap",11);
    a.gc_map_vtable=vtable(m,"25IClientGameCoordinatorMap",3);
    uint32_t job=vtable(m,"27CClientJobGCMessageReceived",19);
    a.frame=m.word(a.client_vtable+19*4);a.get_user=m.word(a.client_vtable+8*4);a.get_gc=m.word(a.client_vtable+27*4);
    auto frame=decode(m,a.frame);cdecl(frame);
    require(contains_string(m,frame,"CSteamClient::RunFrame"),HB_ABI_INTERFACE_DRIFT);
    auto u=getter(m,decode(m,a.get_user)),g=getter(m,decode(m,a.get_gc));
    require(u.root==g.root && u.nodes==g.nodes && u.stride==g.stride && u.mutex==g.mutex && u.user==g.user && u.pipe==g.pipe && u.node==g.node && u.interface!=g.interface,HB_ABI_MAP_DRIFT);
    a.map_mutex=u.mutex;a.map_root=u.root;a.map_nodes=u.nodes;a.node_stride=u.stride;
    a.node_user=u.user;a.node_pipe=u.pipe;a.node_value=u.node;a.user_interface=u.interface;a.gc_interface=g.interface;
    a.map_count=a.map_root+4;a.map_capacity=a.map_nodes+4;a.node_left=0;a.node_right=4;
    for(auto fn:{a.get_user,a.get_gc}) {
        auto c=decode(m,fn);bool left=false,right=false;
        for(auto &i:c)if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_EAX)) {
            left|=mem(i.op[1],ZYDIS_REGISTER_EAX,0);right|=mem(i.op[1],ZYDIS_REGISTER_EAX,4);
        }
        require(left && right,HB_ABI_MAP_DRIFT);
    }
    a.get_id=m.word(a.user_map_vtable+10*4);a.owner_get_id=m.word(a.user_vtable+5*4);a.send=m.word(a.gc_map_vtable);
    auto id=decode(m,a.get_id),owner_id=decode(m,a.owner_get_id,128),send=decode(m,a.send);
    require(contains_string(m,id,"GetSteamID") && contains_string(m,send,"SendMessage"),HB_ABI_IDENTITY_DRIFT);
    bool sret=false;
    for(auto &i:id)if(i.info.mnemonic==ZYDIS_MNEMONIC_RET) { require(imm(i.op[0],4),HB_ABI_IDENTITY_DRIFT);sret=true; }
    require(sret,HB_ABI_IDENTITY_DRIFT);cdecl(send);
    /* Direct owner getter is a leaf copying two adjacent words to the hidden sret pointer. */
    require(owner_id.size()==9 && owner_id[0].info.mnemonic==ZYDIS_MNEMONIC_PUSH && reg(owner_id[0].op[0],ZYDIS_REGISTER_EBX) &&
        owner_id[1].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(owner_id[1].op[0],ZYDIS_REGISTER_EDX) && mem(owner_id[1].op[1],ZYDIS_REGISTER_ESP,12) &&
        owner_id[2].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(owner_id[2].op[0],ZYDIS_REGISTER_EAX) && mem(owner_id[2].op[1],ZYDIS_REGISTER_ESP,8) &&
        owner_id[3].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(owner_id[3].op[0],ZYDIS_REGISTER_EBX) && owner_id[3].op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && owner_id[3].op[1].mem.base==ZYDIS_REGISTER_EDX &&
        owner_id[4].info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(owner_id[4].op[0],ZYDIS_REGISTER_ECX) && mem(owner_id[4].op[1],ZYDIS_REGISTER_EDX,(int32_t)owner_id[3].op[1].mem.disp.value-4) &&
        owner_id[5].info.mnemonic==ZYDIS_MNEMONIC_MOV && mem(owner_id[5].op[0],ZYDIS_REGISTER_EAX,4) && reg(owner_id[5].op[1],ZYDIS_REGISTER_EBX) &&
        owner_id[6].info.mnemonic==ZYDIS_MNEMONIC_MOV && mem(owner_id[6].op[0],ZYDIS_REGISTER_EAX,0) && reg(owner_id[6].op[1],ZYDIS_REGISTER_ECX) &&
        owner_id[7].info.mnemonic==ZYDIS_MNEMONIC_POP && reg(owner_id[7].op[0],ZYDIS_REGISTER_EBX) &&
        owner_id[8].info.mnemonic==ZYDIS_MNEMONIC_RET && imm(owner_id[8].op[0],4),HB_ABI_IDENTITY_DRIFT);
    std::set<uint32_t> singletons,guards,constructors;
    for(auto entry:m.functions) {
        if(entry.second.size>192 || entry.second.size<60)continue;
        auto code=decode(m,entry.first,192);Constants r;r.fill(unknown);uint32_t guard=0,instance=0;
        for(size_t n=0;n<code.size();n++) {
            auto &i=code[n];
            if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVZX && i.op[1].size==8) {
                auto address_=address(i.op[1],r);if(address_!=unknown && m.mapped((uint32_t)address_,1,PF_R|PF_W))guard=(uint32_t)address_;
            }
            if(i.info.mnemonic==ZYDIS_MNEMONIC_AND && reg(i.op[0],ZYDIS_REGISTER_EAX) && imm(i.op[1],0xfffffffcu) && r[ZYDIS_REGISTER_EAX]!=unknown)instance=(uint32_t)r[ZYDIS_REGISTER_EAX]&~3u;
            if(auto target=call(i))if(guard && instance && m.functions.count(target) && m.functions.at(target).size<8192) {
                auto ctor=decode(m,target,8192);Constants cr;cr.fill(unknown);bool writes=false;
                for(auto &ci:ctor) {
                    if(ci.info.mnemonic==ZYDIS_MNEMONIC_MOV && ci.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && ci.op[0].mem.disp.value==0 && value(ci.op[1],cr)==a.client_vtable)writes=true;
                    step(m,ci,cr);
                }
                if(writes) { singletons.insert(instance);guards.insert(guard);constructors.insert(target); }
            }
            step(m,i,r);
        }
    }
    a.singleton=unique(singletons,HB_ABI_LIFECYCLE_DRIFT);a.initialized=unique(guards,HB_ABI_LIFECYCLE_DRIFT);
    uint32_t ctor=unique(constructors,HB_ABI_LIFECYCLE_DRIFT);
    require(m.mapped(a.singleton,a.map_capacity+4,PF_R|PF_W) && m.mapped(a.initialized,1,PF_R|PF_W),HB_ABI_LIFECYCLE_DRIFT);
    std::map<uint32_t,uint32_t> initial;Constants cr;cr.fill(unknown);uint32_t vector_address[ZYDIS_REGISTER_MAX_VALUE+1]{};
    for(auto &i:decode(m,ctor,8192)) {
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVDQA && i.op[0].type==ZYDIS_OPERAND_TYPE_REGISTER) {
            auto p=address(i.op[1],cr);if(p!=unknown && m.mapped((uint32_t)p,16))vector_address[i.op[0].reg.value]=(uint32_t)p;
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOVUPS && i.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[0].mem.base==ZYDIS_REGISTER_ESI && i.op[1].type==ZYDIS_OPERAND_TYPE_REGISTER) {
            uint32_t p=vector_address[i.op[1].reg.value];if(p)for(unsigned k=0;k<4;k++)initial[(uint32_t)i.op[0].mem.disp.value+k*4]=m.word(p+k*4);
        }
        if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && i.op[0].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[0].mem.base==ZYDIS_REGISTER_ESI && i.op[1].type==ZYDIS_OPERAND_TYPE_IMMEDIATE)initial[(uint32_t)i.op[0].mem.disp.value]=(uint32_t)i.op[1].imm.value.u;
        step(m,i,cr);
    }
    require(initial.count(a.map_root) && initial[a.map_root]==UINT32_MAX && initial.count(a.map_count) && !initial[a.map_count] && initial.count(a.map_nodes) && !initial[a.map_nodes] && initial.count(a.map_capacity) && !initial[a.map_capacity],HB_ABI_MAP_DRIFT);
    auto globals=writable_leas(m,decode(m,m.word(a.client_vtable+2*4)));
    auto user_globals=writable_leas(m,decode(m,m.word(a.user_vtable+140*4),128));std::set<uint32_t> intersection;
    std::set_intersection(globals.begin(),globals.end(),user_globals.begin(),user_globals.end(),std::inserter(intersection,intersection.end()));
    a.engine_global=unique(intersection,HB_ABI_LIFECYCLE_DRIFT);
    auto thread=decode(m,m.word(a.engine_vtable+3*4),512);
    require(contains_string(m,thread,"m_EngineThreadID"),HB_ABI_LIFECYCLE_DRIFT);
    std::set<uint32_t> thread_fields;
    for(auto &i:thread)if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_EDI) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_ESI)thread_fields.insert((uint32_t)i.op[1].mem.disp.value);
    a.engine_thread=unique(thread_fields,HB_ABI_LIFECYCLE_DRIFT);require(a.engine_thread<65536,HB_ABI_LIFECYCLE_DRIFT);
    bool thread_store=false;
    for(auto &i:decode(m,m.word(a.engine_vtable+15*4)))if(i.info.mnemonic==ZYDIS_MNEMONIC_MOV && mem(i.op[0],ZYDIS_REGISTER_ECX,(int32_t)a.engine_thread) && reg(i.op[1],ZYDIS_REGISTER_EAX))thread_store=true;
    require(thread_store,HB_ABI_LIFECYCLE_DRIFT);
    auto handler=decode(m,m.word(job+17*4));cdecl(handler);unsigned candidates=0;
    for(size_t n=10;n+1<handler.size();n++) {
        auto &i=handler[n];if(!call(i) || i.info.length!=5)continue;
        auto &push=handler[n-1],&add=handler[n-2],&owner=handler[n-3],&arg=handler[n-4];
        if(push.info.mnemonic!=ZYDIS_MNEMONIC_PUSH || !reg(push.op[0],ZYDIS_REGISTER_EAX) ||
           add.info.mnemonic!=ZYDIS_MNEMONIC_ADD || !reg(add.op[0],ZYDIS_REGISTER_EAX) || add.op[1].type!=ZYDIS_OPERAND_TYPE_IMMEDIATE ||
           owner.info.mnemonic!=ZYDIS_MNEMONIC_MOV || !reg(owner.op[0],ZYDIS_REGISTER_EAX) || owner.op[1].type!=ZYDIS_OPERAND_TYPE_MEMORY || owner.op[1].mem.base!=ZYDIS_REGISTER_EAX ||
           arg.info.mnemonic!=ZYDIS_MNEMONIC_MOV || !reg(arg.op[0],ZYDIS_REGISTER_EAX) || !mem(arg.op[1],ZYDIS_REGISTER_EBP,8))continue;
        require(handler[n-5].info.mnemonic==ZYDIS_MNEMONIC_PUSH && mem(handler[n-5].op[0],ZYDIS_REGISTER_EAX,24) &&
                handler[n-6].info.mnemonic==ZYDIS_MNEMONIC_PUSH && mem(handler[n-6].op[0],ZYDIS_REGISTER_EAX,28) &&
                handler[n-7].info.mnemonic==ZYDIS_MNEMONIC_PUSH && reg(handler[n-7].op[0],ZYDIS_REGISTER_EDX) &&
                handler[n-8].info.mnemonic==ZYDIS_MNEMONIC_PUSH && mem(handler[n-8].op[0],ZYDIS_REGISTER_EDX,-12) &&
                handler[n+1].info.mnemonic==ZYDIS_MNEMONIC_ADD && reg(handler[n+1].op[0],ZYDIS_REGISTER_ESP) && imm(handler[n+1].op[1],32),HB_ABI_RECEIVE_DRIFT);
        a.owner_gc=(uint32_t)add.op[1].imm.value.u;a.receive=call(i);a.receive_call=i.address;candidates++;
    }
    require(candidates==1 && a.owner_gc && a.owner_gc<65536,HB_ABI_RECEIVE_DRIFT);
    auto receiver=decode(m,a.receive);cdecl(receiver);std::set<uint32_t> owner_fields;
    for(size_t n=1;n<receiver.size();n++) {
        auto &i=receiver[n],&prev=receiver[n-1];
        if(prev.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(prev.op[0],ZYDIS_REGISTER_EAX) && mem(prev.op[1],ZYDIS_REGISTER_EBP,8) &&
           i.info.mnemonic==ZYDIS_MNEMONIC_MOV && reg(i.op[0],ZYDIS_REGISTER_EAX) && i.op[1].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[1].mem.base==ZYDIS_REGISTER_EAX &&
           i.op[1].mem.disp.value>0 && i.op[1].mem.disp.value<16)owner_fields.insert((uint32_t)i.op[1].mem.disp.value);
    }
    a.gc_owner=unique(owner_fields,HB_ABI_RECEIVE_DRIFT);
    /* Both sides of the hook use exactly five cdecl arguments; no receiver entry is modified. */
    std::set<int32_t> arguments;
    for(auto &i:receiver)for(unsigned k=0;k<i.info.operand_count_visible;k++)if(i.op[k].type==ZYDIS_OPERAND_TYPE_MEMORY && i.op[k].mem.base==ZYDIS_REGISTER_EBP && i.op[k].mem.disp.value>=8 && i.op[k].mem.disp.value<=24)arguments.insert((int32_t)i.op[k].mem.disp.value);
    require(arguments==std::set<int32_t>{8,12,16,20,24},HB_ABI_RECEIVE_DRIFT);
    std::memcpy(a.receive_bytes,m.at(a.receive_call,5,PF_R|PF_X),5);
}
}
extern "C" hb_abi_reason hb_module_file(const char *path,hb_module **out) {
    *out=nullptr;try { *out=module(path,0);return HB_ABI_OK; } catch(Failure e) { return e.reason; } catch(...) { return HB_ABI_INVALID_BOUNDS; }
}
extern "C" hb_abi_reason hb_module_live(const char *path,uintptr_t base,hb_module **out) {
    *out=nullptr;try { *out=module(path,base);return HB_ABI_OK; } catch(Failure e) { return e.reason; } catch(...) { return HB_ABI_INVALID_BOUNDS; }
}
extern "C" void hb_module_close(hb_module *m) { delete m; }
extern "C" const char *hb_module_build_id(const hb_module *m) { return m?m->build_id.c_str():""; }
extern "C" hb_abi_reason hb_resolve_steam_abi(const hb_module *m,ResolvedSteamAbi *out) {
    std::memset(out,0,sizeof(*out));
    try { ResolvedSteamAbi result{};resolve(*m,result);*out=result;return HB_ABI_OK; }
    catch(Failure e) { return e.reason; } catch(...) { return HB_ABI_INVALID_BOUNDS; }
}
extern "C" const char *hb_abi_reason_code(hb_abi_reason reason) {
    static const char *codes[]={"compatible","unsupported_target","invalid_elf","invalid_bounds","missing_rtti","ambiguous_rtti","missing_unwind","unsupported_instruction","interface_drift","map_layout_drift","identity_abi_drift","receive_contract_drift","lifecycle_contract_drift","late_attachment","runtime_load_failed","patch_failed","probe_timeout","bridge_allocation_failed","worker_start_failed"};
    return (unsigned)reason<sizeof(codes)/sizeof(codes[0])?codes[reason]:"invalid_reason";
}
