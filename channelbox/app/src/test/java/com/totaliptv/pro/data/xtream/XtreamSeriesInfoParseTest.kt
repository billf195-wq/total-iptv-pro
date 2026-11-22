package com.totaliptv.pro.data.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XtreamSeriesInfoParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun episodePlotReadsInfoPlotAndPlainPlot() {
        val withInfo = json.parseToJsonElement(
            """{"id":"11","title":"Pilot","episode_num":"1","info":{"plot":"Walter begins.","duration":"58"}}"""
        ).jsonObject
        assertEquals("Walter begins.", XtreamApi.plotFromEpisodeObject(withInfo))

        val plain = json.parseToJsonElement(
            """{"id":"12","title":"Cat's in the Bag","plot":"The cleanup starts."}"""
        ).jsonObject
        assertEquals("The cleanup starts.", XtreamApi.plotFromEpisodeObject(plain))

        val desc = json.parseToJsonElement(
            """{"id":"13","description":"A short summary."}"""
        ).jsonObject
        assertEquals("A short summary.", XtreamApi.plotFromEpisodeObject(desc))

        val empty = json.parseToJsonElement("""{"id":"14","title":"No plot"}""").jsonObject
        assertNull(XtreamApi.plotFromEpisodeObject(empty))
    }
}
