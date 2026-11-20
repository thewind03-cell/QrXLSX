/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipFile

class SheetData(val name: String, val rows: List<List<String>>)

class XlsxWorkbook(val sheets: List<SheetData>)

/** Đọc file .xlsx bằng ZipFile + XmlPullParser của Android, không cần thư viện ngoài. */
object XlsxReader {

    /**
     * Đọc workbook. Nếu [onlySheet] >= 0 thì chỉ đọc đúng sheet đó (chỉ số theo danh sách sheet của workbook)
     * để tiết kiệm RAM khi khôi phục tab; workbook trả về khi đó chỉ chứa 1 sheet.
     * Mỗi lần đọc dùng file tạm riêng nên có thể gọi song song an toàn.
     */
    fun read(context: Context, uri: Uri, onlySheet: Int = -1): XlsxWorkbook {
        val tmp = File.createTempFile("import", ".xlsx", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Không mở được file")
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            ZipFile(tmp).use { zip -> return parse(zip, onlySheet) }
        } finally {
            tmp.delete()
        }
    }

    private fun parse(zip: ZipFile, onlySheet: Int): XlsxWorkbook {
        val wbEntry = zip.getEntry("xl/workbook.xml")
            ?: throw IllegalStateException("Đây không phải file .xlsx hợp lệ")
        val shared = zip.getEntry("xl/sharedStrings.xml")
            ?.let { e -> zip.getInputStream(e).use { readSharedStrings(it) } }
            ?: emptyList()
        val sheetRefs = zip.getInputStream(wbEntry).use { readWorkbook(it) }
        val rels = zip.getEntry("xl/_rels/workbook.xml.rels")
            ?.let { e -> zip.getInputStream(e).use { readRels(it) } }
            ?: emptyMap()

        val located = sheetRefs.mapIndexedNotNull { i, (name, rid) ->
            val target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val entry = zip.getEntry(path) ?: return@mapIndexedNotNull null
            name to entry
        }
        val selected = if (onlySheet >= 0) {
            listOf(located.getOrNull(onlySheet) ?: throw IllegalStateException("Không tìm thấy sheet đã chọn"))
        } else {
            located
        }
        val sheets = selected.map { (name, entry) ->
            SheetData(name, zip.getInputStream(entry).use { readSheet(it, shared) })
        }
        if (sheets.isEmpty()) throw IllegalStateException("Không tìm thấy sheet nào")
        return XlsxWorkbook(sheets)
    }

    private fun newParser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply { setInput(input, null) }

    private fun readWorkbook(input: InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = newParser(input)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: "Sheet${out.size + 1}"
                val rid = p.getAttributeValue(null, "r:id") ?: ""
                out.add(name to rid)
            }
            ev = p.next()
        }
        return out
    }

    private fun readRels(input: InputStream): Map<String, String> {
        val out = HashMap<String, String>()
        val p = newParser(input)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = target
            }
            ev = p.next()
        }
        return out
    }

    private fun readSharedStrings(input: InputStream): List<String> {
        val list = ArrayList<String>()
        val p = newParser(input)
        var sb: StringBuilder? = null
        var inT = false
        var inPhonetic = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPhonetic = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && !inPhonetic) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> {
                        list.add(sb?.toString() ?: "")
                        sb = null
                    }
                }
            }
            ev = p.next()
        }
        return list
    }

    private fun readSheet(input: InputStream, shared: List<String>): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        val p = newParser(input)
        var cur: MutableList<String>? = null
        var col = 0
        var type = ""
        val v = StringBuilder()
        val inl = StringBuilder()
        var inV = false
        var inIs = false
        var inT = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val r = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (rows.size + 1)
                        while (rows.size < r - 1) rows.add(ArrayList())
                        val nr = ArrayList<String>()
                        rows.add(nr)
                        cur = nr
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) columnIndex(ref) else (cur?.size ?: 0)
                        type = p.getAttributeValue(null, "t") ?: ""
                        v.setLength(0)
                        inl.setLength(0)
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV) v.append(p.text)
                    if (inT) inl.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val row = cur
                        if (row != null) {
                            val value = when (type) {
                                "s" -> shared.getOrElse(v.toString().trim().toIntOrNull() ?: -1) { "" }
                                "inlineStr" -> inl.toString()
                                "str", "e" -> v.toString()
                                "b" -> if (v.toString().trim() == "1") "TRUE" else "FALSE"
                                else -> normalizeNumber(v.toString())
                            }
                            while (row.size < col) row.add("")
                            if (row.size == col) row.add(value) else row[col] = value
                        }
                    }
                }
            }
            ev = p.next()
        }
        while (rows.isNotEmpty() && rows.last().all { it.isBlank() }) rows.removeAt(rows.lastIndex)
        return rows
    }

    /** Số lưu dạng 3.5E+14 hoặc 123.0 -> chuỗi số nguyên đầy đủ (quan trọng với IMEI lưu kiểu số). */
    private fun normalizeNumber(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return try {
            BigDecimal(s).stripTrailingZeros().toPlainString()
        } catch (_: Exception) {
            s
        }
    }

    private fun columnIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return maxOf(idx - 1, 0)
    }
}

fun columnName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        sb.append('A' + r)
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}
