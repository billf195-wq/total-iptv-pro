package com.totaliptv.pro.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubReleaseTest {
    @Test
    fun newestAndroidReleaseFallsThroughToPreviousDesktopRelease() {
        val pages = listOf(
            """
            [
              ${release("1.2.99", 99, apk("1.2.99"))},
              ${release("1.2.22", 34, linux("1.2.22") + "," + windows("1.2.22"), notes = "versionCode: 34")}
            ]
            """.trimIndent()
        )
        val linux = GithubRelease.select(pages, windows = false, localName = "1.2.21", localCode = 33)
        assertEquals("1.2.22", linux?.versionName)
        assertEquals(34, linux?.versionCode)
        assertEquals(
            "https://github.com/billf195-wq/total-iptv-pro/releases/download/v1.2.22/TotalIptvPro-Desktop-1.2.22-linux.tar.gz",
            linux?.fileLinux
        )
        val win = GithubRelease.select(pages, windows = true, localName = "1.2.21", localCode = 33)
        assertEquals("1.2.22", win?.versionName)
        assertTrue(win?.fileWindows.orEmpty().endsWith("TotalIptvPro-Desktop-1.2.22-windows.zip"))
        assertTrue(linux!!.versionCode > 33 || UpdateSources.isNewerName(linux.versionName, "1.2.21"))
        assertFalse(linux.versionCode > 34 || UpdateSources.isNewerName(linux.versionName, "1.2.22"))
    }

    @Test
    fun osAssetMustMatchAndDraftsAndPrereleasesAreSkipped() {
        val page = """
            [
              ${release("1.3.0-rc1", 40, linux("1.3.0-rc1"), prerelease = true)},
              ${release("9.9.9", 99, windows("9.9.9"), draft = true)},
              ${release("1.2.22", 34, linux("1.2.22"))},
              ${release("1.2.21", 33, windows("1.2.21"))}
            ]
        """.trimIndent()
        val linux = GithubRelease.select(listOf(page), windows = false, localName = "1.2.20", localCode = 30)
        assertEquals("1.2.22", linux?.versionName)
        assertNull(linux?.fileWindows)
        val win = GithubRelease.select(listOf(page), windows = true, localName = "1.2.20", localCode = 30)
        assertEquals("1.2.21", win?.versionName)
        assertTrue(win?.fileWindows.orEmpty().contains("windows"))
        assertNull(GithubRelease.select(listOf("[${release("2.0.0", 50, apk("2.0.0"))}]"), windows = false, "1.2.22", 34))
        assertNull(GithubRelease.select(listOf("[${release("2.0.0", 50, apk("2.0.0"))}]"), windows = true, "1.2.22", 34))
    }

    @Test
    fun laterPageIsUsedWhenTheFirstPageHasNoDesktopAsset() {
        val androidOnly = (1..UpdateSources.GITHUB_RELEASES_PER_PAGE).joinToString(",") { n ->
            release("9.0.$n", 100 + n, apk("9.0.$n"))
        }
        val page1 = "[$androidOnly]"
        val page2 = "[${release("1.2.20", 32, linux("1.2.20") + "," + windows("1.2.20"), notes = "versionCode=32")}]"
        assertFalse(GithubRelease.pageEnded(page1))
        assertTrue(GithubRelease.pageEnded(page2))
        val picked = GithubRelease.select(listOf(page1, page2), windows = false, localName = "1.2.15", localCode = 20)
        assertEquals("1.2.20", picked?.versionName)
        assertEquals(32, picked?.versionCode)
        assertTrue(picked?.fileLinux.orEmpty().endsWith(".tar.gz"))
    }

    @Test
    fun listUrlPaginatesTheReleasesApiAndLanShelvesStillRewrite() {
        assertEquals(
            "https://api.github.com/repos/billf195-wq/total-iptv-pro/releases?per_page=30&page=2",
            UpdateSources.releasesListUrl(2)
        )
        assertFalse(UpdateSources.releasesListUrl(1).contains("/releases/latest"))
        assertEquals(
            UpdateSources.GITHUB_RELEASES_PAGE,
            UpdateSources.shelfForCheck("http://192.168.4.39:8767/")
        )
    }

    @Test
    fun tgzCountsAsLinuxAndMissingVersionCodeFollowsTheTag() {
        val body = "[${release("1.2.18", null, """{"name":"app.tgz","browser_download_url":"https://example.test/app.tgz"}""")}]"
        val remote = GithubRelease.select(listOf(body), windows = false, localName = "1.2.17", localCode = 30)
        assertEquals("1.2.18", remote?.versionName)
        assertEquals(31, remote?.versionCode)
        assertEquals("https://example.test/app.tgz", remote?.fileLinux)
        val older = GithubRelease.select(listOf(body), windows = false, localName = "1.2.18", localCode = 40)
        assertEquals(40, older?.versionCode)
    }

    private fun release(
        tag: String,
        code: Int?,
        assets: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = code?.let { "versionCode: $it" }.orEmpty()
    ): String = """
        {
          "tag_name": "v$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": ${jsonString(notes)},
          "assets": [$assets]
        }
    """.trimIndent()

    private fun linux(tag: String) =
        """{"name":"TotalIptvPro-Desktop-$tag-linux.tar.gz","browser_download_url":"https://github.com/billf195-wq/total-iptv-pro/releases/download/v$tag/TotalIptvPro-Desktop-$tag-linux.tar.gz"}"""

    private fun windows(tag: String) =
        """{"name":"TotalIptvPro-Desktop-$tag-windows.zip","browser_download_url":"https://github.com/billf195-wq/total-iptv-pro/releases/download/v$tag/TotalIptvPro-Desktop-$tag-windows.zip"}"""

    private fun apk(tag: String) =
        """{"name":"TotalIptvPro-$tag.apk","browser_download_url":"https://github.com/billf195-wq/total-iptv-pro/releases/download/v$tag/TotalIptvPro-$tag.apk"}"""

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(ch)
                }
            }
            append('"')
        }
}
