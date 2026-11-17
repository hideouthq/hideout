#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    const char *preload=getenv("LD_PRELOAD");
    if(!preload || strcmp(preload,"libm.so.6") || getenv("HIDEOUT_STEAM_BRIDGE_ENDPOINT") ||
       getenv("HIDEOUT_STEAM_BRIDGE_LIBRARY") || getenv("HIDEOUT_STEAM_BRIDGE_PROBE"))return 1;
    puts("bridge preload removed; unrelated preload preserved; private bootstrap environment cleared");return 0;
}
