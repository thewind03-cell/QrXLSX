/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val kxml: () -> XmlPullParser = { KXmlParser() }
    private val kxmlNs: () -> XmlPullParser = {
        KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    }

    private fun buildXlsxBytes(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun writeTempXlsx(name: String, entries: Map<String, String>): File {
        val file = File(tmp.root, name)
        file.writeBytes(buildXlsxBytes(entries))
        return file
    }

    private fun simpleWorkbook(sheetName: String = "Sheet1") = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="$sheetName" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun simpleRels(target: String = "worksheets/sheet1.xml") = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$target"/>
        </Relationships>
    """.trimIndent()

    @Test
    fun sharedStringsWithRPh_inlineStr_scientificAnd15DigitImei() {
        val sharedStrings = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
              <si><t>Mã QR</t></si>
              <si><t>Tên SP</t></si>
              <si>
                <r><t>Điện thoại </t></r>
                <r><t>A1</t></r>
                <rPh sb="0" eb="4"><t>DENWA</t></rPh>
              </si>
              <si><t>SP-001</t></si>
            </sst>
        """.trimIndent()

        val sheet1 = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="inlineStr"><is><t>Số lượng</t></is></c>
                  <c r="D1" t="inlineStr"><is><t>IMEI</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>3</v></c>
                  <c r="B2" t="s"><v>2</v></c>
                  <c r="C2"><v>12.0</v></c>
                  <c r="D2"><v>3.5E+14</v></c>
                </row>
                <row r="3">
                  <c r="A3" t="inlineStr"><is><t>SP-002</t><rPh sb="0" eb="2"><t>ignored</t></rPh></is></c>
                  <c r="B3" t="inlineStr"><is><r><t>Máy </t></r><r><t>B2</t></r></is></c>
                  <c r="C3"><v>5</v></c>
                  <c r="D3"><v>356789012345678</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val bytes = buildXlsxBytes(
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/sharedStrings.xml" to sharedStrings,
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        val preview = XlsxReader.readWorkbookPreview(ByteArrayInputStream(bytes), parserFactory = kxml)
        assertEquals(listOf("Sheet1"), preview.sheetNames)
        assertEquals(3, preview.sheet.rows.size)

        val count = XlsxReader.countValidQrCodes(ByteArrayInputStream(bytes), 0, qrCol = 0, parserFactory = kxml)
        assertEquals(2, count.countWithHeader)
        assertEquals("SP-001", count.firstWithHeader)
        assertEquals(3, count.countWithoutHeader)
        assertEquals("Mã QR", count.firstWithoutHeader)

        val sheet = XlsxReader.readSheet(ByteArrayInputStream(bytes), 0, onlyColumns = setOf(0, 1, 2, 3), parserFactory = kxml)
        val items = buildItems(sheet, hasHeader = true, qrCol = 0, nameCol = 1, qtyCol = 2, imeiCol = 3)
        assertEquals(2, items.size)
        assertEquals(Item(2, "SP-001", "Điện thoại A1", "12", "350000000000000"), items[0])
        assertEquals(Item(3, "SP-002", "Máy B2", "5", "356789012345678"), items[1])
    }

    @Test
    fun interleavedChartSheet_isSkippedAndSheetsResolvedCorrectly() {
        val workbook = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="KhoA" sheetId="1" r:id="rId1"/>
                <sheet name="BieuDo" sheetId="2" r:id="rId2"/>
                <sheet name="KhoB" sheetId="3" r:id="rId3"/>
              </sheets>
            </workbook>
        """.trimIndent()

        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chartsheet" Target="chartsheets/sheet1.xml"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent()

        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>Mã</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>KHOA-1</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val sheet2 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>Mã</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>KHOB-1</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val file = writeTempXlsx(
            "chart_interleaved.xlsx",
            mapOf(
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to rels,
                "xl/worksheets/sheet1.xml" to sheet1,
                "xl/chartsheets/sheet1.xml" to "<chartsheet/>",
                "xl/worksheets/sheet2.xml" to sheet2
            )
        )

        // Kiểm tra với cả parser không bật namespace và parser bật namespace (như Xml.newPullParser trên Android).
        for (factory in listOf(kxml, kxmlNs)) {
            ZipFile(file).use { zip ->
                val p0 = XlsxReader.readWorkbookPreview(zip, sheetIndex = 0, parserFactory = factory)
                assertEquals(listOf("KhoA", "KhoB"), p0.sheetNames)
                assertEquals("KhoA", p0.sheet.name)

                val s1 = XlsxReader.readSheet(zip, sheetIndex = 1, onlyColumns = setOf(0), parserFactory = factory)
                assertEquals("KhoB", s1.name)
                val items = buildItems(s1, hasHeader = true, qrCol = 0, nameCol = -1, qtyCol = -1, imeiCol = -1)
                assertEquals(listOf(Item(2, "KHOB-1", "", "", "")), items)
            }
        }
    }

    @Test
    fun row1048576_doesNotAllocateEmptyPaddingRows_andKeepsRealRowNumber() {
        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>Code</t></is></c></row>
                <row r="1048576"><c r="A1048576" t="inlineStr"><is><t>LAST-CODE</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val file = writeTempXlsx(
            "max_row.xlsx",
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        ZipFile(file).use { zip ->
            val preview = XlsxReader.readWorkbookPreview(zip, parserFactory = kxml)
            assertEquals(2, preview.sheet.rows.size)
            assertEquals(1, preview.sheet.rows[0].rowNumber)
            assertEquals(1_048_576, preview.sheet.rows[1].rowNumber)

            val sheet = XlsxReader.readSheet(zip, 0, onlyColumns = setOf(0), parserFactory = kxml)
            assertEquals(2, sheet.rows.size)
            val items = buildItems(sheet, hasHeader = true, qrCol = 0, nameCol = -1, qtyCol = -1, imeiCol = -1)
            assertEquals(listOf(Item(1_048_576, "LAST-CODE", "", "", "")), items)

            val count = XlsxReader.countValidQrCodes(zip, 0, qrCol = 0, parserFactory = kxml)
            assertEquals(1, count.countWithHeader)
            assertEquals("LAST-CODE", count.firstWithHeader)
        }
    }

    @Test
    fun rowExceeding1048576_throwsClearError() {
        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1048577"><c r="A1048577" t="inlineStr"><is><t>TOO-FAR</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val bytes = buildXlsxBytes(
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        assertThrows(IllegalStateException::class.java) {
            XlsxReader.readSheet(ByteArrayInputStream(bytes), 0, onlyColumns = setOf(0), parserFactory = kxml)
        }
    }

    @Test
    fun missingCells_headerRowMissing_andOutOfOrderRows() {
        // Dòng 1 bị thiếu (không có r="1"), các dòng không theo thứ tự (r=5, r=2, r=4), ô thiếu.
        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="5">
                  <c r="B5" t="inlineStr"><is><t>QR-5</t></is></c>
                  <c r="D5"><v>9</v></c>
                </row>
                <row r="2">
                  <c r="B2" t="inlineStr"><is><t>QR-2</t></is></c>
                  <c r="C2" t="inlineStr"><is><t>Ten-2</t></is></c>
                </row>
                <row r="4">
                  <c r="C4" t="inlineStr"><is><t>KhongCoMa</t></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val file = writeTempXlsx(
            "unordered.xlsx",
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        ZipFile(file).use { zip ->
            val sheet = XlsxReader.readSheet(zip, 0, onlyColumns = setOf(1, 2, 3), parserFactory = kxml)
            // Chỉ giữ dòng có dữ liệu ở cột đã chọn, sắp theo rowNumber thật.
            assertEquals(listOf(2, 4, 5), sheet.rows.map { it.rowNumber })

            // hasHeader = true chỉ bỏ dòng 1; vì dòng 1 không tồn tại nên dòng 2 KHÔNG bị bỏ.
            val items = buildItems(sheet, hasHeader = true, qrCol = 1, nameCol = 2, qtyCol = 3, imeiCol = -1)
            assertEquals(
                listOf(
                    Item(2, "QR-2", "Ten-2", "", ""),
                    Item(5, "QR-5", "", "9", "")
                ),
                items
            )

            val count = XlsxReader.countValidQrCodes(zip, 0, qrCol = 1, parserFactory = kxml)
            assertEquals(2, count.countWithHeader)
            assertEquals("QR-2", count.firstWithHeader)
            assertEquals(2, count.countWithoutHeader)
            assertEquals("QR-2", count.firstWithoutHeader)
        }
    }

    @Test
    fun selectedColumnsOnly_dropsUnselectedColumns_andClampsHugeColumnIndex() {
        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>BoQua</t></is></c>
                  <c r="B1" t="inlineStr"><is><t>QR</t></is></c>
                  <c r="C1" t="inlineStr"><is><t>BoQua2</t></is></c>
                  <c r="ZZZZ1" t="inlineStr"><is><t>Far</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="inlineStr"><is><t>SecretA</t></is></c>
                  <c r="B2" t="inlineStr"><is><t>CODE-2</t></is></c>
                  <c r="C2" t="inlineStr"><is><t>SecretC</t></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val bytes = buildXlsxBytes(
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        val preview = XlsxReader.readWorkbookPreview(ByteArrayInputStream(bytes), parserFactory = kxml)
        assertEquals(XlsxReader.MAX_COL_INDEX, preview.sheet.rows[0].maxCol)

        val sheet = XlsxReader.readSheet(ByteArrayInputStream(bytes), 0, onlyColumns = setOf(1), parserFactory = kxml)
        assertEquals(setOf(1), sheet.rows[1].cells.keys)
        assertEquals("", sheet.rows[1].cell(0))
        assertEquals("CODE-2", sheet.rows[1].cell(1))
        assertEquals("", sheet.rows[1].cell(2))
    }

    @Test
    fun preview_readsAtMost50Rows() {
        val sb = StringBuilder()
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        for (r in 1..80) {
            sb.append("<row r=\"$r\"><c r=\"A$r\" t=\"inlineStr\"><is><t>C-$r</t></is></c></row>")
        }
        sb.append("</sheetData></worksheet>")

        val bytes = buildXlsxBytes(
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sb.toString()
            )
        )

        val preview = XlsxReader.readWorkbookPreview(ByteArrayInputStream(bytes), parserFactory = kxml)
        assertEquals(50, preview.sheet.rows.size)
        assertEquals(50, preview.sheet.rows.last().rowNumber)

        val count = XlsxReader.countValidQrCodes(ByteArrayInputStream(bytes), 0, qrCol = 0, parserFactory = kxml)
        assertEquals(79, count.countWithHeader)
        assertEquals("C-2", count.firstWithHeader)
        assertEquals(80, count.countWithoutHeader)
        assertEquals("C-1", count.firstWithoutHeader)
    }

    @Test
    fun nonXlsxFile_throwsError() {
        val notZip = "plain text not a zip".toByteArray()
        assertThrows(IllegalStateException::class.java) {
            XlsxReader.readWorkbookPreview(ByteArrayInputStream(notZip), parserFactory = kxml)
        }

        val zipWithoutWorkbook = buildXlsxBytes(mapOf("hello.txt" to "world"))
        assertThrows(IllegalStateException::class.java) {
            XlsxReader.readWorkbookPreview(ByteArrayInputStream(zipWithoutWorkbook), parserFactory = kxml)
        }
    }

    @Test
    fun workbookWithNoSheets_throwsError() {
        val emptyWb = """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheets/>
            </workbook>
        """.trimIndent()
        val bytes = buildXlsxBytes(mapOf("xl/workbook.xml" to emptyWb))

        assertThrows(IllegalStateException::class.java) {
            XlsxReader.readWorkbookPreview(ByteArrayInputStream(bytes), parserFactory = kxml)
        }
    }

    @Test
    fun emptyQrColumn_returnsZeroCountAndEmptyItems() {
        val sheet1 = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>QR</t></is></c>
                  <c r="B1" t="inlineStr"><is><t>Ten</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="inlineStr"><is><t>   </t></is></c>
                  <c r="B2" t="inlineStr"><is><t>SP A</t></is></c>
                </row>
                <row r="3">
                  <c r="B3" t="inlineStr"><is><t>SP B</t></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val bytes = buildXlsxBytes(
            mapOf(
                "xl/workbook.xml" to simpleWorkbook(),
                "xl/_rels/workbook.xml.rels" to simpleRels(),
                "xl/worksheets/sheet1.xml" to sheet1
            )
        )

        val count = XlsxReader.countValidQrCodes(ByteArrayInputStream(bytes), 0, qrCol = 0, parserFactory = kxml)
        assertEquals(0, count.countWithHeader)
        assertNull(count.firstWithHeader)
        assertEquals(1, count.countWithoutHeader)
        assertEquals("QR", count.firstWithoutHeader)

        val sheet = XlsxReader.readSheet(ByteArrayInputStream(bytes), 0, onlyColumns = setOf(0, 1), parserFactory = kxml)
        val items = buildItems(sheet, hasHeader = true, qrCol = 0, nameCol = 1, qtyCol = -1, imeiCol = -1)
        assertTrue(items.isEmpty())
    }
}
