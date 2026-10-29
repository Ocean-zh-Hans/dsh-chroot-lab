package dev.dsh.agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhereTest {

    private val view = RefStore().bind(Fixture.settings())

    @Test
    fun findsByText() {
        val ids = Query.select(view, Where.text("WLAN"))
        assertEquals(1, ids.size)
        assertEquals("WLAN", view.snapshot.node(ids[0]).text)
    }

    @Test
    fun filtersClickable() {
        val ids = Query.select(view, Where(clickable = true))
        assertTrue(ids.size >= 2)
        assertTrue(ids.all { view.snapshot.node(it).clickable })
    }

    @Test
    fun parsesJson() {
        val obj = Json.parseToJsonElement("""{"text":"蓝牙","clickable":false}""") as JsonObject
        val where = Where.parse(obj)
        val ids = Query.select(view, where)
        assertEquals(1, ids.size)
        assertEquals("蓝牙", view.snapshot.node(ids[0]).text)
    }

    @Test
    fun relativeAnchor() {
        val anchor = Query.select(view, Where.text("WLAN")).first()
        val anchorRef = view.refOf(anchor)!!
        val where = Where(clickable = true, below = Anchor(ref = anchorRef))
        val ids = Query.select(view, where)
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { view.snapshot.node(it).bounds.t >= view.snapshot.node(anchor).bounds.b })
    }

    @Test
    fun regexSupport() {
        val obj = Json.parseToJsonElement("""{"text":{"value":"已(开启|关闭)","regex":true}}""") as JsonObject
        val ids = Query.select(view, Where.parse(obj))
        assertEquals(2, ids.size)
    }
}
