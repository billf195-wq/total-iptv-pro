package com.totaliptv.pro.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.totaliptv.pro.desktop.util.AppPaths

/**
 * Registers the Escape key as a system hotkey for the life of a playback
 * session so Esc stops VLC even when VLC has focus. Does not require the
 * Accessibility permission (RegisterEventHotKey). Armed only while playing.
 */
object MacStopHotkey {
    /** Hardware key code for Escape (kVK_Escape). */
    internal const val KEY_ESCAPE: Int = 0x35

    /** 'keyb' */
    private const val EVENT_CLASS_KEYBOARD: Int = 0x6B657962

    /** kEventHotKeyPressed */
    private const val EVENT_HOT_KEY_PRESSED: Int = 5

    /** 'TIPR' */
    internal const val HOTKEY_SIGNATURE: Int = 0x54495052

    internal const val HOTKEY_ID: Int = 1

    private val lock = Any()

    @Volatile
    private var carbon: Carbon? = null

    @Volatile
    private var hotKeyRef: Pointer? = null

    @Volatile
    private var handlerRef: Pointer? = null

    /** Strong refs so JNA does not collect the callbacks. */
    @Volatile
    private var handler: Carbon.EventHandlerProc? = null

    fun arm() {
        if (!AppPaths.isMac) return
        synchronized(lock) {
            disarmLocked()
            runCatching { installLocked() }
                .onFailure { err ->
                    PlaybackDebugLog.note("mac-stop-hotkey arm failed: ${err.javaClass.simpleName}")
                    disarmLocked()
                }
        }
    }

    fun disarm() {
        synchronized(lock) { disarmLocked() }
    }

    private fun installLocked() {
        val lib = Native.load("Carbon", Carbon::class.java)
        carbon = lib
        val handlerProc = Carbon.EventHandlerProc { _, _, _ ->
            if (StreamPlayer.isPlaying()) {
                StreamPlayer.stop()
            }
            0
        }
        handler = handlerProc
        val handlerOut = PointerByReference()
        val type = EventTypeSpec().apply {
            eventClass = EVENT_CLASS_KEYBOARD
            eventKind = EVENT_HOT_KEY_PRESSED
        }
        val target = lib.GetApplicationEventTarget()
            ?: throw IllegalStateException("no event target")
        val herr = lib.InstallEventHandler(target, handlerProc, 1, type, Pointer.NULL, handlerOut)
        if (herr != 0) throw IllegalStateException("InstallEventHandler $herr")
        handlerRef = handlerOut.value
        val id = EventHotKeyID().apply {
            signature = HOTKEY_SIGNATURE
            id = HOTKEY_ID
        }
        val keyOut = PointerByReference()
        val kerr = lib.RegisterEventHotKey(KEY_ESCAPE, 0, id, target, 0, keyOut)
        if (kerr != 0) throw IllegalStateException("RegisterEventHotKey $kerr")
        hotKeyRef = keyOut.value
        PlaybackDebugLog.note("mac-stop-hotkey armed Esc")
    }

    private fun disarmLocked() {
        val lib = carbon
        val key = hotKeyRef
        val hnd = handlerRef
        hotKeyRef = null
        handlerRef = null
        handler = null
        if (lib != null && key != null) {
            runCatching { lib.UnregisterEventHotKey(key) }
        }
        if (lib != null && hnd != null) {
            runCatching { lib.RemoveEventHandler(hnd) }
        }
        carbon = null
    }

    @Structure.FieldOrder("eventClass", "eventKind")
    class EventTypeSpec : Structure(), Structure.ByReference {
        @JvmField var eventClass: Int = 0
        @JvmField var eventKind: Int = 0
    }

    @Structure.FieldOrder("signature", "id")
    class EventHotKeyID : Structure(), Structure.ByValue {
        @JvmField var signature: Int = 0
        @JvmField var id: Int = 0
    }

    interface Carbon : Library {
        fun GetApplicationEventTarget(): Pointer?
        fun InstallEventHandler(
            target: Pointer,
            handler: EventHandlerProc,
            numTypes: Int,
            typeList: EventTypeSpec,
            userData: Pointer?,
            outRef: PointerByReference
        ): Int
        fun RemoveEventHandler(ref: Pointer): Int
        fun RegisterEventHotKey(
            keyCode: Int,
            modifiers: Int,
            id: EventHotKeyID,
            target: Pointer,
            options: Int,
            outRef: PointerByReference
        ): Int
        fun UnregisterEventHotKey(ref: Pointer): Int

        fun interface EventHandlerProc : Callback {
            fun invoke(nextHandler: Pointer?, event: Pointer?, userData: Pointer?): Int
        }
    }
}
