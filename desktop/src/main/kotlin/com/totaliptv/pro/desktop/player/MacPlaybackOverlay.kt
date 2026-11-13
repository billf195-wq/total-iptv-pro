package com.totaliptv.pro.desktop.player

import com.totaliptv.pro.desktop.util.AppPaths
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * macOS-only always-on-top Stop control shown while a title is playing.
 * Space in VLC only pauses; Esc quits VLC (see [StreamPlayer.VLC_MAC_STOP_KEYS]).
 * This button calls [StreamPlayer.stop] so the app comes back to the title page.
 */
object MacPlaybackOverlay {
    internal const val TITLE = "Stop"
    internal const val HINT = "Esc also stops"

    private val lock = Any()

    @Volatile
    private var window: JWindow? = null

    @Volatile
    private var raiseTimer: Timer? = null

    fun arm() {
        if (!AppPaths.isMac) return
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater { showOnEdt() }
    }

    fun disarm() {
        val hide = Runnable { hideOnEdt() }
        if (SwingUtilities.isEventDispatchThread()) hide.run() else SwingUtilities.invokeLater(hide)
    }

    internal fun overlaySize(): Dimension = Dimension(168, 64)

    internal fun overlayOrigin(screenWidth: Int, insetTop: Int, size: Dimension = overlaySize()): Pair<Int, Int> {
        val margin = 24
        val y = (insetTop + 12).coerceAtLeast(28)
        val x = (screenWidth - size.width - margin).coerceAtLeast(margin)
        return x to y
    }

    private fun showOnEdt() {
        synchronized(lock) {
            hideOnEdtLocked()
            val size = overlaySize()
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val device = ge.defaultScreenDevice
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
            val bounds = device.defaultConfiguration.bounds
            val (x, y) = overlayOrigin(bounds.x + bounds.width, bounds.y + insets.top, size)
            val win = JWindow().apply {
                type = java.awt.Window.Type.UTILITY
                isAlwaysOnTop = true
                focusableWindowState = false
                this.size = size
                setLocation(x, y)
            }
            val stop = JButton(TITLE).apply {
                font = font.deriveFont(Font.BOLD, 16f)
                foreground = Color.WHITE
                background = Color(180, 40, 40)
                isOpaque = true
                isBorderPainted = false
                isFocusable = false
                toolTipText = HINT
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        StreamPlayer.stop()
                    }
                })
            }
            val hint = JLabel(HINT, SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = Color(230, 230, 230)
                isOpaque = false
            }
            val panel = JPanel(BorderLayout(0, 2)).apply {
                background = Color(20, 20, 20, 230)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
                add(stop, BorderLayout.CENTER)
                add(hint, BorderLayout.SOUTH)
            }
            win.contentPane = panel
            win.isVisible = true
            window = win
            raiseTimer = Timer(800) {
                val w = window
                if (w != null && w.isDisplayable) {
                    w.isAlwaysOnTop = true
                    w.toFront()
                }
            }.apply { start() }
        }
    }

    private fun hideOnEdt() {
        synchronized(lock) { hideOnEdtLocked() }
    }

    private fun hideOnEdtLocked() {
        raiseTimer?.stop()
        raiseTimer = null
        window?.isVisible = false
        window?.dispose()
        window = null
    }
}
