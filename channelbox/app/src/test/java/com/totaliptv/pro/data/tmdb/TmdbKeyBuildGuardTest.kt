package com.totaliptv.pro.data.tmdb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.4.107 / 1.4.108 shipped with a blank built-in TMDB key, so only provider-named service
 * groups (Netflix) showed. The build must refuse to package an APK without the key.
 */
class TmdbKeyBuildGuardTest {
    @Test
    fun packagingWithoutTheTmdbKeyFailsTheBuild() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("\"\\\"\$tipTmdbDefaultKey\\\"\""))
        assertTrue(gradle.contains("tipTmdbKeyFile"))
        assertTrue(gradle.contains("TIP_TMDB_KEY_FILE"))
        assertTrue(gradle.contains("tipAllowNoTmdbKey"))
        assertTrue(gradle.contains("gradle.taskGraph.whenReady"))
        assertTrue(gradle.contains("throw GradleException("))
        assertTrue(gradle.contains("(assemble|bundle|package)(Tv|Phone)"))
    }
}
