/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WarningYellow = Color(0xFFFFF3CD)
private val WarningText = Color(0xFF664D03)

@Composable
fun DisplayScreen(vm: AppViewModel, onRepick: (Long) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val tab = vm.currentTab ?: return

    var showExit by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var closeTarget by remember { mutableStateOf<TabState?>(null) }

    BackHandler { showExit = true }

    // Giữ màn hình sáng và dùng phím âm lượng để chuyển mã; khi rời màn thì trả độ sáng hệ thống.
    DisposableEffect(Unit) {
        val window = activity.window
        val oldBrightness = window.attributes.screenBrightness
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        KeyBus.handler = { code -> vm.onVolumeKey(code) }
        onDispose {
            KeyBus.handler = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.also { it.screenBrightness = oldBrightness }
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val level = vm.brightness
    LaunchedEffect(level) {
        activity.window.attributes = activity.window.attributes.also { it.screenBrightness = level / 100f }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        TabBar(
            vm = vm,
            onAdd = {
                if (!vm.requestAddTab()) Toast.makeText(context, "Tối đa $MAX_TABS tab", Toast.LENGTH_SHORT).show()
            },
            onClose = { closeTarget = it }
        )
        if (vm.restoreTotal > 0) {
            LinearProgressIndicator(
                progress = { vm.restoreDone.toFloat() / vm.restoreTotal },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Đang khôi phục phiên: ${vm.restoreDone}/${vm.restoreTotal} tab",
                fontSize = 12.sp
            )
        }

        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showExit = true }) { Text("← Thoát") }
            TextButton(onClick = { showHistory = true }, enabled = tab.status == TabStatus.Ready) {
                Text("🕘 Lịch sử")
            }
            Spacer(Modifier.weight(1f))
            // Màn ngang thấp: gộp thanh độ sáng lên cùng hàng để nhường chỗ cho QR.
            if (landscape) {
                BrightnessRow(
                    level,
                    onMinus = { vm.changeBrightness(-1) },
                    onPlus = { vm.changeBrightness(1) },
                    modifier = Modifier.height(48.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            if (tab.status == TabStatus.Ready) {
                TextButton(onClick = { showJump = true }) {
                    Text(
                        "${tab.index + 1} / ${tab.items.size}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (!landscape) {
            BrightnessRow(level, onMinus = { vm.changeBrightness(-1) }, onPlus = { vm.changeBrightness(1) })
        }

        when (tab.status) {
            TabStatus.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Đang đọc file…")
                    Text(tab.name, fontSize = 13.sp)
                }
            }
            TabStatus.Error -> ErrorContent(tab, Modifier.weight(1f), onRepick = { onRepick(tab.id) })
            TabStatus.Ready -> ReadyContent(vm, tab, landscape, Modifier.weight(1f))
        }
    }

    if (showJump) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("Nhảy tới số thứ tự") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { vm.jump(tab, it - 1) }
                    showJump = false
                }) { Text("Đi") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("Huỷ") } }
        )
    }

    if (showHistory) HistoryDialog(
        tab = tab,
        onPick = {
            vm.jump(tab, it)
            showHistory = false
        },
        onDismiss = { showHistory = false }
    )

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Thoát ứng dụng?") },
            text = { Text("Tiến độ đã được lưu") },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    vm.saveNow()
                    activity.finish()
                }) { Text("Thoát") }
            },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Ở lại") } }
        )
    }

    closeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { closeTarget = null },
            title = { Text("Đóng tab?") },
            text = { Text("Đóng \"${shortenName(target.name, 24)}\"${sheetSuffix(target)}? Tiến độ của tab này sẽ bị xoá.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeTab(target.id)
                    closeTarget = null
                }) { Text("Đóng") }
            },
            dismissButton = { TextButton(onClick = { closeTarget = null }) { Text("Huỷ") } }
        )
    }
}

private fun sheetSuffix(tab: TabState) = if (tab.sheetName.isEmpty()) "" else " · ${tab.sheetName}"

@Composable
private fun BrightnessRow(
    level: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp)
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onMinus,
            enabled = level > BrightnessLevels.LEVELS.first(),
            modifier = Modifier.size(48.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("−", fontSize = 22.sp) }
        Text(
            "Độ sáng $level%",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(130.dp)
        )
        OutlinedButton(
            onClick = onPlus,
            enabled = level < BrightnessLevels.LEVELS.last(),
            modifier = Modifier.size(48.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("+", fontSize = 22.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabBar(vm: AppViewModel, onAdd: () -> Unit, onClose: (TabState) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.selectedId, vm.tabs.size) {
        val i = vm.tabs.indexOfFirst { it.id == vm.selectedId }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.tabs.size >= 2) {
            Box(
                Modifier.size(44.dp).clickable { vm.swapWithPrevious() },
                contentAlignment = Alignment.Center
            ) { Text("⇄", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary) }
        }
        LazyRow(
            Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(vm.tabs, key = { it.id }) { tab ->
                val selected = tab.id == vm.selectedId
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .height(44.dp)
                        .combinedClickable(
                            onClick = { vm.selectTab(tab.id) },
                            onLongClick = { onClose(tab) }
                        )
                ) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(88.dp)) {
                            Text(
                                (if (tab.done) "✓ " else "") +
                                    (if (tab.status == TabStatus.Error) "⚠ " else "") +
                                    shortenName(tab.name),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                            Text(shortenName(tab.sheetName.ifEmpty { "—" }, 14), fontSize = 11.sp, maxLines = 1)
                        }
                        Box(
                            Modifier.size(40.dp).clickable { onClose(tab) },
                            contentAlignment = Alignment.Center
                        ) { Text("✕", fontSize = 14.sp) }
                    }
                }
            }
        }
        Box(
            Modifier.size(44.dp).clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) { Text("+", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun ErrorContent(tab: TabState, modifier: Modifier, onRepick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Không mở được file", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(tab.name + sheetSuffix(tab), fontSize = 14.sp, textAlign = TextAlign.Center)
        tab.error?.let { Text(it, fontSize = 12.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
        Text("Vị trí đã lưu: mã thứ ${tab.index + 1} (được giữ nguyên)", fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRepick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Chọn lại file", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ReadyContent(vm: AppViewModel, tab: TabState, landscape: Boolean, modifier: Modifier) {
    val items = tab.items
    val index = tab.index.coerceIn(0, items.lastIndex)
    val item = items[index]
    // Mã QR được tạo hoàn toàn trong app (QrEncoder), không gọi dịch vụ ngoài.
    val qr = remember(item.code) {
        try {
            QrEncoder.encode(item.code, QrEncoder.Ecc.M)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val isLast = index >= items.lastIndex
    val showName = tab.showName && item.name.isNotEmpty()
    val showQty = tab.showQty && item.qty.isNotEmpty()
    val showImei = tab.showImei && item.imei.isNotEmpty()

    Column(modifier.fillMaxWidth()) {
        if (tab.changed) {
            Surface(color = WarningYellow, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "File đã thay đổi từ lần trước, hãy kiểm tra lại vị trí",
                        color = WarningText,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.dismissChangedWarning(tab) }) { Text("Đã hiểu") }
                }
            }
        }

        if (landscape) {
            // Màn ngang: QR bên trái chiếm hết chiều cao, thông tin + nút Trước/Tiếp bên phải.
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight, 520.dp)
                    QrOrError(qr, Modifier.size(side))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InfoTexts(item, showName, showQty, showImei)
                    }
                    NavButtons(vm, tab, index, isLast)
                }
            }
        } else {
            // Cỡ QR co theo chiều cao còn lại để QR không bị đẩy ra ngoài màn hình trên máy nhỏ.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                var reserve: Dp = 8.dp + 46.dp // khoảng cách + dòng mã
                if (showName) reserve += 32.dp
                if (showQty) reserve += 36.dp
                if (showImei) reserve += 30.dp
                val qrSide = minOf(maxWidth, 520.dp, maxOf(maxHeight - reserve, 200.dp))
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QrOrError(qr, Modifier.size(qrSide))
                    InfoTexts(item, showName, showQty, showImei)
                }
            }
            NavButtons(vm, tab, index, isLast)
        }
    }
}

@Composable
private fun QrOrError(qr: QrEncoder.Qr?, modifier: Modifier) {
    if (qr != null) {
        QrView(qr, modifier)
    } else {
        Text(
            "Mã quá dài, không tạo được QR",
            color = MaterialTheme.colorScheme.error,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun InfoTexts(item: Item, showName: Boolean, showQty: Boolean, showImei: Boolean) {
    Text(item.code, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    if (showName) Text(item.name, fontSize = 20.sp, textAlign = TextAlign.Center)
    if (showQty) Text("SL: ${item.qty}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (showImei) Text("IMEI: ${item.imei}", fontSize = 20.sp)
}

@Composable
private fun NavButtons(vm: AppViewModel, tab: TabState, index: Int, isLast: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        OutlinedButton(
            onClick = { vm.move(tab, index - 1) },
            enabled = index > 0,
            modifier = Modifier.weight(1f).height(56.dp)
        ) { Text("◀ Trước", fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Button(
            // Ở mã cuối chỉ đánh dấu hoàn thành, không đóng tab.
            onClick = { if (isLast) vm.markDone(tab) else vm.move(tab, index + 1) },
            modifier = Modifier.weight(2f).height(56.dp)
        ) { Text(if (isLast) "Xong ✓" else "Tiếp ▶", fontSize = 20.sp) }
    }
}

@Composable
private fun HistoryDialog(tab: TabState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }
    val marks = tab.history.asReversed()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lịch sử vị trí") },
        text = {
            if (marks.isEmpty()) {
                Text("Chưa có mốc nào")
            } else {
                LazyColumn {
                    items(marks) { m ->
                        val code = tab.items.getOrNull(m.index)?.code.orEmpty()
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(m.index) }.padding(vertical = 10.dp)
                        ) {
                            Text("Mã thứ ${m.index + 1}" + if (code.isEmpty()) "" else " · $code", fontWeight = FontWeight.Bold)
                            Text(fmt.format(Date(m.time)), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
