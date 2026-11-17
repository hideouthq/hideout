package gg.hideouthq.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object HideoutIcons {
    val Discover: ImageVector by lazy { strokedIcon("Discover", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM15.5 8.5l-2 5l-5 2l2-5z") }

    val Search: ImageVector by lazy { strokedIcon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14zM20 20l-4-4") }

    val Layers: ImageVector by lazy { strokedIcon("Layers", "M12 3l9 5l-9 5l-9-5zM3 12.5l9 5l9-5M3 17l9 5l9-5") }

    /** Hideout's own update: an arrow rising to a line, apart from [Download] and from a mod's update. */
    val AppUpdate: ImageVector by lazy { strokedIcon("AppUpdate", "M12 19V8M7 12.5l5-5l5 5M5 4h14") }

    val Restart: ImageVector by lazy { strokedIcon("Restart", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3M4.5 4.5v3.6h3.6") }

    val ArrowUpRight: ImageVector by lazy { strokedIcon("ArrowUpRight", "M9 7h8v8M17 7L7 17") }

    val PlusBold: ImageVector by lazy { strokedIcon("PlusBold", "M12 5v14M5 12h14", strokeWidth = 2.2f) }

    val TrendUp: ImageVector by lazy { strokedIcon("TrendUp", "M4 16l5-5l4 4l7-7M14 8h6v6", strokeWidth = 2.2f) }

    val Wrench: ImageVector by lazy { strokedIcon("Wrench", "M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3l5.3-5.3a4 4 0 0 0 5.4-5.4l-2.4 2.4l-2.6-.6l-.6-2.6z", strokeWidth = 2.2f) }

    val Blank: ImageVector by lazy { iconBuilder("Blank").build() }

    val Refresh: ImageVector by lazy { strokedIcon("Refresh", "M20 12a8 8 0 1 1-2.6-5.9M20 4v5h-5") }

    val Download: ImageVector by lazy { strokedIcon("Download", "M12 4v11M7 11l5 5l5-5M5 20h14") }
    val CloudDownload: ImageVector by lazy { strokedIcon("CloudDownload", "M7 18H6a4 4 0 0 1-.8-7.9a7 7 0 0 1 13.6 0A4 4 0 0 1 18 18h-1M12 12v9M9 18l3 3l3-3") }

    val Settings: ImageVector by lazy { strokedIcon("Settings", "M4 7h10M18 7h2M4 17h2M10 17h10M16 5v4M8 15v4") }

    val Plus: ImageVector by lazy { strokedIcon("Plus", "M12 5v14M5 12h14") }

    val Monitor: ImageVector by lazy { strokedIcon("Monitor", "M3 5h18v12H3zM8 21h8M12 17v4") }

    val Play: ImageVector by lazy { filledIcon("Play", "M8 5.5v13l11-6.5z") }

    val Pause: ImageVector by lazy { filledIcon("Pause", "M7 5h3v14H7zM14 5h3v14h-3z") }

    val Back: ImageVector by lazy { strokedIcon("Back", "M19 12H5M11 6l-6 6l6 6") }

    val Forward: ImageVector by lazy { strokedIcon("Forward", "M5 12h14M13 6l6 6l-6 6") }

    val MoreHorizontal: ImageVector by lazy { strokedIcon("MoreHorizontal", "M6 12h.01M12 12h.01M18 12h.01", strokeWidth = 2.6f) }

    val DragHandle: ImageVector by lazy { strokedIcon("DragHandle", "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01", strokeWidth = 2.8f) }

    val Cube: ImageVector by lazy { strokedIcon("Cube", "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5") }

    val Sound: ImageVector by lazy { strokedIcon("Sound", "M4 10v4h4l5 4V6L8 10zM16.5 9a4 4 0 0 1 0 6") }

    val Check: ImageVector by lazy { strokedIcon("Check", "M5 12.5l4.5 4.5L19 7.5") }

    val CheckBold: ImageVector by lazy { strokedIcon("CheckBold", "M5 12.5l4.5 4.5L19 7.5", strokeWidth = 2.6f) }

    val Minus: ImageVector by lazy { strokedIcon("Minus", "M6 12h12") }

    val Close: ImageVector by lazy { strokedIcon("Close", "M6 6l12 12M18 6L6 18") }

    val ChevronRight: ImageVector by lazy { strokedIcon("ChevronRight", "M9 6l6 6l-6 6") }
    val ChevronLeft: ImageVector by lazy { strokedIcon("ChevronLeft", "M15 6l-6 6l6 6") }
    val ChevronUp: ImageVector by lazy { strokedIcon("ChevronUp", "M7 14l5-5l5 5", strokeWidth = 2.2f) }

    val Filter: ImageVector by lazy { strokedIcon("Filter", "M4 6h16M7 12h10M10 18h4") }

    val Person: ImageVector by lazy { strokedIcon("Person", "M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8zM4 21a8 8 0 0 1 16 0") }

    val Palette: ImageVector by lazy { strokedIcon("Palette", "M12 3a9 9 0 1 0 0 18c1.1 0 2-.9 2-2c0-.5-.2-1-.5-1.3c-.3-.4-.5-.8-.5-1.3c0-1.1.9-2 2-2h2.4A4.6 4.6 0 0 0 21 11c0-4.4-4-8-9-8zM7.5 11.5h.01M10 7.5h.01M14.5 7.5h.01") }
    val Cards: ImageVector by lazy { strokedIcon("Cards", "M4 5h7v8H4zM13 5h7v8h-7zM4 17h7M13 17h7M4 20h4M13 20h4") }
    val Server: ImageVector by lazy { strokedIcon("Server", "M4 4.5h16v6H4zM4 13.5h16v6H4zM7.5 7.5h.01M7.5 16.5h.01M11 7.5h5M11 16.5h5") }
    val Grid: ImageVector by lazy { strokedIcon("Grid", "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z") }

    val Shield: ImageVector by lazy { strokedIcon("Shield", "M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6z") }

    val Warning: ImageVector by lazy { strokedIcon("Warning", "M12 4l9 16H3zM12 10v4M12 17h.01") }

    val Info: ImageVector by lazy { strokedIcon("Info", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 11v6M12 7.5h.01") }

    val Clock: ImageVector by lazy { strokedIcon("Clock", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 7v5l3 2") }

    val Folder: ImageVector by lazy { strokedIcon("Folder", "M3 6h6l2 2h10v11H3z") }

    val Trash: ImageVector by lazy { strokedIcon("Trash", "M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }

    val Rename: ImageVector by lazy { strokedIcon("Rename", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4") }

    val Duplicate: ImageVector by lazy { strokedIcon("Duplicate", "M8 8h12v12H8zM16 8V4H4v12h4") }

    val InGame: ImageVector by lazy { filledIcon("InGame", "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8z") }

    val Discord: ImageVector by lazy {
        iconBuilder("Discord").addPath(addPathNodes("M20.317 4.3698a19.7913 19.7913 0 0 0 -4.8851-1.5152.0741.0741 0 0 0 -.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 0 0 -.0785-.037 19.7363 19.7363 0 0 0 -4.8852 1.515.0699.0699 0 0 0 -.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0 -.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1 -.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1 -.0066.1276 12.2986 12.2986 0 0 1 -1.873.8914.0766.0766 0 0 0 -.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0 -.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"), fill = PlaceholderPaint).build()
    }

    val OpenExternal: ImageVector by lazy { strokedIcon("OpenExternal", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6") }

    val SwapVersions: ImageVector by lazy { strokedIcon("SwapVersions", "M7 7h11l-3-3M17 17H6l3 3") }

    val FileText: ImageVector by lazy { strokedIcon("FileText", "M5 4h10l4 4v12H5zM14 4v5h5M9 13h6M9 17h4") }

    val Box: ImageVector by lazy { strokedIcon("Box", "M4 7h16v13H4zM9 11h6M4 7l2-3h12l2 3") }

    val Globe: ImageVector by lazy { strokedIcon("Globe", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18") }

    val Image: ImageVector by lazy { strokedIcon("Image", "M4 5h16v14H4zM4 15l4-4l4 4l3-3l5 5M15 9h.01") }

    val Book: ImageVector by lazy { strokedIcon("Book", "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11") }

    val Braces: ImageVector by lazy {
        strokedIcon("Braces", "M8 4c-2 0-3 1-3 3v2c0 1.5-1 2-2 3c1 1 2 1.5 2 3v2c0 2 1 3 3 3M16 4c2 0 3 1 3 3v2c0 1.5 1 2 2 3c-1 1-2 1.5-2 3v2c0 2-1 3-3 3")
    }

    val Reset: ImageVector by lazy { strokedIcon("Reset", "M4 12a8 8 0 1 0 2.6-5.9M4 4v5h5") }

    val Fullscreen: ImageVector by lazy { strokedIcon("Fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5") }

    val NowPlaying: ImageVector by lazy { strokedIcon("NowPlaying", "M6 10v6M12 6v12M18 9v8") }

    val Loop: ImageVector by lazy { strokedIcon("Loop", "M17 3l3 3l-3 3M4 11V9a3 3 0 0 1 3-3h13M7 21l-3-3l3-3M20 13v2a3 3 0 0 1-3 3H4") }

    val WindowMinimize: ImageVector by lazy { strokedIcon("WindowMinimize", "M6 12h12") }

    val WindowMaximize: ImageVector by lazy { strokedIcon("WindowMaximize", "M7 7h10v10H7z") }

    val WindowRestore: ImageVector by lazy { strokedIcon("WindowRestore", "M8 9h8v8H8zM10 9V7h8v8h-2") }

    val WindowClose: ImageVector by lazy { strokedIcon("WindowClose", "M7 7l10 10M17 7L7 17") }

    val NavStats: ImageVector by lazy { strokedIcon("NavStats", "M6 20v-7M12 20V5M18 20v-10") }
    val Crosshairs: ImageVector by lazy { strokedIcon("Crosshairs", "M12 5a7 7 0 1 0 0 14a7 7 0 0 0 0-14zM12 2v6M12 16v6M2 12h6M16 12h6") }
    val Autoexec: ImageVector by lazy { strokedIcon("Autoexec", "M4 5h16v14H4zM8 10l3 2.5L8 15M13 15h4") }
    val Import: ImageVector by lazy { strokedIcon("Import", "M9 4h6v3H9zM7 5H5v15h14V5h-2M9 12h6M9 16h4") }
    val Share: ImageVector by lazy { strokedIcon("Share", "M12 3v12M7 8l5-5l5 5M5 14v6h14v-6") }
    val PanelCollapse: ImageVector by lazy { strokedIcon("PanelCollapse", "M4 5h16v14H4zM9 5v14M15 10l-2 2l2 2") }
    val PanelExpand: ImageVector by lazy { strokedIcon("PanelExpand", "M4 5h16v14H4zM9 5v14M13 10l2 2l-2 2") }

    val StatsOverview: ImageVector by lazy { strokedIcon("StatsOverview", "M4 5h7v7H4zM13 5h7v4h-7zM13 11h7v8h-7zM4 14h7v5H4z") }

    val StatsMatches: ImageVector by lazy { strokedIcon("StatsMatches", "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01") }

    val StatsTeammates: ImageVector by lazy {
        strokedIcon("StatsTeammates", "M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7zM2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 14a6.5 6.5 0 0 1 3.5 6")
    }

    val Period: ImageVector by lazy { strokedIcon("Period", "M5 5h14v15H5zM9 3v4M15 3v4M5 10h14") }

    val Since: ImageVector by lazy { strokedIcon("Since", "M5 4h14v16H5zM9 2v4M15 2v4M5 9h14") }

    val Streak: ImageVector by lazy { strokedIcon("Streak", "M12 3v3M12 18v3M3 12h3M18 12h3M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8z") }

    val ArrowUp: ImageVector by lazy { strokedIcon("ArrowUp", "M12 19V5M6 11l6-6l6 6", strokeWidth = 2.4f) }

    val ArrowDown: ImageVector by lazy { strokedIcon("ArrowDown", "M12 5v14M6 13l6 6l6-6", strokeWidth = 2.4f) }

    val Copy: ImageVector by lazy { strokedIcon("Copy", "M8 8h11v11H8zM5 15V5h10") }

    val Gamepad: ImageVector by lazy { strokedIcon("Gamepad", "M7.5 7h9a4.5 4.5 0 0 1 4.5 4.5v2a3.5 3.5 0 0 1-6.3 2.1L13.5 14h-3l-1.2 1.6A3.5 3.5 0 0 1 3 13.5v-2A4.5 4.5 0 0 1 7.5 7zM8 9.5v4M6 11.5h4M15.5 10.5h.01M17.5 12.5h.01") }

    val Link: ImageVector by lazy { strokedIcon("Link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }

    val Users: ImageVector by lazy { strokedIcon("Users", "M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7zM3 20a6 6 0 0 1 12 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 14.5a6 6 0 0 1 3 5.5") }

    val Hook: ImageVector by lazy { strokedIcon("Hook", "M12 3h4M14 3v10.5a5 5 0 0 1-10 0V11l3 3") }

    val Souls: ImageVector by lazy { strokedIcon("Souls", "M12 3.5c3.2 4 6 7.2 6 10.5a6 6 0 0 1-12 0c0-3.3 2.8-6.5 6-10.5z") }

    val Trophy: ImageVector by lazy { strokedIcon("Trophy", "M7 4h10v5a5 5 0 0 1-10 0zM7 6H4v1a3.5 3.5 0 0 0 3.5 3.5M17 6h3v1a3.5 3.5 0 0 1-3.5 3.5M12 14v4M8 20h8") }

    val Stop: ImageVector by lazy { strokedIcon("Stop", "M6 6h12v12H6z") }

    val WifiOff: ImageVector by lazy { strokedIcon("WifiOff", "M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 13a10 10 0 0 1 5-2.7M14 10.4a10 10 0 0 1 5 2.6M2 9.5a15 15 0 0 1 4.5-2.8M10.5 5.1a15 15 0 0 1 11.5 4.4M12 20h.01") }

    val Message: ImageVector by lazy { strokedIcon("Message", "M4 5h16v11H9l-5 4z") }

    val Activity: ImageVector by lazy { strokedIcon("Activity", "M3 12h4l2-6l4 12l2-6h6") }

    val Verified: ImageVector by lazy { strokedIcon("Verified", "M12 3l2.4 1.8l3-.2l.9 2.9l2.5 1.7l-1 2.8l1 2.8l-2.5 1.7l-.9 2.9l-3-.2L12 21l-2.4-1.8l-3 .2l-.9-2.9l-2.5-1.7l1-2.8l-1-2.8l2.5-1.7l.9-2.9l3 .2zM8.5 12l2.5 2.5l4.5-5") }

    val ChevronDown: ImageVector by lazy { strokedIcon("ChevronDown", "M6 9l6 6l6-6") }

    val Shirt: ImageVector by lazy { strokedIcon("Shirt", "M8.5 4L4 7l2 4l2-1v10h8V10l2 1l2-4l-4.5-3c-.6 1.6-1.9 2.6-3.5 2.6S9.1 5.6 8.5 4z") }

    val ViewList: ImageVector by lazy { strokedIcon("ViewList", "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01", strokeWidth = 2f) }

    val AutoSpin: ImageVector by lazy { strokedIcon("AutoSpin", "M20 12a8 8 0 1 1-2.6-5.9M20 4v5h-5M12 9v3l2 1.5") }

    val SaveImage: ImageVector by lazy { strokedIcon("SaveImage", "M4 8h3l2-3h6l2 3h3v11H4zM12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7z") }

    val PatchNotes: ImageVector by lazy { strokedIcon("PatchNotes", "M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3l5.3-5.3a4 4 0 0 0 5.4-5.4l-2.4 2.4l-2.6-.6l-.6-2.6z") }

    val Announcement: ImageVector by lazy { strokedIcon("Announcement", "M4 10v4h3l7 4V6l-7 4zM17 9a4 4 0 0 1 0 6M7 14l1 5h3l-1-5") }

    val ObjectiveGuardian: ImageVector by lazy { strokedIcon("ObjectiveGuardian", "M12 3l7 3v5c0 4.5-3 8-7 10c-4-2-7-5.5-7-10V6z", ObjectiveStrokeWidth) }
    val ObjectiveWalker: ImageVector by lazy { strokedIcon("ObjectiveWalker", "M7 21V10L5 8V4h3v2h2V4h4v2h2V4h3v4l-2 2v11zM10 21v-4a2 2 0 0 1 4 0v4", ObjectiveStrokeWidth) }
    val ObjectiveBaseGuardian: ImageVector by lazy { strokedIcon("ObjectiveBaseGuardian", "M3 21V7h4v3h3V5h4v5h3V7h4v14zM10 21v-4a2 2 0 0 1 4 0v4", ObjectiveStrokeWidth) }
    val ObjectiveShrine: ImageVector by lazy { strokedIcon("ObjectiveShrine", "M12 3l6.5 9L12 21l-6.5-9zM12 8v8", ObjectiveStrokeWidth) }

    val ObjectivePatron: ImageVector by lazy { strokedIcon("ObjectivePatron", PatronPath, ObjectiveStrokeWidth) }
    val ObjectivePatronDestroyed: ImageVector by lazy { filledIcon("ObjectivePatronDestroyed", PatronPath, ObjectiveStrokeWidth) }
    val ObjectiveMidBoss: ImageVector by lazy {
        strokedIcon("ObjectiveMidBoss", "M12 3a8 7 0 0 0-5 12.4V19h10v-3.6A8 7 0 0 0 12 3zM9.5 11h.01M14.5 11h.01M10.5 19v2M13.5 19v2", ObjectiveStrokeWidth)
    }

    val TriangleUp: ImageVector by lazy { filledIcon("TriangleUp", "M12 6l7 11H5z", strokeWidth = null) }
    val TriangleDown: ImageVector by lazy { filledIcon("TriangleDown", "M12 18L5 7h14z", strokeWidth = null) }

    val SectionHero: ImageVector by lazy { strokedIcon("SectionHero", "M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8zM4 21a8 8 0 0 1 16 0", SectionStrokeWidth) }
    val SectionSkins: ImageVector by lazy { strokedIcon("SectionSkins", "M8.5 4L4 7l2 4l2-1v10h8V10l2 1l2-4l-4.5-3c-.6 1.6-1.9 2.6-3.5 2.6S9.1 5.6 8.5 4z", SectionStrokeWidth) }
    val SectionGameplay: ImageVector by lazy { strokedIcon("SectionGameplay", "M13 3L5 14h6l-1 7l8-11h-6z", SectionStrokeWidth) }
    val SectionHud: ImageVector by lazy { strokedIcon("SectionHud", "M4 5h16v14H4zM4 15h16M9 15v4M15 15v4", SectionStrokeWidth) }
    val SectionSounds: ImageVector by lazy { strokedIcon("SectionSounds", "M9 18V6l10-2v12M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0zM19 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z", SectionStrokeWidth) }
    val SectionCollections: ImageVector by lazy { strokedIcon("SectionCollections", "M6 3h12M4 7h16M3 11h18v10H3z", SectionStrokeWidth) }
    val SectionNews: ImageVector by lazy { strokedIcon("SectionNews", "M4 6h13v13H6a2 2 0 0 1-2-2zM17 9h3v8a2 2 0 0 1-2 2M7.5 10h6M7.5 13h6M7.5 16h4", SectionStrokeWidth) }
}

private const val SectionStrokeWidth = 2f
private const val ObjectiveStrokeWidth = 2f
private const val PatronPath = "M4 8l4.5 4L12 5l3.5 7L20 8l-2 11H6z"

private const val ViewportSize = 24f
internal const val DefaultStrokeWidth = 1.8f
private val PlaceholderPaint = SolidColor(Color.Black)

private fun strokedIcon(name: String, pathData: String, strokeWidth: Float = DefaultStrokeWidth): ImageVector =
    iconBuilder(name)
        .addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = PlaceholderPaint,
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

private fun filledIcon(name: String, pathData: String, strokeWidth: Float? = DefaultStrokeWidth): ImageVector =
    iconBuilder(name)
        .addPath(
            pathData = addPathNodes(pathData),
            fill = PlaceholderPaint,
            stroke = if (strokeWidth != null) PlaceholderPaint else null,
            strokeLineWidth = strokeWidth ?: 0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

private fun iconBuilder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = ViewportSize.dp,
    defaultHeight = ViewportSize.dp,
    viewportWidth = ViewportSize,
    viewportHeight = ViewportSize,
)
