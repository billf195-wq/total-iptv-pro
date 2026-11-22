package com.totaliptv.pro.data.xtream

import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real Xtream get_series rows: numbers as strings and vice versa, nulls, arrays, unicode. */
class XtreamSeriesShapeTest {
    // Same settings XtreamApi uses for provider JSON.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val rows = """
        [
          {"num":1,"name":"Show A","series_id":5000,"cover":"c","plot":null,"cast":null,"director":null,
           "genre":"Drama","releaseDate":"2019-01-01","last_modified":"1700000000","rating":7.4,"rating_5based":3.7,
           "backdrop_path":["b1"],"youtube_trailer":null,"episode_run_time":"45","category_id":"3","category_ids":[3],"tmdb":"123"},
          {"num":"2","name":"Show B \u00e9 \ud83d\ude00 {x} [y]","series_id":"5001","cover":null,"plot":"p \"q\"",
           "rating":"6.1","rating_5based":"3.1","backdrop_path":[],"category_id":7,"category_ids":[7],"tmdb":null},
          {"num":3,"name":null,"series_id":5002,"rating":null,"rating_5based":0,"category_id":null,"last_modified":1700000001}
        ]
    """.trimIndent()

    @Test
    fun everyRealShapeRowDecodes() {
        val got = ArrayList<XtreamApi.SeriesStream>()
        val res = JsonArrayStream.consume(StringReader(rows), json, XtreamApi.SeriesStream.serializer(), 2) { got.addAll(it) }
        assertEquals(0, res.rejected)
        assertEquals(3, res.count)
        assertEquals(listOf(5000, 5001, 5002), got.map { it.seriesId })
        assertEquals("7", got[1].categoryId)
        assertEquals("", got[2].name)
    }

    @Test
    fun bomAndNoticePrefixedSeriesStillDecode() {
        val raw = "\uFEFF<br />\n<b>Warning</b>: something<br />\n" + rows
        val got = ArrayList<XtreamApi.SeriesStream>()
        val res = JsonArrayStream.consume(StringReader(raw), json, XtreamApi.SeriesStream.serializer(), 500) { got.addAll(it) }
        assertEquals(3, res.count)
    }
}
