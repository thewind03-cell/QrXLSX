/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** Một dòng dữ liệu để hiển thị. Chỉ nằm trong RAM, không bao giờ ghi xuống đĩa. */
data class Item(
    val rowNumber: Int,
    val code: String,
    val name: String,
    val qty: String,
    val imei: String
)

fun buildItems(
    sheet: SheetData,
    hasHeader: Boolean,
    qrCol: Int,
    nameCol: Int,
    qtyCol: Int,
    imeiCol: Int
): List<Item> {
    fun cell(row: List<String>, c: Int): String =
        if (c < 0) "" else row.getOrElse(c) { "" }.trim()

    val out = ArrayList<Item>()
    for (i in (if (hasHeader) 1 else 0) until sheet.rows.size) {
        val r = sheet.rows[i]
        val code = cell(r, qrCol)
        if (code.isEmpty()) continue
        out.add(Item(i + 1, code, cell(r, nameCol), cell(r, qtyCol), cell(r, imeiCol)))
    }
    return out
}

/** Thông tin nhận diện file tại thời điểm chọn/lưu (size = -1 hoặc lastModified = 0 nghĩa là không biết). */
data class FileMeta(val name: String, val size: Long, val lastModified: Long)

object FileMetaReader {
    fun read(context: Context, uri: Uri): FileMeta? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                val m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                FileMeta(
                    name = (if (n >= 0 && !c.isNull(n)) c.getString(n) else null) ?: "file.xlsx",
                    size = if (s >= 0 && !c.isNull(s)) c.getLong(s) else -1L,
                    lastModified = if (m >= 0 && !c.isNull(m)) c.getLong(m) else 0L
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Rút gọn tên file/sheet để vừa thanh tab. */
fun shortenName(name: String, max: Int = 14): String {
    val base = if (name.endsWith(".xlsx", ignoreCase = true)) name.dropLast(5) else name
    return if (base.length <= max) base else base.take(max - 1) + "…"
}
