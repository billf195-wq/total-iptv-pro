package com.totaliptv.pro.desktop.dvr

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

object DvrFolderPicker {
    fun pick(current: String?): String? {
        var result: String? = null
        val task = Runnable {
            runCatching {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Recordings folder on this device"
                isAcceptAllFileFilterUsed = false
                if (!current.isNullOrBlank()) {
                    val existing = File(current)
                    if (existing.isDirectory) currentDirectory = existing
                    else if (existing.parentFile?.isDirectory == true) currentDirectory = existing.parentFile
                }
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            runCatching { SwingUtilities.invokeAndWait(task) }
        }
        return result
    }
}
