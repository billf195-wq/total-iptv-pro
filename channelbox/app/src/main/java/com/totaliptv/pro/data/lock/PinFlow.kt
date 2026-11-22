package com.totaliptv.pro.data.lock

/** Which PIN dialog is open. */
enum class PinMode { SET, UNLOCK, CHANGE, TURN_OFF, RESET, PICK_CATEGORIES, VERIFY }

/**
 * Pure step logic for the PIN dialogs, shared by TV (Classic + Desktop) and phone.
 * Each step collects 4 digits; [submit] runs when the 4th digit is entered or OK is pressed.
 */
class PinFlow(val mode: PinMode, private val gate: ParentalGate = ParentalGate) {
    enum class Step { CURRENT, NEW, CONFIRM, DONE }

    var step: Step = when (mode) {
        PinMode.SET, PinMode.RESET -> Step.NEW
        else -> Step.CURRENT
    }
        private set
    var digits: String = ""
        private set
    var error: String? = null
        private set
    private var current = ""
    private var first = ""

    val title: String
        get() = when (mode) {
            PinMode.SET -> "Set a PIN"
            PinMode.UNLOCK -> "Enter PIN to unlock"
            PinMode.CHANGE -> "Change PIN"
            PinMode.TURN_OFF -> "Turn off PIN lock"
            PinMode.RESET -> "Forgot PIN — set a new PIN"
            PinMode.PICK_CATEGORIES -> "Enter PIN to choose categories"
            PinMode.VERIFY -> "Enter PIN to show adult in search"
        }

    val prompt: String
        get() = when (step) {
            Step.CURRENT -> "Enter your current PIN"
            Step.NEW -> "Enter a new 4-digit PIN"
            Step.CONFIRM -> "Enter the new PIN again"
            Step.DONE -> ""
        }

    fun digit(d: Char) {
        if (step == Step.DONE || d !in '0'..'9' || digits.length >= PinHash.PIN_LENGTH) return
        error = null
        digits += d
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits = digits.dropLast(1)
    }

    /** Returns true once the whole flow succeeded. */
    fun submit(): Boolean {
        if (step == Step.DONE) return true
        if (digits.length != PinHash.PIN_LENGTH) {
            error = "Enter 4 digits"
            return false
        }
        val entered = digits
        digits = ""
        when (step) {
            Step.CURRENT -> {
                if (!gate.verify(entered)) {
                    error = "Wrong PIN"
                    return false
                }
                current = entered
                when (mode) {
                    PinMode.UNLOCK, PinMode.PICK_CATEGORIES -> {
                        gate.unlock(entered)
                        step = Step.DONE
                    }
                    PinMode.VERIFY -> {
                        // Verify only: do not unlock the parental session.
                        step = Step.DONE
                    }
                    PinMode.TURN_OFF -> {
                        gate.turnOff(entered)
                        step = Step.DONE
                    }
                    else -> step = Step.NEW
                }
            }
            Step.NEW -> {
                first = entered
                step = Step.CONFIRM
            }
            Step.CONFIRM -> {
                if (entered != first) {
                    error = "PINs did not match. Try again."
                    first = ""
                    step = Step.NEW
                    return false
                }
                val ok = when (mode) {
                    PinMode.SET -> gate.setPin(entered)
                    PinMode.CHANGE -> gate.changePin(current, entered)
                    PinMode.RESET -> gate.resetForgotten(entered)
                    else -> false
                }
                if (!ok) {
                    error = "Could not save the PIN"
                    step = Step.NEW
                    return false
                }
                step = Step.DONE
            }
            Step.DONE -> Unit
        }
        return step == Step.DONE
    }

    /** Short line for the Settings section. */
    companion object {
        fun statusLine(gate: ParentalGate = ParentalGate): String =
            statusLine(gate.isEnabled, gate.isActive, gate.lockedCategoryCount)

        /** 1.4.108: says how many categories are locked, and warns when none match. */
        fun statusLine(enabled: Boolean, active: Boolean, lockedCategories: Int): String {
            if (!enabled) return "Off. Set a PIN to hide adult categories and any categories you choose."
            val none = lockedCategories == 0
            val count = when {
                lockedCategories < 0 -> null
                lockedCategories == 1 -> "1 category"
                else -> "$lockedCategories categories"
            }
            return if (active) {
                when {
                    none -> "On — locked, but no categories are locked yet. Use Choose locked categories to pick what to hide."
                    count != null -> "On — locked. $count hidden (adult and chosen categories)."
                    else -> "On — locked. Adult and chosen categories are hidden."
                }
            } else {
                val tail = if (none) " No categories are locked yet; use Choose locked categories." else ""
                "On — unlocked. Locked categories show until you choose Lock now or leave the app for 5 minutes.$tail"
            }
        }
    }
}

/** Immutable snapshot of a [PinFlow] for Compose (title, prompt, dots, error). */
data class PinPadView(val title: String, val prompt: String, val dots: String, val error: String?) {
    companion object {
        fun of(flow: PinFlow): PinPadView = PinPadView(
            title = flow.title,
            prompt = flow.prompt,
            dots = (0 until PinHash.PIN_LENGTH).joinToString("  ") { if (it < flow.digits.length) "\u25CF" else "\u25CB" },
            error = flow.error
        )
    }
}
