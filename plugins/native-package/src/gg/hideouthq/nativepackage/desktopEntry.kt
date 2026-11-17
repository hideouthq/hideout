package gg.hideouthq.nativepackage

/**
 * The menu entry of every Linux format. [exec] and [icon] are where that format puts the executable and the icon. AWT
 * names its X11 window class after the main class, dots as dashes, and desktops match windows to entries by it. Listing
 * the scheme's handler type is all a desktop needs to send links here, and an empty `%u` from the menu adds no argument.
 */
internal fun desktopEntry(settings: NativePackageSettings, mainClass: String, exec: String, icon: String): String = """
    [Desktop Entry]
    Type=Application
    Name=${settings.name}
    Comment=${settings.description}
    Exec=$exec %u
    MimeType=x-scheme-handler/${settings.urlScheme};
    Icon=$icon
    Terminal=false
    Categories=Game;
    StartupWMClass=${mainClass.replace('.', '-')}
""".trimIndent() + "\n"
