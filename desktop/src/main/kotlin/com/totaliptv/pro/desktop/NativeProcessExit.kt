package com.totaliptv.pro.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary

/**
 * Hard process exit that does not take the JVM shutdown lock.
 *
 * [kotlin.system.exitProcess] and [Runtime.halt] both synchronize on
 * `java.lang.Shutdown`. On Linux that lock is already held when a native
 * library's exit handler wedges, so the halt backstop parks forever.
 * libc `_exit` is `exit_group` and does not touch that lock. `SIGKILL`
 * is the fallback if `_exit` cannot be called.
 */
internal object NativeProcessExit {
    const val SIGKILL = 9

    internal interface Hooks {
        fun exitImmediate(status: Int)
        fun killSelf()
    }

    private interface LibC : Library {
        fun _exit(status: Int)
        fun getpid(): Int
        fun kill(pid: Int, sig: Int): Int
    }

    /** Test double. Production stays null and uses libc. */
    @Volatile
    var hooks: Hooks? = null

    @Volatile
    private var libc: LibC? = null

    fun preload(): Boolean {
        if (hooks != null) return true
        if (libc != null) return true
        libc = runCatching {
            val symbols = NativeLibrary.getInstance("c")
            symbols.getFunction("_exit")
            symbols.getFunction("getpid")
            symbols.getFunction("kill")
            Native.load("c", LibC::class.java)
        }.getOrNull()
        return libc != null
    }

    fun libcReady(): Boolean = libc != null || hooks != null

    /**
     * Does not return when libc `_exit` runs. The kill call is only reached
     * when `_exit` fails to terminate the process.
     */
    fun exitNow(status: Int = 0) {
        val test = hooks
        if (test != null) {
            runCatching { test.exitImmediate(status) }
            runCatching { test.killSelf() }
            return
        }
        val lib = libc ?: runCatching { Native.load("c", LibC::class.java) }.getOrNull()?.also { libc = it }
        if (lib != null) {
            runCatching { lib._exit(status) }
            runCatching { lib.kill(lib.getpid(), SIGKILL) }
        }
        runCatching { Runtime.getRuntime().halt(status) }
    }
}
