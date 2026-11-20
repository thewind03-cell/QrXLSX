/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Phần này KHÔNG phụ thuộc Android (chỉ dùng java.io + org.json) để kiểm thử được bằng unit test JVM.

const val MAX_TABS = 3
const val MAX_HISTORY = 20
const val STATE_VERSION = 1

object BrightnessLevels {
    val LEVELS = listOf(25, 50, 75, 100)
    const val DEFAULT = 50

    /** Giá trị không hợp lệ -> mặc định 50. */
    fun normalize(percent: Int): Int = if (percent in LEVELS) percent else DEFAULT
}

data class HistoryMark(val index: Int, val time: Long)

/**
 * Trạng thái lưu của một tab. Chỉ chứa metadata (đường dẫn, cấu hình cột, vị trí),
 * TUYỆT ĐỐI không chứa nội dung mã/tên/số lượng/IMEI.
 */
data class SavedTab(
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val sheetIndex: Int,
    val sheetName: String,
    val hasHeader: Boolean,
    val qrCol: Int,
    val nameCol: Int,
    val qtyCol: Int,
    val imeiCol: Int,
    val position: Int,
    val done: Boolean = false,
    val history: List<HistoryMark> = emptyList()
)

data class SavedState(
    val savedAt: Long,
    val selectedTab: Int,
    val brightness: Int,
    val tabs: List<SavedTab>
)

object StateSerializer {

    fun toJson(state: SavedState): String {
        val tabs = JSONArray()
        for (t in state.tabs) {
            val hist = JSONArray()
            for (h in t.history) hist.put(JSONObject().put("i", h.index).put("t", h.time))
            tabs.put(
                JSONObject()
                    .put("uri", t.uri)
                    .put("name", t.name)
                    .put("size", t.size)
                    .put("lastModified", t.lastModified)
                    .put("sheetIndex", t.sheetIndex)
                    .put("sheetName", t.sheetName)
                    .put("hasHeader", t.hasHeader)
                    .put("qrCol", t.qrCol)
                    .put("nameCol", t.nameCol)
                    .put("qtyCol", t.qtyCol)
                    .put("imeiCol", t.imeiCol)
                    .put("position", t.position)
                    .put("done", t.done)
                    .put("history", hist)
            )
        }
        return JSONObject()
            .put("version", STATE_VERSION)
            .put("savedAt", state.savedAt)
            .put("selectedTab", state.selectedTab)
            .put("brightness", state.brightness)
            .put("tabs", tabs)
            .toString()
    }

    /** Đọc lại trạng thái. File hỏng/thiếu trường -> trả null hoặc bỏ phần hỏng, không bao giờ ném lỗi. */
    fun fromJson(text: String?): SavedState? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JSONObject(text)
            val tabsJson = root.opt("tabs") as? JSONArray
            val tabs = ArrayList<SavedTab>()
            if (tabsJson != null) {
                for (i in 0 until tabsJson.length()) {
                    if (tabs.size >= MAX_TABS) break
                    val o = tabsJson.opt(i) as? JSONObject ?: continue
                    parseTab(o)?.let { tabs.add(it) }
                }
            }
            val selected = root.int("selectedTab", 0)
            SavedState(
                savedAt = root.long("savedAt", 0L),
                selectedTab = if (tabs.isEmpty()) 0 else selected.coerceIn(0, tabs.lastIndex),
                brightness = BrightnessLevels.normalize(root.int("brightness", BrightnessLevels.DEFAULT)),
                tabs = tabs
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTab(o: JSONObject): SavedTab? {
        val uri = o.str("uri")?.takeIf { it.isNotBlank() } ?: return null
        val qrCol = o.int("qrCol", -1)
        if (qrCol < 0) return null // thiếu cột QR thì không dựng lại được tab
        val history = ArrayList<HistoryMark>()
        (o.opt("history") as? JSONArray)?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.opt(i) as? JSONObject ?: continue
                val idx = h.int("i", -1)
                if (idx >= 0) history.add(HistoryMark(idx, h.long("t", 0L)))
            }
        }
        return SavedTab(
            uri = uri,
            name = o.str("name") ?: "file.xlsx",
            size = o.long("size", -1L),
            lastModified = o.long("lastModified", 0L),
            sheetIndex = o.int("sheetIndex", 0).coerceAtLeast(0),
            sheetName = o.str("sheetName") ?: "",
            hasHeader = o.bool("hasHeader", true),
            qrCol = qrCol,
            nameCol = o.int("nameCol", -1).coerceAtLeast(-1),
            qtyCol = o.int("qtyCol", -1).coerceAtLeast(-1),
            imeiCol = o.int("imeiCol", -1).coerceAtLeast(-1),
            position = o.int("position", 0).coerceAtLeast(0),
            done = o.bool("done", false),
            history = history.takeLast(MAX_HISTORY)
        )
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else opt(k) as? String
    private fun JSONObject.int(k: String, def: Int): Int =
        if (isNull(k)) def else (opt(k) as? Number)?.toInt() ?: def

    private fun JSONObject.long(k: String, def: Long): Long =
        if (isNull(k)) def else (opt(k) as? Number)?.toLong() ?: def

    private fun JSONObject.bool(k: String, def: Boolean): Boolean =
        if (isNull(k)) def else (opt(k) as? Boolean) ?: def
}

/** Đọc/ghi `<filesDir>/timemachine/state.json`. Ghi atomic: file tạm -> đổi tên đè file chính. */
class TimeMachineStore(val dir: File) {
    private val stateFile get() = File(dir, "state.json")
    private val tmpFile get() = File(dir, "state.json.tmp")

    @Synchronized
    fun write(json: String): Boolean {
        return try {
            dir.mkdirs()
            val tmp = tmpFile
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // rename(2) trên Linux/Android là atomic và ghi đè file đích.
            if (!tmp.renameTo(stateFile)) {
                stateFile.delete()
                tmp.renameTo(stateFile)
            } else {
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    @Synchronized
    fun read(): SavedState? {
        return try {
            val f = stateFile
            if (!f.isFile) null else StateSerializer.fromJson(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun deleteState() {
        stateFile.delete()
        tmpFile.delete()
    }

    /** Xoá toàn bộ thư mục timemachine. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }
}
