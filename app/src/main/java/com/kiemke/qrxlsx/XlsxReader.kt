/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class WorkbookPreview(
    val sheetNames: List<String>,
    val sheetIndex: Int,
    val sheet: SheetData
)

data class QrColumnCount(
    val countWithHeader: Int,
    val firstWithHeader: String?,
    val countWithoutHeader: Int,
    val firstWithoutHeader: String?
) {
    fun count(hasHeader: Boolean): Int = if (hasHeader) countWithHeader else countWithoutHeader
    fun firstCode(hasHeader: Boolean): String? = if (hasHeader) firstWithHeader else firstWithoutHeader
}

/** Đọc file .xlsx hai bước bằng ZipFile + XmlPullParser, không cần thư viện ngoài. */
object XlsxReader {

    const val MAX_PREVIEW_ROWS = 50
    const val MAX_COLUMNS = 16_384
    const val MAX_COL_INDEX = MAX_COLUMNS - 1
    const val MAX_ROWS = 1_048_576

    private val defaultParserFactory: () -> XmlPullParser = { Xml.newPullParser() }

    private data class SheetRef(val name: String, val rid: String)
    private data class RelInfo(val target: String, val type: String?)
    private data class LocatedSheet(val name: String, val entry: ZipEntry)

    // --------------------------------------------------------- Android (Context + Uri)

    /** Bước 1: chỉ đọc danh sách tên sheet và tối đa [maxRows] dòng đầu của [sheetIndex]. */
    fun readWorkbookPreview(
        context: Context,
        uri: Uri,
        sheetIndex: Int = 0,
        maxRows: Int = MAX_PREVIEW_ROWS
    ): WorkbookPreview = withTempZip(context, uri) { zip ->
        readWorkbookPreview(zip, sheetIndex, maxRows, defaultParserFactory)
    }

    /** Đếm số mã hợp lệ trong cột [qrCol] của [sheetIndex] mà không giữ các dòng vào RAM. */
    fun countValidQrCodes(
        context: Context,
        uri: Uri,
        sheetIndex: Int,
        qrCol: Int
    ): QrColumnCount = withTempZip(context, uri) { zip ->
        countValidQrCodes(zip, sheetIndex, qrCol, defaultParserFactory)
    }

    /** Bước 2 / khôi phục tab: đọc đúng 1 sheet, chỉ giữ các cột trong [onlyColumns] (nếu khác null). */
    fun readSheet(
        context: Context,
        uri: Uri,
        sheetIndex: Int,
        onlyColumns: Set<Int>? = null
    ): SheetData = withTempZip(context, uri) { zip ->
        readSheet(zip, sheetIndex, onlyColumns, defaultParserFactory)
    }

    private inline fun <T> withTempZip(context: Context, uri: Uri, block: (ZipFile) -> T): T {
        val tmp = File.createTempFile("import", ".xlsx", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Không mở được file")
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            return openZip(tmp, block)
        } finally {
            tmp.delete()
        }
    }

    // --------------------------------------------------------- JVM / InputStream / ZipFile

    fun readWorkbookPreview(
        input: InputStream,
        sheetIndex: Int = 0,
        maxRows: Int = MAX_PREVIEW_ROWS,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): WorkbookPreview = withTempZip(input) { zip ->
        readWorkbookPreview(zip, sheetIndex, maxRows, parserFactory)
    }

    fun countValidQrCodes(
        input: InputStream,
        sheetIndex: Int,
        qrCol: Int,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): QrColumnCount = withTempZip(input) { zip ->
        countValidQrCodes(zip, sheetIndex, qrCol, parserFactory)
    }

    fun readSheet(
        input: InputStream,
        sheetIndex: Int,
        onlyColumns: Set<Int>? = null,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): SheetData = withTempZip(input) { zip ->
        readSheet(zip, sheetIndex, onlyColumns, parserFactory)
    }

    private inline fun <T> withTempZip(input: InputStream, block: (ZipFile) -> T): T {
        val tmp = File.createTempFile("import", ".xlsx")
        try {
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            return openZip(tmp, block)
        } finally {
            tmp.delete()
        }
    }

    private inline fun <T> openZip(file: File, block: (ZipFile) -> T): T {
        return try {
            ZipFile(file).use(block)
        } catch (e: ZipException) {
            throw IllegalStateException("Đây không phải file .xlsx hợp lệ", e)
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Dữ liệu XML trong file không đọc được", e)
        }
    }

    fun readWorkbookPreview(
        zip: ZipFile,
        sheetIndex: Int = 0,
        maxRows: Int = MAX_PREVIEW_ROWS,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): WorkbookPreview {
        val located = locateSheets(zip, parserFactory)
        val target = located.getOrNull(sheetIndex)
            ?: throw IllegalStateException("Không tìm thấy sheet đã chọn")
        val shared = readSharedStringsIfAny(zip, parserFactory)
        val rows = collectRows(zip, target.entry, shared, parserFactory, maxRows = maxRows, onlyColumns = null)
        return WorkbookPreview(
            sheetNames = located.map { it.name },
            sheetIndex = sheetIndex,
            sheet = SheetData(target.name, rows)
        )
    }

    fun countValidQrCodes(
        zip: ZipFile,
        sheetIndex: Int,
        qrCol: Int,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): QrColumnCount {
        if (qrCol < 0) return QrColumnCount(0, null, 0, null)
        val located = locateSheets(zip, parserFactory)
        val target = located.getOrNull(sheetIndex)
            ?: throw IllegalStateException("Không tìm thấy sheet đã chọn")
        val shared = readSharedStringsIfAny(zip, parserFactory)

        var countWithHeader = 0
        var firstRowWithHeader = Int.MAX_VALUE
        var firstWithHeader: String? = null

        var countWithoutHeader = 0
        var firstRowWithoutHeader = Int.MAX_VALUE
        var firstWithoutHeader: String? = null

        val cols = setOf(qrCol)
        zip.getInputStream(target.entry).use { stream ->
            streamSheet(stream, shared, parserFactory, maxRows = Int.MAX_VALUE, onlyColumns = cols) { rowNum, _, value ->
                val code = value.trim()
                if (code.isEmpty()) return@streamSheet
                countWithoutHeader++
                if (rowNum < firstRowWithoutHeader) {
                    firstRowWithoutHeader = rowNum
                    firstWithoutHeader = code
                }
                if (rowNum != 1) {
                    countWithHeader++
                    if (rowNum < firstRowWithHeader) {
                        firstRowWithHeader = rowNum
                        firstWithHeader = code
                    }
                }
            }
        }
        return QrColumnCount(
            countWithHeader = countWithHeader,
            firstWithHeader = firstWithHeader,
            countWithoutHeader = countWithoutHeader,
            firstWithoutHeader = firstWithoutHeader
        )
    }

    fun readSheet(
        zip: ZipFile,
        sheetIndex: Int,
        onlyColumns: Set<Int>? = null,
        parserFactory: () -> XmlPullParser = defaultParserFactory
    ): SheetData {
        val located = locateSheets(zip, parserFactory)
        val target = located.getOrNull(sheetIndex)
            ?: throw IllegalStateException("Không tìm thấy sheet đã chọn")
        val shared = readSharedStringsIfAny(zip, parserFactory)
        val rows = collectRows(zip, target.entry, shared, parserFactory, maxRows = Int.MAX_VALUE, onlyColumns = onlyColumns)
        return SheetData(target.name, rows)
    }

    // --------------------------------------------------------- Internal XML parsing

    private fun locateSheets(zip: ZipFile, parserFactory: () -> XmlPullParser): List<LocatedSheet> {
        val wbEntry = zip.getEntry("xl/workbook.xml")
            ?: throw IllegalStateException("Đây không phải file .xlsx hợp lệ")
        val sheetRefs = try {
            zip.getInputStream(wbEntry).use { readWorkbook(it, parserFactory) }
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Dữ liệu workbook không đọc được", e)
        }
        val rels = zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            try {
                zip.getInputStream(e).use { readRels(it, parserFactory) }
            } catch (e: XmlPullParserException) {
                throw IllegalStateException("Dữ liệu quan hệ sheet không đọc được", e)
            }
        } ?: emptyMap()

        val located = sheetRefs.mapIndexedNotNull { i, ref ->
            val rel = if (ref.rid.isNotEmpty()) rels[ref.rid] else null
            val target = if (rel != null) {
                if (rel.type != null && !rel.type.endsWith("/worksheet", ignoreCase = true)) {
                    return@mapIndexedNotNull null
                }
                if (rel.target.contains("chartsheets/", ignoreCase = true)) {
                    return@mapIndexedNotNull null
                }
                rel.target
            } else {
                "worksheets/sheet${i + 1}.xml"
            }
            val clean = target.replace('\\', '/').removePrefix("/").removePrefix("./")
            val path = when {
                clean.startsWith("xl/", ignoreCase = true) -> clean
                clean.startsWith("../xl/", ignoreCase = true) -> clean.removePrefix("../")
                else -> "xl/$clean"
            }
            val entry = zip.getEntry(path) ?: return@mapIndexedNotNull null
            LocatedSheet(ref.name, entry)
        }
        if (located.isEmpty()) throw IllegalStateException("Không tìm thấy sheet nào")
        return located
    }

    private fun readSharedStringsIfAny(zip: ZipFile, parserFactory: () -> XmlPullParser): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        return try {
            zip.getInputStream(entry).use { readSharedStrings(it, parserFactory) }
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Dữ liệu sharedStrings không đọc được", e)
        }
    }

    private fun collectRows(
        zip: ZipFile,
        entry: ZipEntry,
        shared: List<String>,
        parserFactory: () -> XmlPullParser,
        maxRows: Int,
        onlyColumns: Set<Int>?
    ): List<SheetRow> {
        if (onlyColumns != null && onlyColumns.isEmpty()) return emptyList()
        val rowMap = LinkedHashMap<Int, MutableMap<Int, String>>()
        try {
            zip.getInputStream(entry).use { stream ->
                streamSheet(stream, shared, parserFactory, maxRows = maxRows, onlyColumns = onlyColumns) { rowNum, col, value ->
                    rowMap.getOrPut(rowNum) { HashMap(4) }[col] = value
                }
            }
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("Dữ liệu sheet không đọc được", e)
        }
        return rowMap.entries
            .map { (rowNum, cells) -> SheetRow(rowNum, cells) }
            .sortedBy { it.rowNumber }
    }

    private fun newParser(input: InputStream, parserFactory: () -> XmlPullParser): XmlPullParser =
        parserFactory().apply { setInput(input, null) }

    private fun readWorkbook(input: InputStream, parserFactory: () -> XmlPullParser): List<SheetRef> {
        val out = ArrayList<SheetRef>()
        val p = newParser(input, parserFactory)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: "Sheet${out.size + 1}"
                val rid = readRid(p)
                out.add(SheetRef(name, rid))
            }
            ev = p.next()
        }
        return out
    }

    private fun readRid(p: XmlPullParser): String {
        for (i in 0 until p.attributeCount) {
            val attrName = p.getAttributeName(i)
            if (attrName == "r:id" || (attrName == "id" && p.getAttributeNamespace(i).isNotEmpty())) {
                return p.getAttributeValue(i) ?: ""
            }
        }
        return ""
    }

    private fun readRels(input: InputStream, parserFactory: () -> XmlPullParser): Map<String, RelInfo> {
        val out = HashMap<String, RelInfo>()
        val p = newParser(input, parserFactory)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                val type = p.getAttributeValue(null, "Type")
                if (id != null && target != null) out[id] = RelInfo(target, type)
            }
            ev = p.next()
        }
        return out
    }

    private fun readSharedStrings(input: InputStream, parserFactory: () -> XmlPullParser): List<String> {
        val list = ArrayList<String>()
        val p = newParser(input, parserFactory)
        var sb: StringBuilder? = null
        var inT = false
        var inPhonetic = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPhonetic = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && !inPhonetic) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
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

    private inline fun streamSheet(
        input: InputStream,
        shared: List<String>,
        noinline parserFactory: () -> XmlPullParser,
        maxRows: Int,
        onlyColumns: Set<Int>?,
        onCell: (rowNumber: Int, col: Int, value: String) -> Unit
    ) {
        val p = newParser(input, parserFactory)
        var lastRowNum = 0
        var currentRowNum = -1
        var rowCount = 0
        var nextCol = 0
        var col = 0
        var inDesiredCol = false
        var type = ""
        val v = StringBuilder()
        val inl = StringBuilder()
        var inV = false
        var inIs = false
        var inT = false
        var inPhonetic = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "row" -> {
                        if (rowCount >= maxRows) return
                        val rAttr = p.getAttributeValue(null, "r")
                        val r = if (rAttr != null) {
                            val parsed = rAttr.toIntOrNull()
                                ?: throw IllegalStateException("Dữ liệu dòng không hợp lệ: $rAttr")
                            if (parsed <= 0) throw IllegalStateException("Dữ liệu dòng không hợp lệ: $rAttr")
                            parsed
                        } else {
                            lastRowNum + 1
                        }
                        if (r > MAX_ROWS) {
                            throw IllegalStateException("Số dòng vượt quá giới hạn Excel (1.048.576)")
                        }
                        currentRowNum = r
                        lastRowNum = r
                        rowCount++
                        nextCol = 0
                    }
                    "c" -> if (currentRowNum > 0) {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) columnIndex(ref) else nextCol
                        nextCol = (col + 1).coerceAtMost(MAX_COL_INDEX)
                        inDesiredCol = onlyColumns == null || col in onlyColumns
                        if (inDesiredCol) {
                            type = p.getAttributeValue(null, "t") ?: ""
                            v.setLength(0)
                            inl.setLength(0)
                        }
                        inV = false
                        inIs = false
                        inT = false
                        inPhonetic = false
                    }
                    "v" -> if (inDesiredCol) inV = true
                    "is" -> if (inDesiredCol) inIs = true
                    "rPh" -> if (inDesiredCol && inIs) inPhonetic = true
                    "t" -> if (inDesiredCol && inIs) inT = true
                }
                XmlPullParser.TEXT -> if (inDesiredCol) {
                    if (inV) v.append(p.text)
                    if (inT && !inPhonetic) inl.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "is" -> inIs = false
                    "c" -> {
                        if (currentRowNum > 0 && inDesiredCol) {
                            val value = when (type) {
                                "s" -> {
                                    val idxStr = v.toString().trim()
                                    if (idxStr.isEmpty()) "" else shared.getOrElse(idxStr.toIntOrNull() ?: -1) { "" }
                                }
                                "inlineStr" -> inl.toString()
                                "str", "e" -> v.toString()
                                "b" -> {
                                    val raw = v.toString().trim()
                                    if (raw.isEmpty()) "" else if (raw == "1") "TRUE" else "FALSE"
                                }
                                else -> normalizeNumber(v.toString())
                            }
                            if (value.isNotEmpty()) {
                                onCell(currentRowNum, col, value)
                            }
                        }
                        inDesiredCol = false
                    }
                    "row" -> currentRowNum = -1
                }
            }
            ev = p.next()
        }
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

    internal fun columnIndex(ref: String): Int {
        var idx = 0L
        for (ch in ref) {
            if (ch.isLetter()) {
                val upper = ch.uppercaseChar()
                if (upper !in 'A'..'Z') break
                idx = idx * 26 + (upper - 'A' + 1)
                if (idx > MAX_COLUMNS) return MAX_COL_INDEX
            } else {
                break
            }
        }
        return (idx - 1).toInt().coerceIn(0, MAX_COL_INDEX)
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
