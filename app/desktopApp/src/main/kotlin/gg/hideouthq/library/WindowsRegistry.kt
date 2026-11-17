package gg.hideouthq.library

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinReg

internal object WindowsRegistry : Registry {
    override fun string(hive: RegistryHive, key: String, value: String): String? = try {
        val root = when (hive) {
            RegistryHive.CurrentUser -> WinReg.HKEY_CURRENT_USER
            RegistryHive.LocalMachine -> WinReg.HKEY_LOCAL_MACHINE
        }
        if (Advapi32Util.registryValueExists(root, key, value)) {
            Advapi32Util.registryGetStringValue(root, key, value)
        } else {
            null
        }
    } catch (e: Win32Exception) {
        null
    }
}
