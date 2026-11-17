/* Synthetic i386 contracts, authored for these tests. Contains no Steam binary data. */
.code32
.macro function name
.text
.globl \name
.hidden \name
.type \name,@function
\name:
.cfi_startproc
.endm
.macro end name
.cfi_endproc
.size \name,.-\name
.endm
function empty
ret
end empty
#ifdef ABI_SHIFT
.space 137
#endif
function get_pc
mov (%esp),%ebx
ret
end get_pc
.macro got
call get_pc
.Lpc\@:
addl $_GLOBAL_OFFSET_TABLE_+(.-.Lpc\@),%ebx
.endm
.macro map_getter name,interface
function \name
push %ebp
push %edi
push %esi
push %ebx
got
sub $24,%esp
mov 44(%esp),%ebp
mov 52(%esp),%esi
lea mutex,%edi
push %edi
call pthread_mutex_lock@PLT
mov 52(%esp),%eax
add $16,%esp
mov 148(%ebp),%eax
cmp $-1,%eax
mov 168(%ebp),%ecx
imul $28,%eax,%eax
add %ecx,%eax
mov 16(%eax),%edx
mov 20(%eax),%ebp
mov 24(%eax),%esi
mov 4(%eax),%eax
mov (%eax),%eax
test %esi,%esi
jz .Lother\@
sub $12,%esp
push %edi
call pthread_mutex_unlock@PLT
add $16,%esp
mov \interface(%esi),%eax
add $12,%esp
pop %ebx
pop %esi
pop %edi
pop %ebp
ret
.Lother\@:
sub $12,%esp
push %edi
call pthread_mutex_unlock@PLT
add $16,%esp
add $12,%esp
pop %ebx
pop %esi
pop %edi
pop %ebp
ret
end \name
.endm
map_getter get_user,20
map_getter get_gc,68
function frame
lea frame_name,%eax
ret
end frame
function map_id
push %ebp
mov %esp,%ebp
lea id_name,%eax
mov 8(%ebp),%eax
mov 12(%ebp),%edx
movl $0,(%eax)
movl $0,4(%eax)
pop %ebp
ret $4
end map_id
function owner_id
push %ebx
mov 12(%esp),%edx
mov 8(%esp),%eax
mov 44(%edx),%ebx
mov 40(%edx),%ecx
mov %ebx,4(%eax)
mov %ecx,(%eax)
pop %ebx
ret $4
end owner_id
function send_message
push %ebp
mov %esp,%ebp
lea send_name,%eax
mov 8(%ebp),%eax
mov 12(%ebp),%eax
mov 16(%ebp),%eax
mov 20(%ebp),%eax
mov 24(%ebp),%eax
pop %ebp
ret
end send_message
function receiver
push %ebp
mov %esp,%ebp
mov 8(%ebp),%eax
mov 4(%eax),%eax
mov 12(%ebp),%eax
mov 16(%ebp),%eax
mov 20(%ebp),%eax
mov 24(%ebp),%eax
pop %ebp
ret
end receiver
function handler
push %ebp
mov %esp,%ebp
mov (%edx),%edx
push -12(%edx)
push %edx
push 28(%eax)
push 24(%eax)
mov 8(%ebp),%eax
mov 260(%eax),%eax
add $96,%eax
push %eax
call receiver
add $32,%esp
pop %ebp
ret
end handler
function constructor
push %ebp
mov %esp,%ebp
push %esi
mov 8(%ebp),%esi
mov $client_vtable,%eax
mov %eax,(%esi)
movdqa root_initial,%xmm0
movups %xmm0,148(%esi)
movdqa capacity_initial,%xmm1
movups %xmm1,172(%esi)
movl $0,168(%esi)
pop %esi
pop %ebp
ret
end constructor
function accessor
got
movzbl initialized,%eax
lea singleton+3,%eax
and $-4,%eax
push %eax
call constructor
add $4,%esp
.rept 32
nop
.endr
ret
end accessor
function engine_reference
lea engine_global,%eax
ret
end engine_reference
function engine_thread
lea thread_name,%eax
mov 320(%esi),%edi
ret
end engine_thread
function engine_start
mov %eax,320(%ecx)
ret
end engine_start
.section .rodata
frame_name: .asciz "CSteamClient::RunFrame"
id_name: .asciz "GetSteamID"
send_name: .asciz "SendMessage"
thread_name: .asciz "m_EngineThreadID"
.p2align 4
root_initial: .long -1,0,-1,0
capacity_initial: .long 0,0,1,0
.section .data
#ifdef ABI_SHIFT
.space 93
#endif
.p2align 4
mutex: .zero 32
singleton: .zero 256
initialized: .byte 1
.p2align 2
engine_global: .long 0
/* RTTI follows the Itanium type-name/typeinfo/vtable relationship. */
.macro rtti name,text
.section .rodata
\name\()_name: .asciz "\text"
.section .data.rel.ro
\name\()_type: .long 0,\name\()_name
.long 0,\name\()_type
\name\()_vtable:
.endm
rtti client,12CSteamClient
.set slot,0
.rept 28
.if slot==8
.long get_user
.elseif slot==27
.long get_gc
.elseif slot==19
.long frame
.elseif slot==2
.long engine_reference
.else
.long empty
.endif
.set slot,slot+1
.endr
rtti engine,12CSteamEngine
.set slot,0
.rept 16
.if slot==3
.long engine_thread
.elseif slot==15
.long engine_start
.else
.long empty
.endif
.set slot,slot+1
.endr
rtti user,5CUser
.set slot,0
.rept 141
.if slot==5
.long owner_id
.elseif slot==140
.long engine_reference
.else
.long empty
.endif
.set slot,slot+1
.endr
rtti gc,22CClientGameCoordinator
.rept 3
.long empty
.endr
rtti user_map,14IClientUserMap
.rept 10
.long empty
.endr
.long map_id
rtti gc_map,25IClientGameCoordinatorMap
.long send_message,empty,empty
rtti job,27CClientJobGCMessageReceived
.rept 17
.long empty
.endr
.long handler,empty
.section .note.GNU-stack,"",@progbits
