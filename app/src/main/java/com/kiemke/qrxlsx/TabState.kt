/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TabStatus { Loading, Ready, Error }

/**
 * Một tab = một phiên: file + sheet + cấu hình cột + vị trí đang xem.
 * Chỉ giữ danh sách [Item] trong RAM (không giữ workbook).
 */
@Stable
class TabState(val id: Long, saved: SavedTab) {
    var uri by mutableStateOf(saved.uri)
    var name by mutableStateOf(saved.name)
    var size by mutableLongStateOf(saved.size)
    var lastModified by mutableLongStateOf(saved.lastModified)

    val sheetIndex = saved.sheetIndex
    val sheetName = saved.sheetName
    val hasHeader = saved.hasHeader
    val qrCol = saved.qrCol
    val nameCol = saved.nameCol
    val qtyCol = saved.qtyCol
    val imeiCol = saved.imeiCol

    var items by mutableStateOf<List<Item>>(emptyList())
    var status by mutableStateOf(TabStatus.Loading)
    var error by mutableStateOf<String?>(null)

    /** File đã đổi so với lần lưu trước. */
    var changed by mutableStateOf(false)

    /** Thông tin file thực tế vừa đọc (dùng để cập nhật mốc so sánh khi người dùng xác nhận). */
    var currentMeta: FileMeta? = null

    var index by mutableIntStateOf(saved.position)
    var done by mutableStateOf(saved.done)
    var history by mutableStateOf(saved.history)

    val showName get() = nameCol >= 0
    val showQty get() = qtyCol >= 0
    val showImei get() = imeiCol >= 0

    fun toSaved() = SavedTab(
        uri = uri,
        name = name,
        size = size,
        lastModified = lastModified,
        sheetIndex = sheetIndex,
        sheetName = sheetName,
        hasHeader = hasHeader,
        qrCol = qrCol,
        nameCol = nameCol,
        qtyCol = qtyCol,
        imeiCol = imeiCol,
        position = index.coerceAtLeast(0),
        done = done,
        history = history
    )
}

/** Đọc lại danh sách mã của một tab từ file (gọi ở luồng nền). */
object TabLoader {
    sealed interface Result {
        class Ok(val items: List<Item>, val meta: FileMeta?) : Result
        class Fail(val message: String) : Result
    }

    fun load(context: Context, tab: TabState): Result {
        val uri = Uri.parse(tab.uri)
        return try {
            val meta = FileMetaReader.read(context, uri)
            val cols = setOf(tab.qrCol, tab.nameCol, tab.qtyCol, tab.imeiCol).filter { it >= 0 }.toSet()
            val sheet = XlsxReader.readSheet(context, uri, sheetIndex = tab.sheetIndex, onlyColumns = cols)
            val items = buildItems(
                sheet, tab.hasHeader, tab.qrCol, tab.nameCol, tab.qtyCol, tab.imeiCol
            )
            if (items.isEmpty()) {
                Result.Fail("Không có mã hợp lệ ở cột đã chọn (file có thể đã thay đổi)")
            } else {
                Result.Ok(items, meta)
            }
        } catch (e: OutOfMemoryError) {
            Result.Fail("Không đủ bộ nhớ để đọc file")
        } catch (e: Exception) {
            Result.Fail(e.message ?: e.javaClass.simpleName)
        }
    }
}
