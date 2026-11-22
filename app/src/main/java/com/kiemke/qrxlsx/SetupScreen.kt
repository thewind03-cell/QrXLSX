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
import androidx.compose.runtime.LaunchedEffect
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
    onSelectSheet: (Int) -> Unit,
    onCountQr: (sheetIndex: Int, qrCol: Int) -> Unit,
    onClearCount: () -> Unit,
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
            if (canGoBack) "Thêm tab mới" else "QR Kiểm Kê",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Button(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (draft == null) "Chọn file Excel (.xlsx)" else "Đổi file")
        }
        if (busy || draft?.sheetLoading == true) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (draft != null) {
            SetupOptions(
                draft = draft,
                busy = busy,
                onSelectSheet = onSelectSheet,
                onCountQr = onCountQr,
                onClearCount = onClearCount,
                onStart = onStart
            )
        }

        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Xoá dữ liệu đã lưu")
        }

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

private fun guess(headerRow: SheetRow?, colCount: Int, hasHeader: Boolean, test: (String) -> Boolean): Int {
    if (!hasHeader || headerRow == null) return -1
    for (i in 0 until colCount) {
        if (test(headerRow.cell(i).trim().lowercase())) return i
    }
    return -1
}

private fun columnLabel(headerRow: SheetRow?, i: Int, hasHeader: Boolean): String {
    val h = if (hasHeader) headerRow?.cell(i)?.trim().orEmpty() else ""
    val letter = columnName(i)
    return if (h.isEmpty()) "Cột $letter" else "$letter – $h"
}

@Composable
fun SetupOptions(
    draft: SetupDraft,
    busy: Boolean,
    onSelectSheet: (Int) -> Unit,
    onCountQr: (sheetIndex: Int, qrCol: Int) -> Unit,
    onClearCount: () -> Unit,
    onStart: (SetupChoice) -> Unit
) {
    val sheet = draft.preview
    val sheetIdx = draft.sheetIndex
    var hasHeader by remember(draft) { mutableStateOf(true) }

    val headerRow = remember(sheet) { sheet.rows.firstOrNull { it.rowNumber == 1 } }
    val colCount = remember(sheet) { sheet.rows.maxOfOrNull { it.maxCol + 1 } ?: 0 }
    val labels = remember(headerRow, colCount, hasHeader) {
        (0 until colCount).map { columnLabel(headerRow, it, hasHeader) }
    }

    var qrCol by remember(sheet, hasHeader) {
        val byImei = guess(headerRow, colCount, hasHeader) { it.contains("imei") }
        mutableIntStateOf(
            if (byImei >= 0) byImei
            else guess(headerRow, colCount, hasHeader) { it.contains("mã") || it.contains("sku") || it.contains("code") }
        )
    }
    var nameCol by remember(sheet, hasHeader) {
        mutableIntStateOf(guess(headerRow, colCount, hasHeader) { it.contains("tên") || it.contains("name") })
    }
    var qtyCol by remember(sheet, hasHeader) {
        mutableIntStateOf(
            guess(headerRow, colCount, hasHeader) {
                it.contains("số lượng") || it.contains("so luong") || it.contains("qty") ||
                    it.contains("quantity") || it == "sl"
            }
        )
    }
    var imeiCol by remember(sheet, hasHeader) { mutableIntStateOf(-1) }

    LaunchedEffect(draft, sheet, qrCol, draft.sheetLoading) {
        if (!draft.sheetLoading && qrCol >= 0) {
            onCountQr(sheetIdx, qrCol)
        } else {
            onClearCount()
        }
    }

    val validCount = draft.countResult?.count(hasHeader) ?: 0
    val firstCode = draft.countResult?.firstCode(hasHeader)
    val controlsEnabled = !busy && !draft.sheetLoading

    Text("File: ${draft.meta.name}", fontSize = 14.sp)

    if (draft.sheetNames.size > 1) {
        Picker("Sheet", draft.sheetNames, sheetIdx, false, enabled = !busy, onSelect = onSelectSheet)
    }

    Row(
        Modifier.fillMaxWidth().clickable(enabled = controlsEnabled) { hasHeader = !hasHeader },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = hasHeader, enabled = controlsEnabled, onCheckedChange = { hasHeader = it })
        Text("Dòng đầu là tiêu đề")
    }

    Picker("Cột tạo QR (bắt buộc)", labels, qrCol, false, enabled = controlsEnabled) { qrCol = it }
    Picker("Hiển thị thêm: Tên sản phẩm", labels, nameCol, true, enabled = controlsEnabled) { nameCol = it }
    Picker("Hiển thị thêm: Số lượng", labels, qtyCol, true, enabled = controlsEnabled) { qtyCol = it }
    Picker("Hiển thị thêm: IMEI", labels, imeiCol, true, enabled = controlsEnabled) { imeiCol = it }

    draft.countError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
    }

    if (qrCol >= 0 && !draft.sheetLoading && draft.countError == null) {
        if (draft.counting) {
            Text("Đang đếm…", fontSize = 14.sp)
        } else if (draft.countResult != null) {
            Text(
                "Có $validCount mã hợp lệ" +
                    (firstCode?.let { " · mã đầu tiên: $it" } ?: ""),
                fontSize = 14.sp
            )
        }
    }

    Button(
        onClick = {
            val sheetName = draft.sheetNames.getOrElse(sheetIdx) { sheet.name }
            onStart(SetupChoice(sheetIdx, sheetName, hasHeader, qrCol, nameCol, qtyCol, imeiCol))
        },
        enabled = qrCol >= 0 && !draft.sheetLoading && !draft.counting && validCount > 0 && !busy && draft.countError == null,
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
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val text = if (selected < 0) "— Không —" else options.getOrElse(selected) { "" }
    Column {
        Text(label, fontSize = 13.sp)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
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
