#ifndef HB_STEAM_ADAPTER_H
#define HB_STEAM_ADAPTER_H
#include <stdint.h>
#include <link.h>
/* Installs only before Steam creates user interfaces or starts its engine thread, after structural validation. */
int hb_install_steam_adapter(const struct dl_phdr_info *module, const char *endpoint);
#endif
