/* GCC i386 SysV returns SteamID via a hidden result pointer, popped by the callee.
   The shared resolver verifies this before any interface call. */
#if !defined(__i386__)
#error This adapter requires the i386 ABI
#endif
#include "steam_adapter.inc"
