/* SysV AMD64 returns the eight-byte SteamID in RAX. The resolver verifies both
   identity getters and all five register arguments at the incoming call site. */
#if !defined(__x86_64__)
#error This adapter requires the x86-64 ABI
#endif
#include "steam_adapter.inc"
