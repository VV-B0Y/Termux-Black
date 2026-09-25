#define _GNU_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <string.h>
#include <errno.h>

static DIR *(*real_opendir)(const char *name) = NULL;

DIR *opendir(const char *name) {
    if (!real_opendir) {
        real_opendir = (DIR *(*)(const char *))dlsym(RTLD_NEXT, "opendir");
    }
    if (name != NULL) {
        if (strstr(name, "/dev/bus/usb") != NULL || strstr(name, "/sys/bus/usb") != NULL) {
            errno = ENOENT;
            return NULL;
        }
    }
    if (real_opendir) {
        return real_opendir(name);
    }
    errno = EACCES;
    return NULL;
}
