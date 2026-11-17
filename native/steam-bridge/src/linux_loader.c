#define _GNU_SOURCE
#include "steam_adapter.h"
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdatomic.h>

static void *(*real_dlopen)(const char *,int);
static void *(*real_dlmopen)(Lmid_t,const char *,int);
static pthread_once_t resolve_once=PTHREAD_ONCE_INIT;
static int enabled;
static atomic_int attempted;
static atomic_flag installing=ATOMIC_FLAG_INIT;
static int (*libc_setenv)(const char *,const char *,int);
static int (*libc_unsetenv)(const char *);
static char endpoint[PATH_MAX];
static void resolve(void) {
#if defined(__i386__)
    real_dlopen=dlvsym(RTLD_NEXT,"dlopen","GLIBC_2.1");
    libc_setenv=dlvsym(RTLD_NEXT,"setenv","GLIBC_2.0");
    libc_unsetenv=dlvsym(RTLD_NEXT,"unsetenv","GLIBC_2.0");
#else
    real_dlopen=dlvsym(RTLD_NEXT,"dlopen","GLIBC_2.2.5");
    libc_setenv=dlvsym(RTLD_NEXT,"setenv","GLIBC_2.2.5");
    libc_unsetenv=dlvsym(RTLD_NEXT,"unsetenv","GLIBC_2.2.5");
#endif
    real_dlmopen=dlvsym(RTLD_NEXT,"dlmopen","GLIBC_2.3.4");
}
static void inspect_handle(void *handle) {
    struct link_map *map=NULL;
    if(!enabled || atomic_load(&attempted) || !handle || dlinfo(handle,RTLD_DI_LINKMAP,&map))return;
    if(atomic_flag_test_and_set(&installing))return;
    for(struct link_map *m=map;m;m=m->l_next) {
        const char *name=strrchr(m->l_name,'/');name=name?name+1:m->l_name;
        if(strcmp(name,"steamclient.so"))continue;
        struct dl_phdr_info info={.dlpi_addr=m->l_addr,.dlpi_name=m->l_name};
        atomic_store(&attempted,1);
        int installed=hb_install_steam_adapter(&info,endpoint);
        const char *message=installed
            ? "Hideout Steam bridge: adapter armed\n" : "Hideout Steam bridge: adapter inactive; see startup diagnostics\n";
        ssize_t written=write(STDERR_FILENO,message,strlen(message));(void)written;break;
    }
    atomic_flag_clear(&installing);
}
static void *tracked_dlopen(const char *path,int flags) {
    void *handle=real_dlopen(path,flags);
    if(enabled && !atomic_load(&attempted) && handle && path) {
        const char *name=strrchr(path,'/');name=name?name+1:path;
        if(!strcmp(name,"steamclient.so"))inspect_handle(handle);
    }
    return handle;
}
static void *tracked_dlmopen(Lmid_t namespace,const char *path,int flags) {
    void *handle=real_dlmopen(namespace,path,flags);
    if(path) {
        const char *name=strrchr(path,'/');name=name?name+1:path;
        if(!strcmp(name,"steamclient.so"))inspect_handle(handle);
    }
    return handle;
}
static int tracked_path(const char *path) {
    if(!enabled || atomic_load(&attempted) || !path || path[0]!='/')return 0;
    const char *name=strrchr(path,'/');
    return name && !strcmp(name+1,"steamclient.so");
}
static void *unavailable_dlopen(const char *path,int flags) { (void)path;(void)flags;return NULL; }
static void *unavailable_dlmopen(Lmid_t ns,const char *path,int flags) { (void)ns;(void)path;(void)flags;return NULL; }
typedef void *(*dlopen_function)(const char *,int);
typedef void *(*dlmopen_function)(Lmid_t,const char *,int);
/* The assembly entry points tail-jump to these selected functions. libc uses its
 * caller's return address for RUNPATH and namespace lookup. An ordinary C wrapper
 * changes that identity, even when it simply forwards the same arguments. Only
 * the selected absolute Steam module load needs a return-time callback. */
__attribute__((visibility("hidden"))) dlopen_function hb_dlopen_target(const char *path,int flags) {
    (void)flags;pthread_once(&resolve_once,resolve);
    return !real_dlopen?unavailable_dlopen:tracked_path(path)?tracked_dlopen:real_dlopen;
}
__attribute__((visibility("hidden"))) dlmopen_function hb_dlmopen_target(Lmid_t ns,const char *path,int flags) {
    (void)ns;(void)flags;pthread_once(&resolve_once,resolve);
    return !real_dlmopen?unavailable_dlmopen:tracked_path(path)?tracked_dlmopen:real_dlmopen;
}
__attribute__((constructor)) static void hideout_bridge_load(void) {
    pthread_once(&resolve_once,resolve);
    if(!libc_setenv || !libc_unsetenv)return;
    Dl_info own;char executable[PATH_MAX];
    ssize_t n=readlink("/proc/self/exe",executable,sizeof(executable)-1);
    if(n<=0)return;
    executable[n]=0;
    if(dladdr((void*)hideout_bridge_load,&own) && own.dli_fname) {
        const char *preload=getenv("LD_PRELOAD");
        if(preload) {
            char *copy=strdup(preload),*clean=calloc(strlen(preload)+1,1);
            if(copy && clean) {
                char *save=NULL;
                const char *injected=getenv("HIDEOUT_STEAM_BRIDGE_LIBRARY");
                for(char *part=strtok_r(copy," :\t\n",&save);part;part=strtok_r(NULL," :\t\n",&save)) {
                    if(!strcmp(part,own.dli_fname) || (injected && !strcmp(part,injected)))continue;
                    if(*clean)strcat(clean,":");
                    strcat(clean,part);
                }
                if(!*clean || libc_setenv("LD_PRELOAD",clean,1))libc_unsetenv("LD_PRELOAD");
            } else libc_unsetenv("LD_PRELOAD");
            free(copy);free(clean);
        }
    }
    const char *ep=getenv("HIDEOUT_STEAM_BRIDGE_ENDPOINT");
    const char *suffix=sizeof(void*)==8?"/steamrt64/steam":"/ubuntu12_32/steam";size_t length=strlen(executable),tail=strlen(suffix);
    if(real_dlopen && ep && ep[0]=='/' && strlen(ep)<sizeof(endpoint) && length>=tail && !strcmp(executable+length-tail,suffix)) {
        strcpy(endpoint,ep);enabled=1;
    }
    libc_unsetenv("HIDEOUT_STEAM_BRIDGE_ENDPOINT");
    libc_unsetenv("HIDEOUT_STEAM_BRIDGE_LIBRARY");
    libc_unsetenv("HIDEOUT_STEAM_BRIDGE_PROBE");
}
