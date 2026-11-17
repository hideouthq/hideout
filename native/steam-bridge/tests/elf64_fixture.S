.text
.globl fixture_function
.hidden fixture_function
.type fixture_function,@function
fixture_function:
.cfi_startproc
    sub $8,%rsp
.cfi_adjust_cfa_offset 8
    call puts@PLT
    add $8,%rsp
.cfi_adjust_cfa_offset -8
    ret
.cfi_endproc
.size fixture_function,.-fixture_function
.data
.globl fixture_pointer
fixture_pointer:
    .quad fixture_function
.section .note.GNU-stack,"",@progbits
