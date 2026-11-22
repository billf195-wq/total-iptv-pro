package com.totaliptv.pro.data.xtream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonArrayStreamTest {
    @Serializable
    data class Rec(val id: Int, val name: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun collect(raw: String, batchSize: Int = 3): Pair<JsonArrayStream.Result, List<List<Rec>>> {
        val batches = mutableListOf<List<Rec>>()
        val result = JsonArrayStream.consume(
            StringReader(raw),
            json,
            Rec.serializer(),
            batchSize = batchSize,
        ) { batches.add(it) }
        return result to batches
    }

    @Test
    fun emptyAndBlankAreEmpty() {
        assertEquals(JsonArrayStream.Kind.Empty, collect("").first.kind)
        assertEquals(0, collect("   ").first.count)
        assertEquals(0, collect("[]").first.count)
    }

    @Test
    fun arraysAreDeliveredInBoundedBatches() {
        val raw = """[{"id":1,"name":"a"},{"id":2,"name":"b"},{"id":3,"name":"c"},{"id":4,"name":"d"}]"""
        val (res, batches) = collect(raw, batchSize = 3)
        assertEquals(JsonArrayStream.Kind.Array, res.kind)
        assertEquals(4, res.count)
        assertEquals(listOf(3, 1), batches.map { it.size })
        assertEquals(listOf(1, 2, 3, 4), batches.flatten().map { it.id })
    }

    @Test
    fun nestedObjectsAndBracesInStringsParse() {
        val raw = """[{"id":1,"name":"a {b}"},{"id":2,"name":"x","inner":{"k":"v"}}]"""
        val (res, batches) = collect(raw, batchSize = 10)
        assertEquals(2, res.count)
        assertEquals("a {b}", batches.flatten()[0].name)
    }

    @Test
    fun truncatedTailKeepsCompleteObjects() {
        val raw = """[{"id":1,"name":"a"},{"id":2,"name":"b"},{"id":3,"name":"c"""
        val (res, batches) = collect(raw, batchSize = 10)
        assertEquals(2, res.count)
        assertEquals(listOf(1, 2), batches.flatten().map { it.id })
    }

    @Test
    fun objectPayloadIsNotSlurpedAsArray() {
        val raw = """{"user_info":{"auth":0},"server_info":{}}"""
        val (res, _) = collect(raw)
        assertEquals(JsonArrayStream.Kind.Object, res.kind)
        assertEquals(0, res.count)
        assertTrue(res.prefix!!.contains("\"auth\":0"))
        assertTrue(res.prefix!!.length <= raw.length)
    }

    @Test
    fun skipsCorruptEntryAndContinues() {
        val raw = """[{"id":1,"name":"a"},{"id":"bad"},{"id":3,"name":"c"}]"""
        val (res, batches) = collect(raw, batchSize = 10)
        assertEquals(2, res.count)
        assertEquals(listOf(1, 3), batches.flatten().map { it.id })
    }
}
