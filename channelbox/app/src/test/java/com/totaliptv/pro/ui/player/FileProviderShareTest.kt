package com.totaliptv.pro.ui.player

import kotlin.test.Test
import kotlin.test.assertTrue

class FileProviderShareTest {
    @Test
    fun recordingsLeaveTheAppAsAGrantedContentUri() {
        val player = java.io.File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        assertTrue(player.contains("FileProvider.getUriForFile"))
        assertTrue(player.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(player.contains("grantUriPermission"))
        val paths = java.io.File("src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("recordings/"))
        assertTrue(paths.contains("files_root"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains(".fileprovider"))
    }
}
