package com.totaliptv.pro.ui.settings

import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.PinMode

/** Buttons in the Settings "PIN lock" section, shared by TV Classic, TV Desktop and phone. */
enum class PinLockEntry(val label: String, val subtitle: String) {
    SET("Set PIN", "Choose a 4-digit PIN. Adult categories are locked automatically."),
    UNLOCK("Unlock with PIN", "Show locked categories until Lock now, or 5 minutes away from the app"),
    LOCK_NOW("Lock now", "Hide locked categories again"),
    CATEGORIES("Choose locked categories", "Tick Live, Movies or Series categories to lock"),
    CHANGE("Change PIN", "Needs the current PIN"),
    TURN_OFF("Turn off PIN lock", "Needs the current PIN"),
    FORGOT("Forgot PIN", "Set a new PIN; your locked list is cleared");

    companion object {
        const val SECTION = "PIN lock"

        fun visibleEntries(gate: ParentalGate = ParentalGate): List<PinLockEntry> = when {
            !gate.isEnabled -> listOf(SET)
            gate.isActive -> listOf(UNLOCK, CATEGORIES, CHANGE, TURN_OFF, FORGOT)
            else -> listOf(LOCK_NOW, CATEGORIES, CHANGE, TURN_OFF, FORGOT)
        }

        /** PIN dialog an entry opens; null = no PIN needed (Lock now, Forgot confirm, picker while unlocked). */
        fun pinModeFor(entry: PinLockEntry, gate: ParentalGate = ParentalGate): PinMode? = when (entry) {
            SET -> PinMode.SET
            UNLOCK -> PinMode.UNLOCK
            LOCK_NOW -> null
            CATEGORIES -> if (gate.isActive) PinMode.PICK_CATEGORIES else null
            CHANGE -> PinMode.CHANGE
            TURN_OFF -> PinMode.TURN_OFF
            FORGOT -> null
        }

        fun doneMessage(mode: PinMode): String? = when (mode) {
            PinMode.SET -> "PIN lock on. Adult categories are hidden."
            PinMode.UNLOCK -> "Unlocked. Locks again after 5 minutes away from the app, or choose Lock now."
            PinMode.CHANGE -> "PIN changed"
            PinMode.TURN_OFF -> "PIN lock off"
            PinMode.RESET -> "New PIN set. Your locked list was cleared."
            PinMode.PICK_CATEGORIES -> null
            PinMode.VERIFY -> null
        }

        const val FORGOT_TITLE = "Forgot your PIN?"
        const val FORGOT_BODY =
            "There is no way to recover a PIN. You can set a new one: your ticked categories are " +
                "cleared, adult categories stay locked, and everything stays hidden until you enter the new PIN."
        const val FORGOT_CONFIRM = "Set a new PIN"
        const val PICKER_TITLE = "Locked categories"
        const val PICKER_HINT = "OK locks or shows a category. Adult categories are locked automatically."
        const val PICKER_HINT_TOUCH = "Tap a category to lock or show it. Adult categories are locked automatically."
    }
}

/** Which PIN lock dialog Settings is showing (TV Classic, Desktop and phone share these). */
sealed class PinLockDialog {
    data class Pad(val mode: PinMode) : PinLockDialog()
    data object Forgot : PinLockDialog()
    data object Categories : PinLockDialog()
}

/** Runs one Settings entry: opens the right dialog, or locks right away. */
fun pinLockEntryClicked(entry: PinLockEntry, open: (PinLockDialog?) -> Unit, toast: (String) -> Unit) {
    when (entry) {
        PinLockEntry.LOCK_NOW -> {
            ParentalGate.lockNow()
            toast("Locked")
            open(null)
        }
        PinLockEntry.FORGOT -> open(PinLockDialog.Forgot)
        PinLockEntry.CATEGORIES -> {
            val mode = PinLockEntry.pinModeFor(entry)
            open(if (mode != null) PinLockDialog.Pad(mode) else PinLockDialog.Categories)
        }
        else -> PinLockEntry.pinModeFor(entry)?.let { open(PinLockDialog.Pad(it)) }
    }
}

/**
 * 1.4.107: where D-pad focus goes when a PIN lock dialog closes (TV Classic and Desktop).
 * Back to the slot that was used, or the last slot still shown (Turn off leaves only
 * "Set PIN"). The Classic list scrolls the section header into view before focusing.
 */
object PinFocusReturn {
    val MAX_SLOTS: Int = PinLockEntry.entries.size
    const val ATTEMPTS = 25
    const val STEP_MS = 60L
    /** Items above slot 0 in the Classic list: the section title and the status line. */
    const val HEADER_ITEMS = 2

    fun slotAfterClose(usedSlot: Int, entryCount: Int): Int {
        if (entryCount <= 0) return 0
        return usedSlot.coerceIn(0, entryCount - 1)
    }

    fun scrollIndex(slot0Index: Int): Int = (slot0Index - HEADER_ITEMS).coerceAtLeast(0)
}
