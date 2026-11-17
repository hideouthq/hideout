#define _GNU_SOURCE
#include <dlfcn.h>
int caller_load(int namespace_load) {
    void *library=namespace_load?dlmopen(LM_ID_BASE,"libloader_dependency.so",RTLD_NOW):dlopen("libloader_dependency.so",RTLD_NOW);
    if(!library)return 1;
    int (*value)(void)=dlsym(library,"fixture_value");
    int result=value && value()==42?0:2;
    dlclose(library);return result;
}
