package com.totaliptv.pro.data.lock

import android.content.Context
import java.io.File

/** On-device store: salted PIN hash + category choices in SharedPreferences, hidden ids in a file. */
class ParentalLockStore(context: Context) : ParentalGate.Store {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val idsFile = File(context.applicationContext.filesDir, IDS_FILE)

    override fun readConfig(): LockConfig = LockConfig(
        pinHash = prefs.getString(KEY_HASH, null)?.takeIf { it.isNotBlank() },
        extraLocked = prefs.getStringSet(KEY_EXTRA, null)?.toSet().orEmpty(),
        autoExempt = prefs.getStringSet(KEY_EXEMPT, null)?.toSet().orEmpty()
    )

    override fun writeConfig(config: LockConfig) {
        prefs.edit()
            .putString(KEY_HASH, config.pinHash)
            .putStringSet(KEY_EXTRA, config.extraLocked)
            .putStringSet(KEY_EXEMPT, config.autoExempt)
            .apply()
    }

    override fun readRemembered(): Set<String> =
        runCatching { if (idsFile.isFile) idsFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet() }
            .getOrDefault(emptySet())

    override fun writeRemembered(ids: Set<String>) {
        runCatching {
            val tmp = File(idsFile.parentFile, "$IDS_FILE.tmp")
            tmp.writeText(ids.joinToString("\n"))
            if (!tmp.renameTo(idsFile)) {
                tmp.copyTo(idsFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val PREFS = "parental_lock"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_EXTRA = "extra_locked"
        private const val KEY_EXEMPT = "auto_exempt"
        private const val IDS_FILE = "parental-hidden.txt"
    }
}
