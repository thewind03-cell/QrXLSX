/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(
    draft: SetupDraft?,
    busy: Boolean,
    error: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onStart: (SetupChoice) -> Unit,
    onClearData: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    // Mở từ nút "+" thì Back quay lại tab cũ; nếu là màn đầu tiên thì Back thoát app như cũ.
    BackHandler(enabled = canGoBack, onBack = onBack)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack) { Text("← Quay lại") }
        }
        Text(
            if (canGoBack) "Thêm tab mới" else "QRcode Kiểm Kê",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Button(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (draft == null) "Chọn file Excel (.xlsx)" else "Đổi file")
        }
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (draft != null) SetupOptions(draft, busy, onStart)

        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Xoá dữ liệu đã lưu")
        }

        Spacer(Modifier.weight(1f))
        Text(
            "© 2026 Thanh Phong Do · thewind03@gmail.com",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Xoá dữ liệu đã lưu?") },
            text = {
                Text(
                    "Xoá tiến độ và quyền truy cập file mà ứng dụng đã ghi nhớ trên máy. " +
                        "Các tab đang mở vẫn dùng được nhưng sẽ không được lưu lại cho tới khi bạn thêm tab mới."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearData()
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Huỷ") } }
        )
    }
}

private fun guess(sheet: SheetData, hasHeader: Boolean, test: (String) -> Boolean): Int {
    if (!hasHeader) return -1
    val header = sheet.rows.firstOrNull() ?: return -1
    return header.indexOfFirst { test(it.trim().lowercase()) }
}

private fun columnLabel(sheet: SheetData, i: Int, hasHeader: Boolean): String {
    val h = if (hasHeader) sheet.rows.firstOrNull()?.getOrNull(i)?.trim().orEmpty() else ""
    val letter = columnName(i)
    return if (h.isEmpty()) "Cột $letter" else "$letter – $h"
}

@Composable
fun SetupOptions(draft: SetupDraft, busy: Boolean, onStart: (SetupChoice) -> Unit) {
    val workbook = draft.workbook
    var sheetIdx by remember(workbook) { mutableIntStateOf(0) }
    val sheet = workbook.sheets[sheetIdx]
    var hasHeader by remember(workbook) { mutableStateOf(true) }

    val colCount = remember(sheet) { sheet.rows.take(50).maxOfOrNull { it.size } ?: 0 }
    val labels = remember(sheet, hasHeader) { (0 until colCount).map { columnLabel(sheet, it, hasHeader) } }

    var qrCol by remember(sheet, hasHeader) {
        val byImei = guess(sheet, hasHeader) { it.contains("imei") }
        mutableIntStateOf(
            if (byImei >= 0) byImei
            else guess(sheet, hasHeader) { it.contains("mã") || it.contains("sku") || it.contains("code") }
        )
    }
    var nameCol by remember(sheet, hasHeader) {
        mutableIntStateOf(guess(sheet, hasHeader) { it.contains("tên") || it.contains("name") })
    }
    var qtyCol by remember(sheet, hasHeader) {
        mutableIntStateOf(
            guess(sheet, hasHeader) {
                it.contains("số lượng") || it.contains("so luong") || it.contains("qty") ||
                    it.contains("quantity") || it == "sl"
            }
        )
    }
    var imeiCol by remember(sheet, hasHeader) { mutableIntStateOf(-1) }

    val items = remember(sheet, hasHeader, qrCol) {
        if (qrCol < 0) emptyList() else buildItems(sheet, hasHeader, qrCol, -1, -1, -1)
    }

    Text("File: ${draft.meta.name}", fontSize = 14.sp)

    if (workbook.sheets.size > 1) {
        Picker("Sheet", workbook.sheets.map { it.name }, sheetIdx, false) { sheetIdx = it }
    }

    Row(
        Modifier.fillMaxWidth().clickable { hasHeader = !hasHeader },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = hasHeader, onCheckedChange = { hasHeader = it })
        Text("Dòng đầu là tiêu đề")
    }

    Picker("Cột tạo QR (bắt buộc)", labels, qrCol, false) { qrCol = it }
    Picker("Hiển thị thêm: Tên sản phẩm", labels, nameCol, true) { nameCol = it }
    Picker("Hiển thị thêm: Số lượng", labels, qtyCol, true) { qtyCol = it }
    Picker("Hiển thị thêm: IMEI", labels, imeiCol, true) { imeiCol = it }

    if (qrCol >= 0) {
        Text(
            "Có ${items.size} mã hợp lệ" +
                (items.firstOrNull()?.let { " · mã đầu tiên: ${it.code}" } ?: ""),
            fontSize = 14.sp
        )
    }

    Button(
        onClick = {
            onStart(SetupChoice(sheetIdx, sheet.name, hasHeader, qrCol, nameCol, qtyCol, imeiCol))
        },
        enabled = qrCol >= 0 && items.isNotEmpty() && !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text("Bắt đầu", fontSize = 18.sp)
    }
}

@Composable
fun Picker(
    label: String,
    options: List<String>,
    selected: Int,
    allowNone: Boolean,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val text = if (selected < 0) "— Không —" else options.getOrElse(selected) { "" }
    Column {
        Text(label, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text, modifier = Modifier.weight(1f), maxLines = 1)
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (allowNone) {
                    DropdownMenuItem(text = { Text("— Không —") }, onClick = { onSelect(-1); open = false })
                }
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}
