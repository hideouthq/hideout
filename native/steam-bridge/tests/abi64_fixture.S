/* Synthetic contracts; no Steam binary bytes. */
.macro function name
.text
.p2align 4
.type \name,@function
\name:
.cfi_startproc
.endm
.macro end name
.cfi_endproc
.size \name,.-\name
.endm
#ifdef ABI_SHIFT
.text
.space 89
#endif
function empty
ret
end empty
function frame
lea frame_name(%rip),%rax
ret
end frame
.macro getter name,interface
function \name
push %r13
lea mutex(%rip),%r13
push %r12
mov %rdi,%r12
mov %r13,%rdi
push %rbp
mov %edx,%ebp
push %rbx
mov %esi,%ebx
sub $8,%rsp
call pthread_mutex_lock@PLT
movslq 216(%r12),%rax
cmp $-1,%eax
je 2f
mov 240(%r12),%rsi
1:
shl $5,%rax
lea (%rsi,%rax),%rcx
cmp 16(%rcx),%ebx
jl 3f
jne 4f
cmp 20(%rcx),%ebp
jl 3f
jg 4f
mov 24(%rsi,%rax),%rbx
mov %r13,%rdi
call pthread_mutex_unlock@PLT
mov \interface(%rbx),%rax
jmp 5f
3:
movslq (%rcx),%rax
cmp $-1,%eax
jne 1b
2:
mov %r13,%rdi
call pthread_mutex_unlock@PLT
xor %eax,%eax
5:
add $8,%rsp
pop %rbx
pop %rbp
pop %r12
pop %r13
ret
4:
movslq 4(%rcx),%rax
jmp 1b
end \name
.endm
getter get_user,24
getter get_gc,120
function map_id
lea id_name(%rip),%rax
mov %rdi,%rbp
mov %rbx,%rax
ret
end map_id
function owner_id
mov 400(%rdi),%rax
ret
end owner_id
function send_message
lea send_name(%rip),%rax
mov %rdi,%rbp
mov %esi,%r15d
mov %edx,%r14d
mov %rcx,%r13
mov %r8d,%r12d
ret
end send_message
function receive_message
mov %rdi,%rbp
mov %esi,%r15d
mov %edx,%r14d
mov %r8d,%r12d
mov %rcx,(%rsp)
mov 8(%rbp),%rdi
ret
end receive_message
function handler
mov %rdi,%r12
mov 24(%rbp),%rax
mov 352(%r12),%rdi
mov 44(%rbp),%edx
mov 40(%rbp),%esi
and $-2,%rax
mov (%rax),%rcx
add $4816,%rdi
mov -24(%rcx),%r8d
call receive_message
ret
end handler
function constructor
mov %rdi,%rbx
movq client_pointer(%rip),%xmm0
movups %xmm0,(%rbx)
movdqa root_initial(%rip),%xmm0
movups %xmm0,216(%rbx)
movq $0,240(%rbx)
movq $0,248(%rbx)
ret
end constructor
function accessor
movzbl initialized(%rip),%eax
lea singleton+7(%rip),%rbx
and $-8,%rbx
mov %rbx,%rdi
movb $1,initialized(%rip)
call constructor
.rept 40
nop
.endr
ret
end accessor
function engine_reference
lea engine_global(%rip),%rax
ret
end engine_reference
function engine_thread
lea thread_name(%rip),%rax
mov 320(%rdi),%ebp
ret
end engine_thread
function engine_start
mov %eax,320(%rbx)
ret
end engine_start
.section .rodata
frame_name: .asciz "CSteamClient::RunFrame"
id_name: .asciz "GetSteamID"
send_name: .asciz "SendMessage"
thread_name: .asciz "m_EngineThreadID"
.p2align 4
root_initial: .long -1,0,-1,0
.section .data
#ifdef ABI_SHIFT
.space 93
#endif
.p2align 4
mutex: .zero 40
.p2align 3
singleton: .zero 512
initialized: .byte 1
.p2align 3
engine_global: .quad 0
.section .data.rel.ro
client_pointer: .quad client_vtable
.macro rtti name,text
.section .rodata
\name\()_name: .asciz "\text"
.section .data.rel.ro
\name\()_type: .quad 0,\name\()_name
.quad 0,\name\()_type
\name\()_vtable:
.endm
rtti client,12CSteamClient
.set slot,0
.rept 28
.if slot==8
.quad get_user
.elseif slot==27
.quad get_gc
.elseif slot==19
.quad frame
.elseif slot==2
.quad engine_reference
.else
.quad empty
.endif
.set slot,slot+1
.endr
rtti engine,12CSteamEngine
.set slot,0
.rept 16
.if slot==3
.quad engine_thread
.elseif slot==15
.quad engine_start
.else
.quad empty
.endif
.set slot,slot+1
.endr
rtti user,5CUser
.set slot,0
.rept 141
.if slot==5
.quad owner_id
.elseif slot==140
.quad engine_reference
.else
.quad empty
.endif
.set slot,slot+1
.endr
rtti gc,22CClientGameCoordinator
.rept 3
.quad empty
.endr
rtti user_map,14IClientUserMap
.rept 10
.quad empty
.endr
.quad map_id
rtti gc_map,25IClientGameCoordinatorMap
.quad send_message,empty,empty
rtti job,27CClientJobGCMessageReceived
.rept 17
.quad empty
.endr
.quad handler,empty
.section .note.GNU-stack,"",@progbits
