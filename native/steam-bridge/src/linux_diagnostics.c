#define _GNU_SOURCE
#include "linux_host.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
uint64_t hb_linux_process_start(long pid) {
    char data[4096];char path[64];snprintf(path,sizeof(path),"/proc/%ld/stat",pid);FILE *f=fopen(path,"r");if(!f)return 0;
    char *ok=fgets(data,sizeof(data),f);fclose(f);if(!ok)return 0;
    char *p=strrchr(data,')');if(!p)return 0;p+=2;
    for(unsigned field=3;field<22;field++) { p=strchr(p,' ');if(!p)return 0;p++; }
    return strtoull(p,NULL,10);
}
void hb_linux_diagnostic_pid(const char *endpoint,long pid,const char *stage,hb_abi_reason reason) {
    char dir[4096],target[4352],temp[4352],body[512];
    if(strlen(endpoint)>=sizeof(dir))return;
    strcpy(dir,endpoint);char *slash=strrchr(dir,'/');if(!slash)return;*slash=0;
    struct stat st;if(lstat(dir,&st) || !S_ISDIR(st.st_mode) || st.st_uid!=getuid() || (st.st_mode&077))return;
    unsigned long long start=hb_linux_process_start(pid);if(!start)return;
    snprintf(target,sizeof(target),"%s/startup-%ld-%llu.json",dir,pid,start);
    snprintf(temp,sizeof(temp),"%s/.startup-%ld-%llu.tmp",dir,pid,start);
    int fd=open(temp,O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW|O_CLOEXEC,0600);if(fd<0)return;
    int n=snprintf(body,sizeof(body),"{\"version\":1,\"pid\":%ld,\"start\":\"%llu\",\"stage\":\"%s\",\"reason\":\"%s\"}\n",pid,start,stage,hb_abi_reason_code(reason));
    int ok=n>0 && (size_t)n<sizeof(body) && write(fd,body,(size_t)n)==n;
    close(fd);if(ok)(void)rename(temp,target);(void)unlink(temp);
}
void hb_linux_diagnostic(const char *endpoint,const char *stage,hb_abi_reason reason) {
    hb_linux_diagnostic_pid(endpoint,(long)getpid(),stage,reason);
}
