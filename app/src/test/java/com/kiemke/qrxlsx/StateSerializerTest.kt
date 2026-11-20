/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StateSerializerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tab(n: Int, pos: Int = 5) = SavedTab(
        uri = "content://docs/file$n",
        name = "kiem ke $n.xlsx",
        size = 1000L + n,
        lastModified = 1_700_000_000_000L + n,
        sheetIndex = n,
        sheetName = "Sheet$n",
        hasHeader = n % 2 == 0,
        qrCol = 2,
        nameCol = 0,
        qtyCol = -1,
        imeiCol = 3,
        position = pos,
        done = n == 1,
        history = listOf(HistoryMark(1, 10L), HistoryMark(7, 20L))
    )

    @Test
    fun roundTrip_keepsEverything() {
        val state = SavedState(123456L, 1, 75, listOf(tab(0), tab(1, 36), tab(2)))
        val back = StateSerializer.fromJson(StateSerializer.toJson(state))
        assertEquals(state, back)
    }

    @Test
    fun json_doesNotContainDataContent() {
        // Chỉ có metadata; không có trường nào chứa mã/tên/IMEI của dữ liệu.
        val json = StateSerializer.toJson(SavedState(1L, 0, 50, listOf(tab(0))))
        for (key in listOf("\"code\"", "\"imei\":", "\"qty\"", "\"items\"")) assertFalse(json.contains(key))
    }

    @Test
    fun garbage_returnsNull() {
        assertNull(StateSerializer.fromJson(null))
        assertNull(StateSerializer.fromJson(""))
        assertNull(StateSerializer.fromJson("   "))
        assertNull(StateSerializer.fromJson("{not json"))
        assertNull(StateSerializer.fromJson("[1,2,3]"))
        assertNull(StateSerializer.fromJson("\u0000\u0001binary"))
    }

    @Test
    fun truncatedFile_returnsNullWithoutCrash() {
        val json = StateSerializer.toJson(SavedState(1L, 0, 50, listOf(tab(0), tab(1))))
        assertNull(StateSerializer.fromJson(json.substring(0, json.length / 2)))
    }

    @Test
    fun missingFields_useDefaults() {
        val s = StateSerializer.fromJson("""{"tabs":[{"uri":"content://a","qrCol":1}]}""")
        assertNotNull(s)
        s!!
        assertEquals(50, s.brightness)
        assertEquals(0, s.selectedTab)
        val t = s.tabs.single()
        assertEquals("content://a", t.uri)
        assertEquals(0, t.position)
        assertTrue(t.hasHeader)
        assertEquals(-1, t.nameCol)
        assertEquals(0, t.sheetIndex)
        assertFalse(t.done)
        assertTrue(t.history.isEmpty())
    }

    @Test
    fun tabsWithoutUriOrQrCol_areSkipped_othersKept() {
        val s = StateSerializer.fromJson(
            """{"selectedTab":5,"tabs":[
                {"qrCol":1},
                {"uri":"content://a"},
                {"uri":null,"qrCol":1},
                "not an object",
                {"uri":"content://ok","qrCol":0}
            ]}"""
        )!!
        assertEquals(listOf("content://ok"), s.tabs.map { it.uri })
        assertEquals(0, s.selectedTab) // kẹp về tab hợp lệ
    }

    @Test
    fun wrongTypes_fallBackToDefaults() {
        val s = StateSerializer.fromJson(
            """{"brightness":"bright","savedAt":"x","tabs":[{"uri":"content://a","qrCol":1,"position":"abc","hasHeader":"yes","history":"oops"}]}"""
        )!!
        assertEquals(50, s.brightness)
        assertEquals(0L, s.savedAt)
        val t = s.tabs.single()
        assertEquals(0, t.position)
        assertTrue(t.hasHeader)
        assertTrue(t.history.isEmpty())
    }

    @Test
    fun invalidBrightness_normalizedTo50() {
        assertEquals(50, StateSerializer.fromJson("""{"brightness":60,"tabs":[]}""")!!.brightness)
        assertEquals(25, StateSerializer.fromJson("""{"brightness":25,"tabs":[]}""")!!.brightness)
        assertEquals(100, StateSerializer.fromJson("""{"brightness":100,"tabs":[]}""")!!.brightness)
    }

    @Test
    fun negativeValues_areClamped() {
        val t = StateSerializer.fromJson(
            """{"tabs":[{"uri":"content://a","qrCol":0,"position":-4,"sheetIndex":-2,"nameCol":-9}]}"""
        )!!.tabs.single()
        assertEquals(0, t.position)
        assertEquals(0, t.sheetIndex)
        assertEquals(-1, t.nameCol)
    }

    @Test
    fun moreThanMaxTabs_andHistory_areTrimmed() {
        val many = (0 until 6).map { tab(it) }
        val history = (0 until 50).map { HistoryMark(it, it.toLong()) }
        val state = SavedState(1L, 0, 50, many.map { it.copy(history = history) })
        val back = StateSerializer.fromJson(StateSerializer.toJson(state))!!
        assertEquals(MAX_TABS, back.tabs.size)
        assertEquals(MAX_HISTORY, back.tabs[0].history.size)
        assertEquals(49, back.tabs[0].history.last().index)
    }

    // ---------------------------------------------------------------- Store

    @Test
    fun store_writeThenRead() {
        val store = TimeMachineStore(File(tmp.root, "timemachine"))
        assertNull(store.read())
        val state = SavedState(9L, 0, 25, listOf(tab(0)))
        assertTrue(store.write(StateSerializer.toJson(state)))
        assertEquals(state, store.read())
        assertFalse(File(store.dir, "state.json.tmp").exists())
    }

    @Test
    fun store_overwriteKeepsLatest() {
        val store = TimeMachineStore(File(tmp.root, "timemachine"))
        store.write(StateSerializer.toJson(SavedState(1L, 0, 50, listOf(tab(0, 1)))))
        store.write(StateSerializer.toJson(SavedState(2L, 0, 50, listOf(tab(0, 99)))))
        assertEquals(99, store.read()!!.tabs.single().position)
    }

    @Test
    fun store_corruptFile_isIgnored() {
        val dir = File(tmp.root, "timemachine").also { it.mkdirs() }
        File(dir, "state.json").writeText("{ broken")
        val store = TimeMachineStore(dir)
        assertNull(store.read())
        // Vẫn ghi đè được sau khi file hỏng.
        assertTrue(store.write(StateSerializer.toJson(SavedState(1L, 0, 50, listOf(tab(0))))))
        assertNotNull(store.read())
    }

    @Test
    fun store_staleTmpFile_doesNotAffectRead() {
        val dir = File(tmp.root, "timemachine").also { it.mkdirs() }
        File(dir, "state.json.tmp").writeText("half written")
        assertNull(TimeMachineStore(dir).read())
    }

    @Test
    fun store_clearRemovesDirectory() {
        val store = TimeMachineStore(File(tmp.root, "timemachine"))
        store.write(StateSerializer.toJson(SavedState(1L, 0, 50, listOf(tab(0)))))
        store.clear()
        assertFalse(store.dir.exists())
        assertNull(store.read())
    }

    @Test
    fun brightnessLevels() {
        assertEquals(listOf(25, 50, 75, 100), BrightnessLevels.LEVELS)
        assertEquals(50, BrightnessLevels.normalize(0))
        assertEquals(75, BrightnessLevels.normalize(75))
    }
}
