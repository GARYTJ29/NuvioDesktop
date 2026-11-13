package com.nuvio.app.features.power

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.ActiveDesktopPlayer
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import javax.swing.SwingUtilities

/**
 * What the power menu, its timer and the idle check finally do: quit the app,
 * sleep the PC, or shut it down. A port of the shell's src/utils/power.cpp.
 *
 * `-Dnuvio.power.dryRun=true` logs sleep and shutdown instead of doing them.
 */
internal object PowerActions {
    private val log = Logger.withTag("Power")

    /** The app's own orderly exit (Main.kt's closeApplication). */
    @Volatile
    var quitHandler: (() -> Unit)? = null

    private val dryRun: Boolean
        get() = System.getProperty("nuvio.power.dryRun")?.toBoolean() == true

    fun run(action: PowerAction) {
        log.i { "power action ${action.id}${if (dryRun) " (dry run)" else ""}" }
        when (action) {
            PowerAction.Quit -> SwingUtilities.invokeLater { quitHandler?.invoke() }
            PowerAction.Sleep, PowerAction.Shutdown -> {
                // Freeze the picture first, the way the shell did, so it is not
                // left playing to a sleeping or departing user.
                SwingUtilities.invokeLater { runCatching { ActiveDesktopPlayer.controller?.pause() } }
                Thread({
                    Thread.sleep(250)
                    if (dryRun) return@Thread
                    if (action == PowerAction.Sleep) sleep() else shutdown()
                }, "nuvio-power-action").apply { isDaemon = true }.start()
            }
        }
    }

    private fun sleep() {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        val ok = runCatching {
            PowrProf.INSTANCE.SetSuspendState(0, 0, 0) != 0.toByte()
        }.getOrDefault(false)
        if (!ok) log.w { "SetSuspendState failed" }
    }

    private fun shutdown() {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        val ok = runCatching {
            enableShutdownPrivilege()
            User32Power.INSTANCE.ExitWindowsEx(
                EWX_SHUTDOWN or EWX_FORCEIFHUNG,
                SHTDN_REASON_MAJOR_APPLICATION or SHTDN_REASON_MINOR_MAINTENANCE or SHTDN_REASON_FLAG_PLANNED,
            )
        }.getOrDefault(false)
        if (!ok) {
            log.w { "ExitWindowsEx failed, falling back to shutdown.exe" }
            runCatching { ProcessBuilder("shutdown", "/s", "/t", "0").start() }
                .onFailure { error -> log.e(error) { "shutdown failed" } }
        }
    }

    private fun enableShutdownPrivilege() {
        val token = PointerByReference()
        val process = Kernel32Power.INSTANCE.GetCurrentProcess()
        if (!Advapi32Power.INSTANCE.OpenProcessToken(process, TOKEN_ADJUST_PRIVILEGES or TOKEN_QUERY, token)) return
        try {
            val privileges = TokenPrivileges()
            privileges.privilegeCount = 1
            privileges.attributes = SE_PRIVILEGE_ENABLED
            if (!Advapi32Power.INSTANCE.LookupPrivilegeValueW(null, "SeShutdownPrivilege".toCharArray() + '\u0000', privileges.luid)) return
            privileges.write()
            Advapi32Power.INSTANCE.AdjustTokenPrivileges(token.value, false, privileges, 0, null, null)
        } finally {
            Kernel32Power.INSTANCE.CloseHandle(token.value)
        }
    }

    private const val EWX_SHUTDOWN = 0x00000001
    private const val EWX_FORCEIFHUNG = 0x00000010
    private const val SHTDN_REASON_MAJOR_APPLICATION = 0x00040000
    private const val SHTDN_REASON_MINOR_MAINTENANCE = 0x00000001
    private const val SHTDN_REASON_FLAG_PLANNED = 0x80000000.toInt()
    private const val TOKEN_ADJUST_PRIVILEGES = 0x0020
    private const val TOKEN_QUERY = 0x0008
    private const val SE_PRIVILEGE_ENABLED = 0x00000002
}

@Structure.FieldOrder("lowPart", "highPart")
internal class Luid : Structure() {
    @JvmField var lowPart: Int = 0
    @JvmField var highPart: Int = 0
}

@Structure.FieldOrder("privilegeCount", "luid", "attributes")
internal class TokenPrivileges : Structure() {
    @JvmField var privilegeCount: Int = 0
    @JvmField var luid: Luid = Luid()
    @JvmField var attributes: Int = 0
}

@Suppress("FunctionName")
private interface PowrProf : Library {
    fun SetSuspendState(hibernate: Byte, force: Byte, wakeupEventsDisabled: Byte): Byte

    companion object {
        val INSTANCE: PowrProf by lazy { Native.load("powrprof", PowrProf::class.java) }
    }
}

@Suppress("FunctionName")
private interface User32Power : Library {
    fun ExitWindowsEx(flags: Int, reason: Int): Boolean

    companion object {
        val INSTANCE: User32Power by lazy { Native.load("user32", User32Power::class.java) }
    }
}

@Suppress("FunctionName")
private interface Kernel32Power : Library {
    fun GetCurrentProcess(): Pointer
    fun CloseHandle(handle: Pointer?): Boolean

    companion object {
        val INSTANCE: Kernel32Power by lazy { Native.load("kernel32", Kernel32Power::class.java) }
    }
}

@Suppress("FunctionName")
private interface Advapi32Power : Library {
    fun OpenProcessToken(process: Pointer, desiredAccess: Int, token: PointerByReference): Boolean
    fun LookupPrivilegeValueW(systemName: CharArray?, name: CharArray, luid: Luid): Boolean
    fun AdjustTokenPrivileges(
        token: Pointer?,
        disableAll: Boolean,
        newState: TokenPrivileges,
        bufferLength: Int,
        previousState: Pointer?,
        returnLength: Pointer?,
    ): Boolean

    companion object {
        val INSTANCE: Advapi32Power by lazy { Native.load("advapi32", Advapi32Power::class.java) }
    }
}
