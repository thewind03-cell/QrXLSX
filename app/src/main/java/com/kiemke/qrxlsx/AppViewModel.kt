/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Kết quả chọn file ở màn Setup (workbook chỉ nằm trong RAM tới khi bấm Bắt đầu / Quay lại). */
class SetupDraft(val uri: Uri, val meta: FileMeta, val workbook: XlsxWorkbook)

/** Lựa chọn của người dùng ở màn Setup. */
class SetupChoice(
    val sheetIndex: Int,
    val sheetName: String,
    val hasHeader: Boolean,
    val qrCol: Int,
    val nameCol: Int,
    val qtyCol: Int,
    val imeiCol: Int
)

/**
 * Giữ toàn bộ trạng thái ứng dụng (tab, độ sáng, màn Setup) nên không mất khi xoay màn hình,
 * và chịu trách nhiệm lưu/khôi phục "Time Machine".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context get() = getApplication<Application>()
    private val store = TimeMachineStore(File(appContext.filesDir, "timemachine"))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Mọi thao tác đĩa của time machine chạy tuần tự trên một luồng nền (thứ tự được bảo toàn).
    // Scope này KHÔNG bị huỷ cùng ViewModel để lần ghi cuối khi thoát luôn được hoàn tất.
    private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + storeDispatcher)

    val tabs = mutableStateListOf<TabState>()
    var selectedId by mutableLongStateOf(-1L)
        private set
    var brightness by mutableIntStateOf(BrightnessLevels.DEFAULT)
        private set

    var showSetup by mutableStateOf(false)
        private set
    var draft by mutableStateOf<SetupDraft?>(null)
        private set
    var setupBusy by mutableStateOf(false)
        private set
    var setupError by mutableStateOf<String?>(null)
        private set

    var pendingRestore by mutableStateOf<SavedState?>(null)
        private set
    var restoreTotal by mutableIntStateOf(0)
        private set
    var restoreDone by mutableIntStateOf(0)
        private set

    /** Tab đang chờ người dùng chọn lại file. */
    var repickTabId: Long = -1L

    private var previousId = -1L
    private var nextId = 1L
    private var persistEnabled = false
    private var saveJob: Job? = null

    val currentTab: TabState? get() = tabs.firstOrNull { it.id == selectedId }
    val setupVisible: Boolean get() = tabs.isEmpty() || showSetup

    init {
        scope.launch {
            val saved = withContext(storeDispatcher) { store.read() }
            if (saved != null && saved.tabs.isNotEmpty() && tabs.isEmpty()) {
                pendingRestore = saved
            } else {
                persistEnabled = true
            }
        }
    }

    // ---------------------------------------------------------------- Tab

    fun selectTab(id: Long) {
        if (id == selectedId || tabs.none { it.id == id }) return
        currentTab?.let { addMark(it) }
        previousId = selectedId
        selectedId = id
        saveNow()
    }

    /** Nút ⇄: nhảy giữa tab hiện tại và tab ngay trước đó. */
    fun swapWithPrevious() {
        val target = tabs.firstOrNull { it.id == previousId && it.id != selectedId }
            ?: tabs.firstOrNull { it.id != selectedId }
            ?: return
        selectTab(target.id)
    }

    fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val removed = tabs.removeAt(idx)
        if (previousId == id) previousId = -1L
        if (selectedId == id) {
            selectedId = tabs.firstOrNull { it.id == previousId }?.id
                ?: tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.id
                ?: -1L
        }
        if (tabs.none { it.uri == removed.uri }) releasePermission(removed.uri)
        saveNow()
    }

    // ------------------------------------------------------------ Vị trí

    fun move(tab: TabState, newIndex: Int) {
        if (tab.items.isEmpty()) return
        val target = newIndex.coerceIn(0, tab.items.lastIndex)
        if (target == tab.index) return
        tab.index = target
        scheduleSave()
    }

    /** Người dùng nhảy số / chọn mốc lịch sử: ghi lại vị trí cũ để có thể quay về. */
    fun jump(tab: TabState, newIndex: Int) {
        if (tab.items.isEmpty()) return
        val target = newIndex.coerceIn(0, tab.items.lastIndex)
        if (target == tab.index) return
        addMark(tab)
        tab.index = target
        saveNow()
    }

    fun markDone(tab: TabState) {
        if (tab.done) return
        tab.done = true
        saveNow()
    }

    private fun addMark(tab: TabState) {
        val list = tab.history
        val now = System.currentTimeMillis()
        val last = list.lastOrNull()
        tab.history = if (last != null && last.index == tab.index) {
            list.dropLast(1) + HistoryMark(tab.index, now)
        } else {
            (list + HistoryMark(tab.index, now)).takeLast(MAX_HISTORY)
        }
    }

    fun onVolumeKey(code: Int): Boolean {
        val tab = currentTab ?: return false
        if (tab.status != TabStatus.Ready) return false
        return when (code) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                move(tab, tab.index + 1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                move(tab, tab.index - 1)
                true
            }
            else -> false
        }
    }

    fun dismissChangedWarning(tab: TabState) {
        tab.changed = false
        tab.currentMeta?.let {
            tab.size = it.size
            tab.lastModified = it.lastModified
        }
        saveNow()
    }

    // ------------------------------------------------------------ Độ sáng

    fun changeBrightness(direction: Int) {
        val levels = BrightnessLevels.LEVELS
        val i = levels.indexOf(brightness).let { if (it < 0) levels.indexOf(BrightnessLevels.DEFAULT) else it }
        val next = levels[(i + direction).coerceIn(0, levels.lastIndex)]
        if (next != brightness) {
            brightness = next
            saveNow()
        }
    }

    // -------------------------------------------------------------- Setup

    /** Mở màn Setup từ nút "+". Trả về false nếu đã đủ [MAX_TABS]. */
    fun requestAddTab(): Boolean {
        if (tabs.size >= MAX_TABS) return false
        showSetup = true
        return true
    }

    fun closeSetup() {
        showSetup = false
        draft = null
        setupError = null
    }

    fun onFilePicked(uri: Uri) {
        setupBusy = true
        setupError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    takePermission(uri)
                    val meta = FileMetaReader.read(appContext, uri) ?: FileMeta("file.xlsx", -1L, 0L)
                    SetupDraft(uri, meta, XlsxReader.read(appContext, uri))
                }
                draft = result
            } catch (e: OutOfMemoryError) {
                setupError = "Không đủ bộ nhớ để đọc file"
            } catch (e: Exception) {
                setupError = "Không đọc được file: ${e.message ?: e.javaClass.simpleName}"
            }
            setupBusy = false
        }
    }

    fun startTab(choice: SetupChoice) {
        val d = draft ?: return
        if (tabs.size >= MAX_TABS) return
        setupBusy = true
        scope.launch {
            val items = withContext(Dispatchers.Default) {
                buildItems(
                    d.workbook.sheets[choice.sheetIndex], choice.hasHeader,
                    choice.qrCol, choice.nameCol, choice.qtyCol, choice.imeiCol
                )
            }
            val tab = TabState(
                nextId++,
                SavedTab(
                    uri = d.uri.toString(),
                    name = d.meta.name,
                    size = d.meta.size,
                    lastModified = d.meta.lastModified,
                    sheetIndex = choice.sheetIndex,
                    sheetName = choice.sheetName,
                    hasHeader = choice.hasHeader,
                    qrCol = choice.qrCol,
                    nameCol = choice.nameCol,
                    qtyCol = choice.qtyCol,
                    imeiCol = choice.imeiCol,
                    position = 0
                )
            )
            tab.items = items
            tab.status = TabStatus.Ready
            currentTab?.let { addMark(it) }
            previousId = selectedId
            tabs.add(tab)
            selectedId = tab.id
            draft = null
            showSetup = false
            setupBusy = false
            persistEnabled = true
            saveNow()
        }
    }

    /** "Xoá dữ liệu đã lưu": xoá thư mục timemachine và bỏ mọi quyền URI đã giữ. */
    fun clearSavedData(onDone: () -> Unit) {
        saveJob?.cancel()
        // Các tab đang mở vẫn dùng được nhưng không được lưu nữa cho tới khi thêm tab mới.
        persistEnabled = false
        scope.launch {
            withContext(storeDispatcher) {
                store.clear()
                releaseAllPermissions()
            }
            onDone()
        }
    }

    // ----------------------------------------------------- Khôi phục phiên

    fun continueRestore() {
        val saved = pendingRestore ?: return
        pendingRestore = null
        val restored = saved.tabs.take(MAX_TABS).map { TabState(nextId++, it) }
        tabs.clear()
        tabs.addAll(restored)
        selectedId = restored.getOrNull(saved.selectedTab)?.id ?: restored.first().id
        previousId = -1L
        brightness = BrightnessLevels.normalize(saved.brightness)
        showSetup = false
        persistEnabled = true

        // Tab đang chọn được nạp trước; nạp tuần tự để không tăng đột biến RAM.
        val order = restored.sortedBy { if (it.id == selectedId) 0 else 1 }
        restoreTotal = order.size
        restoreDone = 0
        scope.launch {
            for (tab in order) {
                loadTab(tab)
                restoreDone++
            }
            restoreTotal = 0
        }
    }

    fun discardRestore() {
        pendingRestore = null
        persistEnabled = true
        ioScope.launch {
            store.clear()
            releaseAllPermissions()
        }
    }

    private suspend fun loadTab(tab: TabState) {
        tab.status = TabStatus.Loading
        tab.error = null
        when (val r = withContext(Dispatchers.IO) { TabLoader.load(appContext, tab) }) {
            is TabLoader.Result.Ok -> {
                tab.items = r.items
                if (tab.index > r.items.lastIndex) tab.index = r.items.lastIndex
                val m = r.meta
                tab.currentMeta = m
                tab.changed = m != null && (
                    (tab.size >= 0 && m.size >= 0 && tab.size != m.size) ||
                        (tab.lastModified > 0 && m.lastModified > 0 && tab.lastModified != m.lastModified)
                    )
                tab.status = TabStatus.Ready
            }
            is TabLoader.Result.Fail -> {
                tab.items = emptyList()
                tab.error = r.message
                tab.status = TabStatus.Error
            }
        }
        scheduleSave()
    }

    /** Người dùng chọn lại file cho tab bị lỗi; vẫn giữ sheet, cột và vị trí cũ. */
    fun repickTab(id: Long, uri: Uri) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        scope.launch {
            val meta = withContext(Dispatchers.IO) {
                takePermission(uri)
                FileMetaReader.read(appContext, uri)
            }
            val old = tab.uri
            tab.uri = uri.toString()
            if (meta != null) tab.name = meta.name
            if (tabs.none { it.uri == old }) releasePermission(old)
            loadTab(tab)
            saveNow()
        }
    }

    // ---------------------------------------------------------- Lưu trạng thái

    private fun snapshot() = SavedState(
        savedAt = System.currentTimeMillis(),
        selectedTab = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
        brightness = brightness,
        tabs = tabs.map { it.toSaved() }
    )

    /** Lưu có debounce ~500 ms (dùng khi đổi vị trí liên tục). */
    fun scheduleSave() {
        if (!persistEnabled) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            saveNow()
        }
    }

    /** Lưu ngay (chuyển tab, đổi độ sáng, onPause/onStop). Chụp snapshot ở main, ghi file ở luồng nền. */
    fun saveNow() {
        saveJob?.cancel()
        if (!persistEnabled) return
        val json = if (tabs.isEmpty()) null else StateSerializer.toJson(snapshot())
        ioScope.launch {
            if (json == null) store.deleteState() else store.write(json)
        }
    }

    /** Gọi từ onStop: thêm mốc "xuống nền" cho tab đang chọn rồi lưu ngay. */
    fun onAppBackgrounded() {
        currentTab?.let { addMark(it) }
        saveNow()
    }

    // --------------------------------------------------------- Quyền URI

    private fun takePermission(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Provider không cho giữ quyền: vẫn đọc được trong phiên này.
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun releasePermission(uriString: String) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun releaseAllPermissions() {
        val resolver = appContext.contentResolver
        for (p in resolver.persistedUriPermissions) {
            var flags = 0
            if (p.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (p.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                resolver.releasePersistableUriPermission(p.uri, flags)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        scope.coroutineContext[Job]?.cancel()
        super.onCleared()
    }
}
