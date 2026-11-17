#define _GNU_SOURCE
#include "linux_host.h"
#include "linux_transport.h"
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
static atomic_uint online,game_running,tick_due,rebind,worker_state,game_epoch;
static hb_bridge *core;
static pthread_t thread;
static char endpoint_path[4096];
int hb_linux_online(void) { return atomic_load(&online)!=0; }
int hb_linux_game_running(void) { return atomic_load(&game_running)!=0; }
uint32_t hb_linux_game_epoch(void) { return atomic_load(&game_epoch); }
int hb_linux_take_tick(void) { return atomic_exchange(&tick_due,0)!=0; }
int hb_linux_take_rebind(void) { return atomic_exchange(&rebind,0)!=0; }
void hb_linux_retry_tick(void) { atomic_store(&rebind,1);atomic_store(&tick_due,1); }
void hb_linux_connected(int value) {
    atomic_store(&online,value!=0);atomic_store(&rebind,1);atomic_store(&tick_due,1);
}
void hb_linux_background_tick(void) {
    static long previous_pid;static uint64_t previous_start;
    unsigned running=0;long game_pid=0;uint64_t game_start=0;
    {
        DIR *processes=opendir("/proc");struct dirent *entry;
        if(processes) {
            while((entry=readdir(processes))) {
                if(entry->d_name[0]<'0'||entry->d_name[0]>'9')continue;
                char path[320],name[4096];snprintf(path,sizeof(path),"/proc/%s/cmdline",entry->d_name);
                struct stat st;if(stat(path,&st)||st.st_uid!=getuid())continue;
                FILE *f=fopen(path,"r");if(!f)continue;
                size_t length=fread(name,1,sizeof(name)-1,f);name[length]=0;fclose(f);
                const char *base=strrchr(name,'/'),*windows=strrchr(name,'\\');
                if(!base || (windows && windows>base))base=windows;
                base=base?base+1:name;
                if(!strcmp(base,"deadlock.exe") || !strcmp(base,"deadlock")) {
                    game_pid=strtol(entry->d_name,NULL,10);game_start=hb_linux_process_start(game_pid);running=game_start!=0;
                }
                if(running)break;
            }
            closedir(processes);
        }
    }
    if(game_pid!=previous_pid || game_start!=previous_start) {
        atomic_fetch_add(&game_epoch,1);previous_pid=game_pid;previous_start=game_start;
    }
    atomic_store(&game_running,running);atomic_store(&tick_due,1);
    hb_session session={0};if(!hb_get_session(core,&session))return;
    const char *stage=!session.generation?"waiting_for_game":hb_gc_readiness(core)==HB_GC_READY?"collecting":"waiting_for_gc";
    hb_linux_diagnostic(endpoint_path,stage,HB_ABI_OK);
}
static void *worker(void *unused) {
    (void)unused;struct timespec delay={0,1000000};
    while(!atomic_load_explicit(&worker_state,memory_order_acquire))nanosleep(&delay,NULL);
    if(atomic_load(&worker_state)!=1)return NULL;
    atomic_int stop;atomic_init(&stop,0);delay.tv_sec=1;delay.tv_nsec=0;
    for(;;) { hb_linux_background_tick();hb_linux_transport(core,endpoint_path,&stop);nanosleep(&delay,NULL); }
    return NULL;
}
int hb_linux_prepare_worker(hb_bridge *b,const char *endpoint) {
    if(strlen(endpoint)>=sizeof(endpoint_path))return 0;
    core=b;strcpy(endpoint_path,endpoint);atomic_store(&worker_state,0);
    return pthread_create(&thread,NULL,worker,NULL)==0;
}
void hb_linux_commit_worker(void) { atomic_store_explicit(&worker_state,1,memory_order_release);pthread_detach(thread); }
void hb_linux_cancel_worker(void) { atomic_store_explicit(&worker_state,2,memory_order_release);pthread_join(thread,NULL);core=NULL; }
