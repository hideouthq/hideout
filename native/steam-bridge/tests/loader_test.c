#include <stdio.h>
int caller_load(int namespace_load);
int main(void) {
    for(int n=0;n<2;n++)if(caller_load(n)) {
        fprintf(stderr,"%s lost the caller's RUNPATH\n",n?"dlmopen":"dlopen");return 1;
    }
    return 0;
}
