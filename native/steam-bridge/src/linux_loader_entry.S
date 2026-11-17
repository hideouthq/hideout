/* Preserve libc's caller identity for every unrelated library load. */
.text
.hidden hb_dlopen_target
.hidden hb_dlmopen_target
.macro entry name,target,args
.p2align 4
.globl \name
.type \name,@function
\name:
.cfi_startproc
#if defined(__x86_64__)
    pushq %rdi
    .cfi_adjust_cfa_offset 8
    pushq %rsi
    .cfi_adjust_cfa_offset 8
    pushq %rdx
    .cfi_adjust_cfa_offset 8
    call \target
    popq %rdx
    .cfi_adjust_cfa_offset -8
    popq %rsi
    .cfi_adjust_cfa_offset -8
    popq %rdi
    .cfi_adjust_cfa_offset -8
    jmp *%rax
#elif defined(__i386__)
    .if \args == 2
    subl $4,%esp
    .cfi_adjust_cfa_offset 4
    .else
    pushl 12(%esp)
    .cfi_adjust_cfa_offset 4
    .endif
    pushl 12(%esp)
    .cfi_adjust_cfa_offset 4
    pushl 12(%esp)
    .cfi_adjust_cfa_offset 4
    call \target
    addl $12,%esp
    .cfi_adjust_cfa_offset -12
    jmp *%eax
#else
#error Unsupported Steam architecture
#endif
.cfi_endproc
.size \name,.-\name
.endm
entry dlopen,hb_dlopen_target,2
entry dlmopen,hb_dlmopen_target,3
.section .note.GNU-stack,"",@progbits
