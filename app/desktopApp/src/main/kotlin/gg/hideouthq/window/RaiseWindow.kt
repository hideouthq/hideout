package gg.hideouthq.window

import gg.hideouthq.OnWindows
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun Frame.raiseUnless(leaving: () -> Boolean): Boolean {
    val raised = CompletableFuture<Boolean>()
    EventQueue.invokeLater {
        if (leaving()) {
            raised.complete(false)
        } else {
            if (OnWindows) raise() else activateOwnWindow()
            raised.complete(true)
        }
    }
    return try {
        raised.get(EventThreadWaitMillis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        false
    }
}

/** `setState(NORMAL)` keeps a maximized window maximized. */
private fun Frame.raise() {
    state = Frame.NORMAL
    toFront()
    requestFocus()
}

/**
 * toFront's raise is refused while another window is in use, a fullscreen game too: KWin leaves the window behind, and
 * Mutter gives it the keyboard but leaves it stacked under the game. The request carries the X server's time now, which
 * KWin and Mutter weigh against the last input to the window in use, so it stands: it answers a launch or a link the
 * user has just made.
 */
private fun activateOwnWindow() {
    try {
        Xlib.open()?.use { x ->
            val pid = ProcessHandle.current().pid()
            val wmPid = x.atom("_NET_WM_PID")
            val transientFor = x.atom("WM_TRANSIENT_FOR")
            // A dialog names its owner in WM_TRANSIENT_FOR, and the window manager brings it up with that owner.
            val window = x.property(x.root, x.atom("_NET_CLIENT_LIST")).firstOrNull { x.property(it, wmPid).singleOrNull() == pid && x.property(it, transientFor).isEmpty() }
            if (window != null) x.activate(window)
        }
    } catch (e: Exception) {
        System.err.println("Could not ask for the window: $e")
    } catch (e: LinkageError) {
        System.err.println("Could not ask for the window: $e")
    }
}

/** Xlib on a connection of its own, so nothing here waits on AWT's. The offsets are 64-bit Linux's. */
private class Xlib(private val display: Pointer) : AutoCloseable {
    val root = long("XDefaultRootWindow")

    fun atom(name: String) = long("XInternAtom", name, 0)

    /** Xlib hands out a property's 32-bit items as C longs. */
    fun property(window: Long, property: Long): List<Long> {
        val out = Memory(5L * Long.SIZE_BYTES).apply { clear() }
        val read = int("XGetWindowProperty", window, property, 0L, MaxPropertyItems, 0, AnyPropertyType, out, out.share(8), out.share(16), out.share(24), out.share(32))
        val items = out.getPointer(32) ?: return emptyList()
        try {
            return if (read != Success || out.getInt(8) != 32) emptyList() else items.getLongArray(0, out.getLong(16).toInt()).asList()
        } finally {
            library.getFunction("XFree").invokeInt(arrayOf(items))
        }
    }

    fun activate(window: Long) {
        val time = serverTime() ?: return
        val event = Memory(XEventSize).apply { clear() }
        event.setInt(0, ClientMessage)
        event.setLong(32, window)
        event.setLong(40, atom("_NET_ACTIVE_WINDOW"))
        event.setInt(48, 32)
        event.setLong(56, FromApplication)
        event.setLong(64, time)
        int("XSendEvent", root, 0, SubstructureRedirectMask or SubstructureNotifyMask, event)
        int("XFlush")
    }

    /**
     * The X server stamps every change of a property, and an empty append is a change that leaves nothing behind. This
     * runs on the event thread, and a window the server refused never gets the event, so the wait has an end.
     */
    private fun serverTime(): Long? {
        val window = long("XCreateSimpleWindow", root, 0, 0, 1, 1, 0, 0L, 0L)
        try {
            int("XSelectInput", window, PropertyChangeMask)
            int("XChangeProperty", window, atom("_HIDEOUT_TIME"), XaString, 8, PropModeAppend, null, 0)
            int("XFlush")
            val event = Memory(XEventSize)
            val deadline = System.nanoTime() + ServerTimeWaitNanos
            while (int("XCheckWindowEvent", window, PropertyChangeMask, event) == 0) {
                if (System.nanoTime() > deadline) return null
                Thread.sleep(ServerTimePollMillis)
            }
            return event.getLong(48)
        } finally {
            int("XDestroyWindow", window)
        }
    }

    override fun close() {
        int("XCloseDisplay")
    }

    private fun long(name: String, vararg args: Any?) = library.getFunction(name).invokeLong(arrayOf(display, *args))

    private fun int(name: String, vararg args: Any?) = library.getFunction(name).invokeInt(arrayOf(display, *args))

    companion object {
        // The soname AWT links; the bare libX11.so comes only with the development package.
        private val library by lazy { NativeLibrary.getInstance("libX11.so.6") }

        fun open() = library.getFunction("XOpenDisplay").invokePointer(arrayOf(null))?.let(::Xlib)
    }
}

/**
 * Windows lets only the process the user just started take the foreground, and that is the launch about to exit, not the
 * window it asks to come up. This passes the right on; without it toFront only flashes the taskbar button.
 */
internal fun allowOtherInstanceForeground() {
    if (!OnWindows) return
    try {
        NativeLibrary.getInstance("user32").getFunction("AllowSetForegroundWindow").invokeInt(arrayOf(AnyProcess))
    } catch (e: Exception) {
        System.err.println("Could not pass the foreground on: $e")
    } catch (e: LinkageError) {
        System.err.println("Could not pass the foreground on: $e")
    }
}

private const val AnyProcess = -1
private const val EventThreadWaitMillis = 1000L
private const val ServerTimeWaitNanos = 300_000_000L
private const val ServerTimePollMillis = 5L
private const val Success = 0
private const val AnyPropertyType = 0L
private const val XaString = 31L
private const val PropModeAppend = 2
private const val ClientMessage = 33
private const val FromApplication = 1L
private const val PropertyChangeMask = 1L shl 22
private const val SubstructureNotifyMask = 1L shl 19
private const val SubstructureRedirectMask = 1L shl 20
private const val XEventSize = 192L
private const val MaxPropertyItems = 1024L
