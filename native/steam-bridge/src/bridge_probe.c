#define _POSIX_C_SOURCE 200809L
#include "steam_abi.h"
#include "linux_host.h"
#include <dlfcn.h>
#include <elf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
static void timed_out(int signal_number) {
    (void)signal_number;
    static const char report[]="{\"version\":1,\"compatible\":false,\"reason\":\"probe_timeout\",\"buildId\":\"\"}\n";
    ssize_t written=write(STDOUT_FILENO,report,sizeof(report)-1);(void)written;_exit(1);
}
static int executable_target(const char *path) {
    FILE *f=fopen(path,"rb");if(!f)return 0;
    Elf32_Ehdr e;int ok=fread(&e,1,sizeof(e),f)==sizeof(e);fclose(f);
    return ok && !memcmp(e.e_ident,ELFMAG,4) && e.e_ident[EI_CLASS]==(sizeof(void*)==8?ELFCLASS64:ELFCLASS32) && e.e_ident[EI_DATA]==ELFDATA2LSB && e.e_machine==(sizeof(void*)==8?EM_X86_64:EM_386) && (e.e_type==ET_DYN || e.e_type==ET_EXEC);
}
static int snap_sandbox(void) {
    char profile[128];FILE *f=fopen("/proc/self/attr/current","r");if(!f)return 0;
    int ok=fgets(profile,sizeof(profile),f)!=NULL;fclose(f);
    return ok && !strcmp(profile,"snap.steam.steam (enforce)\n");
}
int main(int argc,char **argv) {
    if(argc==3 && !strcmp(argv[1],"--check-load")) {
        alarm(5);unsetenv("HIDEOUT_STEAM_BRIDGE_ENDPOINT");
        void *library=dlopen(argv[2],RTLD_NOW|RTLD_LOCAL);
        if(!library) {fputs(dlerror(),stderr);return 1;}
        dlclose(library);return 0;
    }
    int require_snap=argc==5 && !strcmp(argv[4],"--require-snap");
    if(argc!=4 && !require_snap && (argc!=7 || strcmp(argv[4],"--startup"))){fputs("usage: bridge_probe STEAM_EXECUTABLE STEAMCLIENT BRIDGE_LIBRARY [--require-snap]\n",stderr);return 2;}
    struct sigaction timeout={0};timeout.sa_handler=timed_out;sigemptyset(&timeout.sa_mask);sigaction(SIGALRM,&timeout,NULL);alarm(8);
    hb_module *m=NULL;ResolvedSteamAbi abi;
    hb_abi_reason reason=require_snap && !snap_sandbox()?HB_ABI_RUNTIME_LOAD:
        executable_target(argv[1])?hb_module_file(argv[2],&m):HB_ABI_UNSUPPORTED_TARGET;
    if(reason==HB_ABI_OK)reason=hb_resolve_steam_abi(m,&abi);
    if(reason==HB_ABI_OK) {
        pid_t child=fork();
        if(child==0) { signal(SIGALRM,SIG_DFL);alarm(5);unsetenv("HIDEOUT_STEAM_BRIDGE_ENDPOINT");void *h=dlopen(argv[3],RTLD_NOW|RTLD_LOCAL);_exit(h?0:1); }
        int status=0;
        if(child<0 || waitpid(child,&status,0)!=child || !WIFEXITED(status) || WEXITSTATUS(status))reason=HB_ABI_RUNTIME_LOAD;
    }
    if(argc==7)hb_linux_diagnostic_pid(argv[5],strtol(argv[6],NULL,10),reason==HB_ABI_OK?"structurally_compatible":"incompatible",reason);
    printf("{\"version\":1,\"compatible\":%s,\"reason\":\"%s\",\"buildId\":\"%s\"}\n",reason==HB_ABI_OK?"true":"false",hb_abi_reason_code(reason),hb_module_build_id(m));
    hb_module_close(m);return reason==HB_ABI_OK?0:1;
}
