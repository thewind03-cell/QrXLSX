/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cầu nối phím cứng (âm lượng) từ Activity xuống màn hình hiển thị QR. */
object KeyBus {
    var handler: ((Int) -> Boolean)? = null
}

/** Như OpenDocument nhưng xin quyền đọc có thể giữ lâu dài (để khôi phục phiên không phải chọn lại file). */
class PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App(vm)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.saveNow()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackgrounded()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && KeyBus.handler?.invoke(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun App(vm: AppViewModel) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(PersistableOpenDocument()) { uri: Uri? ->
        if (uri != null) vm.onFilePicked(uri)
    }
    val repicker = rememberLauncherForActivityResult(PersistableOpenDocument()) { uri: Uri? ->
        if (uri != null && vm.repickTabId >= 0) vm.repickTab(vm.repickTabId, uri)
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            if (vm.setupVisible) {
                SetupScreen(
                    draft = vm.draft,
                    busy = vm.setupBusy,
                    error = vm.setupError,
                    canGoBack = vm.tabs.isNotEmpty(),
                    onBack = { vm.closeSetup() },
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onStart = { vm.startTab(it) },
                    onClearData = {
                        vm.clearSavedData {
                            Toast.makeText(context, "Đã xoá dữ liệu đã lưu", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                DisplayScreen(vm) { tabId ->
                    vm.repickTabId = tabId
                    repicker.launch(arrayOf("*/*"))
                }
            }
        }
    }

    vm.pendingRestore?.let { saved ->
        val whenText = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(saved.savedAt))
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Tiếp tục phiên trước?") },
            text = { Text("(${saved.tabs.size} tab, lưu lúc $whenText)") },
            confirmButton = { TextButton(onClick = { vm.continueRestore() }) { Text("Tiếp tục") } },
            dismissButton = { TextButton(onClick = { vm.discardRestore() }) { Text("Bỏ qua và bắt đầu mới") } }
        )
    }
}
