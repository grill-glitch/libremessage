package org.librelab.messaging.data

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val messages: List<SmsMessage> = emptyList(),
    val contacts: List<ContactInfo> = emptyList(),
    val filter: SmsFilter = SmsFilter.ALL,
    val searchQuery: String = "",
    val tab: Int = 0, // 0 = 短信, 1 = 联系人
    val hasSmsPermission: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
    val searchActive: Boolean = false,
    val showSettings: Boolean = false,
    val selectedThreadId: Long? = null,
    val threadMessages: List<SmsMessage> = emptyList(),
    val loading: Boolean = true,
    val showAdsInAll: Boolean = false,
    val notifyAds: Boolean = false,
    val autoCopyCode: Boolean = true,
    /** Anti-spam marks by address (standard flavor only; empty elsewhere). */
    val marks: Map<String, org.librelab.messaging.antispam.NumberMark> = emptyMap()
) {
    /** Latest code/express message pinned to the smart banner. */
    val latestCode: SmsMessage?
        get() = allCodeEntries.maxByOrNull { it.date }

    /** Latest express pickup message (used under the 包裹 filter). */
    val latestPickup: SmsMessage?
        get() = allPickups.maxByOrNull { it.date }

    /** Express pickup entries (one per cabinet number), for the 包裹 banner. */
    val allPickups: List<SmsMessage>
        get() = pickupMessages
            .flatMap { msg ->
                SmsParser.extractAllCodes(msg.body).map { code -> msg.copy(code = code) }
            }
            .sortedByDescending { it.date }

    /** All code + express messages for the expanded list. Express messages
     * are split per cabinet number: 【多多代收点】…取货码1-3-9448、5-4-3216
     * yields two entries (one per parcel). */
    val allCodeEntries: List<SmsMessage>
        get() = messages.filter { it.category == MessageCategory.CODE || it.category == MessageCategory.PACKAGE }
            .flatMap { msg ->
                SmsParser.extractAllCodes(msg.body).map { code -> msg.copy(code = code) }
            }
            .sortedByDescending { it.date }

    /** Pure verification codes (no pickup codes) — the 验证码 filter list. */
    val codeEntries: List<SmsMessage>
        get() = messages.filter { it.category == MessageCategory.CODE }
            .flatMap { msg ->
                SmsParser.extractAllCodes(msg.body).map { code -> msg.copy(code = code) }
            }
            .sortedByDescending { it.date }

    private val codeMessages: List<SmsMessage>
        get() = messages.filter { it.category == MessageCategory.CODE }

    private val pickupMessages: List<SmsMessage>
        get() = messages.filter { it.category == MessageCategory.PACKAGE }

    /** Messages after applying filter chip + search query. */
    val visibleMessages: List<SmsMessage>
        get() {
            val q = searchQuery.trim()
            // 设置里关闭"全部标签显示广告"时,把广告从全部列表剔除
            // (广告标签仍可单独查看)。
            val hideAdsInAll = filter == SmsFilter.ALL && !showAdsInAll
            return messages.filter { m ->
                (!hideAdsInAll || m.category != MessageCategory.AD) &&
                    filter.matches(m.category, m.body) &&
                    (q.isEmpty() || m.body.contains(q, ignoreCase = true) || m.address.contains(q, ignoreCase = true))
            }
        }

    /**
     * Conversation rows: filter at message level, then group by thread
     * (fallback: address) so each sender occupies exactly one list row.
     */
    val visibleThreads: List<SmsThreadItem>
        get() = visibleMessages
            .groupBy { if (it.threadId != 0L) it.threadId else it.address.hashCode().toLong() }
            .values
            .map { group ->
                val latest = group.maxByOrNull { it.date }!!
                SmsThreadItem(latest, group.count { !it.isRead }, group.size)
            }
            .sortedByDescending { it.message.date }
}

class SmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        // Watch SMS, MMS and the archived view; refresh on any change.
        val resolver = application.contentResolver
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        resolver.registerContentObserver(android.net.Uri.parse("content://mms"), true, observer)
        resolver.registerContentObserver(android.net.Uri.parse("content://sms/archived"), true, observer)
        refresh()
    }

    fun refresh() {
        val app = getApplication<Application>()
        val hasSms = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val isDefault = runCatching {
            Telephony.Sms.getDefaultSmsPackage(app) == app.packageName
        }.getOrDefault(false)
        _state.update {
            it.copy(
                hasSmsPermission = hasSms,
                isDefaultSmsApp = isDefault,
                showAdsInAll = repository.showAdsInAll(),
                notifyAds = repository.notifyAds(),
                autoCopyCode = repository.autoCopyCode()
            )
        }
        if (!hasSms) {
            _state.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            val messages = repository.loadAll()
            _state.update {
                it.copy(messages = messages, loading = false)
            }
            _state.value.selectedThreadId?.let { loadThread(it) }
        }
    }

    fun openThread(threadId: Long) {
        _state.update { it.copy(selectedThreadId = threadId) }
        loadThread(threadId)
        // Opening a conversation marks its messages read; the ContentObserver
        // fires refresh() which recomputes the unread badges on the home list.
        viewModelScope.launch(Dispatchers.IO) { repository.markThreadRead(threadId) }
    }

    fun closeThread() = _state.update { it.copy(selectedThreadId = null, threadMessages = emptyList()) }

    /** Archive (or restore) a conversation; the system archived view updates. */
    fun archiveThread(threadId: Long, archive: Boolean) {
        viewModelScope.launch {
            repository.archiveThread(threadId, archive)
            refresh()
        }
    }

    /** Batch archive (or restore) several conversations. */
    fun archiveThreads(ids: List<Long>, archive: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.archiveThread(it, archive) }
            refresh()
        }
    }

    /** Batch delete several conversations (permanent). */
    fun deleteThreads(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.deleteThread(it) }
            refresh()
        }
    }

    fun loadThread(threadId: Long) {
        viewModelScope.launch {
            val thread = repository.queryThread(threadId)
            _state.update { it.copy(threadMessages = thread) }
        }
    }

    /** Insert our own outbox row so the sent message shows in the thread. */
    fun insertPendingSms(address: String, body: String): Long =
        repository.insertPendingSms(address, body)

    fun markSmsFailed(id: Long) = repository.markSmsFailed(id)

    fun loadContacts() {
        viewModelScope.launch {
            val contacts = repository.loadContacts()
            _state.update { it.copy(contacts = contacts) }
        }
    }

    fun setFilter(filter: SmsFilter) = _state.update { it.copy(filter = filter) }

    fun setSearchQuery(q: String) = _state.update { it.copy(searchQuery = q) }

    fun setSearchActive(active: Boolean) = _state.update { it.copy(searchActive = active) }

    fun setShowSettings(show: Boolean) = _state.update { it.copy(showSettings = show) }

    /** 全部标签是否显示广告短信(设置项,持久化)。 */
    fun setShowAdsInAll(show: Boolean) {
        repository.setShowAdsInAll(show)
        _state.update { it.copy(showAdsInAll = show) }
    }

    /** 广告短信是否弹通知(设置项;默认静音)。 */
    fun setNotifyAds(notify: Boolean) {
        repository.setNotifyAds(notify)
        _state.update { it.copy(notifyAds = notify) }
    }

    /** 验证码短信自动复制到剪贴板(设置项)。 */
    fun setAutoCopyCode(auto: Boolean) {
        repository.setAutoCopyCode(auto)
        _state.update { it.copy(autoCopyCode = auto) }
    }

    fun setTab(tab: Int) {
        _state.update { it.copy(tab = tab) }
        if (tab == 1) loadContacts()
    }

    /** Look up anti-spam marks for an address (standard flavor only). */
    fun lookupMark(address: String) {
        if (address.isBlank() || _state.value.marks.containsKey(address)) return
        if (!org.librelab.messaging.antispam.AntiSpam.service.isAvailable()) return
        viewModelScope.launch {
            val mark = org.librelab.messaging.antispam.AntiSpam.service.lookup(address) ?: return@launch
            _state.update { it.copy(marks = it.marks + (address to mark)) }
        }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}
