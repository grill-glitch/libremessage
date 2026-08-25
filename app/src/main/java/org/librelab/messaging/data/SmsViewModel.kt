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
    val antiBomb: Boolean = false,
    val antiBombUntil: Long = 0L,
    val defaultSubId: Int = 0,       // 0 = auto / system default SIM
    val simCards: List<SimCard> = emptyList()
) {
    /** Latest code/express message pinned to the smart banner. */
    val latestCode: SmsMessage?
        get() = allCodeEntries.maxByOrNull { it.date }

    /** All code + express messages for the expanded list. Express messages
     * are split per cabinet number: 【多多代收点】…取货码1-3-9448、5-4-3216
     * yields two entries (one per parcel). */
    val allCodeEntries: List<SmsMessage>
        get() = codeEntries(messages, setOf(MessageCategory.CODE, MessageCategory.PACKAGE))

    /** Messages after applying filter chip + search query. */
    val visibleMessages: List<SmsMessage>
        get() = messagesFor(filter)

    /**
     * Messages for a specific filter, independent of [filter] — used by the
     * pager pages so the content switches the instant the page settles,
     * without waiting for the chip state to propagate.
     */
    fun messagesFor(f: SmsFilter): List<SmsMessage> {
        val q = searchQuery.trim()
        // 设置里关闭"全部标签显示广告"时,把广告从全部列表剔除
        // (广告标签仍可单独查看)。
        val hideAdsInAll = f == SmsFilter.ALL && !showAdsInAll
        // 防验证码轰炸:验证码从全部列表隐藏,但首页 banner 保留
        // (banner 数据源 allCodeEntries 不走这里);临时接收窗口内恢复。
        val antiBombActive = isAntiBombActive(antiBomb, antiBombUntil)
        return messages.filter { m ->
            (!hideAdsInAll || m.category != MessageCategory.AD) &&
                (!(antiBombActive && f == SmsFilter.ALL) || m.category != MessageCategory.CODE) &&
                f.matches(m.category, m.body) &&
                (q.isEmpty() || m.body.contains(q, ignoreCase = true) || m.address.contains(q, ignoreCase = true))
        }
    }

    /**
     * Conversation rows for one filter, grouped by thread — independent of
     * [filter] so pager pages render instantly.
     */
    fun threadsFor(f: SmsFilter): List<SmsThreadItem> =
        groupThreads(messagesFor(f))

    /** Pure verification codes (no pickup codes) — the 验证码 filter list. */
    fun codesFor(): List<SmsMessage> =
        codeEntries(messages, setOf(MessageCategory.CODE))

    /** Express pickup entries for the 包裹 filter. */
    fun pickupsFor(): List<SmsMessage> =
        codeEntries(messages, setOf(MessageCategory.PACKAGE))

    /**
     * Conversation rows: filter at message level, then group by thread
     * (fallback: address) so each sender occupies exactly one list row.
     */
    val visibleThreads: List<SmsThreadItem>
        get() = groupThreads(visibleMessages)
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
        loadSimCards()
        _state.update {
            it.copy(
                hasSmsPermission = hasSms,
                isDefaultSmsApp = isDefault,
                showAdsInAll = repository.showAdsInAll(),
                notifyAds = repository.notifyAds(),
                autoCopyCode = repository.autoCopyCode(),
                antiBomb = repository.antiBomb(),
                antiBombUntil = repository.antiBombUntil(),
                defaultSubId = repository.defaultSubId()
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

    /** 防验证码轰炸:静音验证码 + 全部标签列表隐藏(首页 banner 保留)。 */
    fun setAntiBomb(on: Boolean) {
        repository.setAntiBomb(on)
        _state.update { it.copy(antiBomb = on) }
    }

    /** 临时接收验证码 1 分钟(倒计时窗口)。 */
    fun temporarilyUnmuteCodes() {
        val until = System.currentTimeMillis() + 60_000L
        repository.setAntiBombUntil(until)
        _state.update { it.copy(antiBombUntil = until) }
    }

    /** 临时窗口再 +1 分钟。 */
    fun extendUnmuteCodes() {
        val until = maxOf(_state.value.antiBombUntil, System.currentTimeMillis()) + 60_000L
        repository.setAntiBombUntil(until)
        _state.update { it.copy(antiBombUntil = until) }
    }

    /** 立即恢复静音(结束临时窗口)。 */
    fun restoreCodeMute() {
        repository.setAntiBombUntil(0L)
        _state.update { it.copy(antiBombUntil = 0L) }
    }

    /** Enumerate active SIM cards (safe: empty list when unavailable). */
    fun loadSimCards() {
        val app = getApplication<Application>()
        val cards = runCatching {
            val sm = android.telephony.SubscriptionManager.from(app)
            sm.activeSubscriptionInfoList.orEmpty()
                .map { SimCard(it.subscriptionId, it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}") }
        }.getOrDefault(emptyList())
        _state.update { it.copy(simCards = cards) }
    }

    /** 默认电话卡(设置项,0 = 无/系统自动)。 */
    fun setDefaultSubId(subId: Int) {
        repository.setDefaultSubId(subId)
        _state.update { it.copy(defaultSubId = subId) }
    }

    fun setTab(tab: Int) {
        _state.update { it.copy(tab = tab) }
        if (tab == 1) loadContacts()
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}
