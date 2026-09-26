package com.totaliptv.pro.ui

/**
 * Long-press OK and Menu on a Live row offer Record.
 * The long-press flag arrives on key down (repeat). The matching key up
 * must be consumed or the row's click plays the channel instead.
 */
object LiveRowKeys {
    enum class Action { IGNORE, RECORD, CONSUME }

    fun decide(
        isOk: Boolean,
        isMenu: Boolean,
        keyDown: Boolean,
        repeatCount: Int,
        isLongPress: Boolean,
        longAlreadyFired: Boolean
    ): Action {
        if (isMenu) return if (keyDown) Action.RECORD else Action.CONSUME
        if (!isOk) return Action.IGNORE
        if (keyDown && (isLongPress || repeatCount > 0)) {
            return if (longAlreadyFired) Action.CONSUME else Action.RECORD
        }
        if (!keyDown && longAlreadyFired) return Action.CONSUME
        return Action.IGNORE
    }
}
