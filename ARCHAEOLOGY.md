# LibreLab Message 代码考古报告 (ARCHAEOLOGY)

> 分析日期:2026-08-25 · 分析基线:git HEAD `2b77780` (v1.3.0)
> 方式:全源码通读(26 个 Kotlin 文件 + mms/pdu 移植 Java),关键声明均带 `文件:行号` 证据。
> 原则:Understand first. Measure duplication. Extract carefully. Preserve behavior. Refactor incrementally.
> 本报告只读,未修改任何代码。

---

## 1. 项目架构地图

### 1.1 总体规模

| 层 | 文件 | 行数 | 说明 |
|---|---|---|---|
| UI (Compose) | 8 | ~4,100 | MainScreen(1,233) + ThreadDetailScreen(1,601) 占 2/3 |
| 数据层 | 4 | ~1,320 | SmsRepository(548) + SmsViewModel(337) + SmsParser(304) + SmsModels(130) |
| 系统回调 | 7 | ~450 | 2×Receiver + SentReceiver + CopyCode + HeadlessService + Notifications + MainActivity |
| Widget | 3 | ~690 | ListWidgetProvider(284) + CodeWidgetProvider(203) + WidgetService |
| 工具/主题 | 4 | ~230 | MmsSender(200) + OutboxStore + TimeUtil + Theme |
| MMS PDU (AOSP 移植 Java) | 13 | ~3,700 | `mms/pdu/` — 第三方移植代码,勿动 |
| 测试 | 1 | 137 | SmsParserTest(纯 JVM) |

**单包结构**:除 `ui/`、`data/`、`util/` 外,`SmsReceiver/MmsReceiver/SmsSentReceiver/CopyCodeReceiver/Notifications/Widgets` 全部平铺在 `org.librelab.messaging` 根包 — 系统层无领域分包。

### 1.2 启动流程

```
Launcher / smsto: / share / shortcut / widget
  → MainActivity (singleTask)
      parseIntent(): LaunchParams(number, body, attachmentUri, shortcutTarget, threadId)
        ├─ 0 普通启动 → HomeRoute(会话列表)
        ├─ 1 NEW_MESSAGE / share → ThreadRoute(threadId=0, 新短信模式)
        ├─ 2 OPEN_CODES → vm.setFilter(CODE)
        ├─ 3 OPEN_PICKUPS → vm.setFilter(PACKAGE)
        └─ 4 OPEN_THREAD(widget 行点击) → ThreadRoute(threadId)
  → MainScreen: NavHost { HomeRoute, ThreadRoute, SettingsRoute }
```

`MainActivity.kt:37` `launchParams` 是 Compose state,`onNewIntent` 更新它触发重组 — 这是处理热启动深链的关键设计。

### 1.3 分层职责(实际,非理想)

```text
UI 层
├─ MainScreen.kt: HomeScreen(列表+搜索+多选+防轰炸卡+权限+设置导航) / SmsListContent(pager) /
│   4×FilterPage / SettingsScreen / SetupCard / AntiBombCard / defaultSmsRequestIntent
├─ ThreadDetailScreen.kt: 聊天页 — 发送/接收气泡、输入栏、附件、多选、图片查看器、联系人选择、
│   删除、分享、保存图片、拉黑、加联系人、SIM 选择  (1601 行,几乎所有功能)
├─ MessageList.kt: MessageItem + Avatar
├─ SmartCodeCard.kt: 验证码卡 + CodeCardRow + 展开 sheet
├─ FilterChips.kt: 滑动指示器 chips
└─ ContactsScreen.kt: 联系人 tab
    ↓ collectAsStateWithLifecycle
状态层
├─ SmsViewModel (AndroidViewModel): ContentObserver 注册/反注册、refresh 编排、权限/默认app探测、
│   SIM 枚举、全部设置写入转发
└─ UiState (data class): 27 字段 + 8 个计算属性(含 5 条重复的 code/pickup 提取管道)
    ↓
业务逻辑层
├─ SmsRepository: 数据访问(SMS/MMS/Outbox 三源合并)、联系人解析、归档状态、
│   设置持久化(SharedPreferences)  ← 职责混装
├─ SmsParser (object): 纯函数分类/提取 — 项目里最干净的抽象
├─ MmsSender (object): PDU 编码 + SmsManager 下发 + 图片压缩
└─ OutboxStore (object): 已发 MMS 私有 JSON 存储
    ↓
数据访问
├─ Telephony provider (content://sms, content://mms, content://mms/part, /addr)
├─ ContactsContract (PhoneLookup + Phone.CONTENT_URI 全量索引)
├─ SharedPreferences ×2 (settings_prefs, archive_prefs)
└─ filesDir JSON (outbox_mms.json) + mms_sent/ 图片副本
    ↓
Android System
├─ SmsManager.sendTextMessage / sendMultimediaMessage
├─ PendingIntent → SmsSentReceiver (发送回调)
├─ 系统广播 SMS_RECEIVED / SMS_DELIVER / WAP_PUSH_*
└─ RoleManager / 默认短信应用资格(HeadlessSmsSendService 桩)
```

### 1.4 各域类清单

| 域 | 主要类 | 位置 |
|---|---|---|
| Activity | MainActivity | 根包 |
| Compose UI | MainScreen/ThreadDetailScreen/MessageList/SmartCodeCard/FilterChips/ContactsScreen | ui/ |
| ViewModel | SmsViewModel + UiState | data/ |
| Repository | SmsRepository | data/ |
| Service | HeadlessSmsSendService(桩), ListWidgetService(RemoteViews) | 根包 |
| BroadcastReceiver | SmsReceiver, MmsReceiver, SmsSentReceiver, CopyCodeReceiver, CodeWidgetProvider, ListWidgetProvider | 根包 |
| Notification | Notifications (object) | 根包 |
| SMS/MMS 数据访问 | SmsRepository.query/queryMms/outboxMessages;Widget 直接查 provider | data/ + 根包 |
| 数据库/Provider | 无自有 DB — 全部系统 provider + SharedPreferences + JSON | — |
| 权限处理 | MainScreen REQUIRED_PERMISSIONS + HomeScreen 首次请求;SmsReceiver 通知前检查 | ui/ |
| 联系人 | SmsRepository.lookupContact / loadContacts / contactNumberIndex;ListWidgetViewsFactory.nameFor | data/ + 根包 |
| Conversation | 无独立模型 — `SmsThreadItem`(最新消息+计数)在 UI 层合成;线程 = threadId 分组 | data/SmsModels |
| 消息发送 | ThreadDetailScreen.onSend(直接 SmsManager) + MmsSender + SmsSentReceiver 回调 | ui/ + util/ |
| 消息接收 | SmsReceiver(去重→入库→分类→通知→剪贴板→widget) | 根包 |
| 草稿 | 无持久草稿 — 内存 `input` state(转后台即丢) | ui/ |
| 附件/MMS | PendingAttachment + MmsSender + OutboxStore + readMmsParts | data/ + util/ |
| 搜索 | UiState.searchQuery 内存过滤(messagesFor) | data/ |
| 设置 | SettingsScreen + SmsRepository 12 个 getter/setter(SharedPreferences) | ui/ + data/ |
| 主题/UI 状态 | Theme/Color/Type + Material You 动态色;UiState 全量状态 | ui/theme + data/ |
| 后台任务 | 无 Worker — ContentObserver + 广播驱动刷新;Widget 30 分钟系统 tick | — |
| 系统回调 | 上述全部 Receiver + MainActivity.onNewIntent | 根包 |
| Widget/Shortcut | CodeWidgetProvider/ListWidgetProvider/shortcuts.xml(3 快捷方式) | 根包 + res/xml |

---

## 2. 依赖关系与高耦合节点

### 2.1 谁调用谁(核心链)

```
MainActivity ──> MainScreen ──> SmsViewModel ──> SmsRepository ──> Telephony provider
     │                │              │                ├──> ContactsContract
     │                │              │                ├──> SharedPreferences (settings + archive)
     │                │              │                └──> OutboxStore
     │                └──> ThreadDetailScreen ──> SmsManager (直接!)
     │                                   ├──> MmsSender ──> mms/pdu (AOSP) + FileProvider
     │                                   ├──> ContentResolver.delete (直接!)
     │                                   ├──> MediaStore / ContactsContract / BlockedNumberContract (直接!)
     │                                   └──> OutboxStore (直接!)
SmsReceiver ──> Notifications / CodeWidgetProvider / ListWidgetProvider / SharedPreferences / Clipboard
Widgets ──> SMS provider 直查 + SmsParser
```

### 2.2 高耦合节点(按依赖者×职责数)

**① ThreadDetailScreen — 1601 行,全能页面**
```text
ThreadDetailScreen
 ├── SmsManager (sendTextMessage, L579)
 ├── Telephony.Threads (getOrCreateThreadId, L545/599)
 ├── ContentResolver (deleteMessage, L779-785)
 ├── MediaStore (saveImageToGallery, L882-898)
 ├── ContactsContract.Intents (addToContacts, L839)
 ├── BlockedNumberContract (blockNumber, L861)
 ├── FileProvider (shareableImageUri, L816)
 ├── MmsSender / OutboxStore / SmsSentReceiver (发送链)
 ├── SmsViewModel (openThread/refresh/insertPendingSms/markSmsFailed/setDefaultSubId)
 └── 本地 UI 状态: input/pendingAttachment/viewerUri/pickedSubId/currentThreadId/
     multiSelect/selectedKeys/moreMenuOpen/inputFocused (+3 个 rememberLauncher)
```
它同时是页面、发送器、删除器、分享器、媒体保存器、通讯录工具 — 且发送逻辑(而非 Repository)是主路径。

**② SmsRepository — 548 行,数据仓库滑向 god class**
```text
SmsRepository
 ├── SMS 查询/写入 (query/insertPendingSms/markSmsSent/markSmsFailed/markThreadRead)
 ├── MMS 查询 (queryMms/readMmsParts/readMmsAddress/smsAddressForThread)
 ├── Outbox 合并 (outboxMessages)
 ├── 联系人解析 (lookupContact/phoneLookup/contactNumberIndex/loadContacts) + normalizeNumber
 ├── 归档状态 (archivedIds/setArchivedIds/archiveThread) ← 私有状态混入
 └── 设置持久化 (12 个 getter/setter ← SharedPreferences)  ← 职责错位
```

**③ SmsViewModel/UiState — 状态与业务管道混装**
- `UiState` 27 字段 + 8 计算属性;其中 5 条 code/pickup 提取管道几乎相同(`SmsViewModel.kt:50-134`)
- `SmsViewModel.refresh()` 每次 ContentObserver 触发都重查全表 + 读 7 个设置 + 枚举 SIM — 无去抖、无缓存。

**④ SmsReceiver — 接收端做了六件事**
去重(L89-105)→ 入库(L74-87)→ 分类(L45)→ 设置判断+防轰炸(L44-54)→ 通知/剪贴板(L56-71)→ widget 刷新(L41-42)。其中设置判断与 Repository 的 getter 是**两套实现**。

**⑤ MainScreen.HomeScreen** — 权限请求、默认短信应用、搜索、多选、防轰炸、FAB、底栏、pager、设置导航全部内联(约 300 行函数)。

---

## 3. 数据流

### 3.1 主列表数据流(loadAll)

```
ContentObserver (sms/mms/archived URI) ──> SmsViewModel.refresh()
  ├─ 权限/默认app/SIM/7 项设置 → UiState
  └─ SmsRepository.loadAll():
       sms 表 (TYPE IN 1,2,4,5,6)        ─┐
       mms 表 (msg_box IN 1,2,4,5)  + parts/addr ─┤→ LinkedHashMap[key] 去重
       OutboxStore JSON (负 id)          ─┘
       → 归档线程 re-classify 为 ARCHIVED
       → date DESC 排序
  → UiState.messages
  → UiState.visibleThreads / threadsFor(f) 分组 → HomeScreen 列表
```

### 3.2 聊天页数据流(queryThread)

```
openThread(threadId) → queryThread:
  sms (THREAD_ID=? AND TYPE IN 1,2,4,5,6) + mms (thread_id=? AND msg_box IN 1,2,4,5) + outbox 过滤
  → 合并按 date ASC → UiState.threadMessages
openThread 同时异步 markThreadRead → observer 再触发 refresh(读取标记的副作用链)
```

### 3.3 发送数据流

```
ThreadDetailScreen.onSend:
  ├─ 无附件: vm.insertPendingSms(address, body) [TYPE=6 OUTBOX, 系统 ROM 不自动写行]
  │    → PendingIntent(SMS_SENT, recordId) → SmsManager.sendTextMessage
  │    → SmsSentReceiver: 成功→TYPE=2, 失败→TYPE=5 → ContentObserver → refresh
  └─ 有附件: MmsSender.send():
       SendReq PDU(PduComposer) → filesDir/mms_outbox/rawmms_*.pdu → FileProvider URI
       → SmsManager.sendMultimediaMessage(uri, sentIntent)
       → 图片压缩存 mms_sent/ + OutboxStore.add(负 id 记录)
       → SmsSentReceiver: OutboxStore.mark(failed)
```

### 3.4 接收数据流

```
SMS_RECEIVED(所有 app) + SMS_DELIVER(仅默认短信应用,是持久化通道)
  → SmsReceiver: 内容去重(内存 64 条环形)
  → SMS_DELIVER: insertInbox(ADDRESS/BODY/DATE/READ/SEEN — 系统自动分配 thread_id)
  → 分类 + 广告静音/防轰炸判断(读 SharedPreferences) → Notifications.notifyIncoming
  → CODE 且未静音且 auto_copy_code: 剪贴板
  → CodeWidgetProvider.requestRefresh + ListWidgetProvider.requestRefresh
```

### 3.5 归档数据流

```
archiveThread(threadId) → archive_prefs["thread_ids"] 增删
  → loadAll 时: 若 msg.threadId ∈ archived 集合 → copy(category = ARCHIVED)
  → SmsFilter.ARCHIVED 按 category 匹配 → 归档列表
注: SmsRepository.kt:27 的 archivedUri ("content://sms/archived") 定义后从未作为查询 URI 使用,
    L302 的 `archived = uri == archivedUri` 分支是死代码(所有调用都传 Telephony.Sms.CONTENT_URI)。
```

---

## 4. Message 生命周期

```
接收: SMS_DELIVER → insertInbox → Observer → loadAll (category 由 classify 计算)
      ↳ MMS 到达: 系统写库 → Observer → loadAll (queryMms 映射, category 只看联系人)
发送(SMS): insertPendingSms(TYPE=6) → [左侧?见 §7 状态机] → SmsSentReceiver → TYPE=2/5
发送(MMS): OutboxStore.add(负 id) → SmsSentReceiver → failed flip
展示: sms/mms/outbox 三源 → SmsMessage(唯一模型, key=s/m+id 防重叠)
操作:
  ├─ 删除单条: UI 直接 resolver.delete(按 id) / OutboxStore.remove(负 id)
  ├─ 删除线程: repository.deleteThread(sms + mms by thread_id) ← 不清理 OutboxStore!
  ├─ 标记已读: markThreadRead(thread_id) — sms + mms
  └─ 归档: 不写系统,只写本地 id 集合
```

**生命周期问题**:
- `deleteThread` 不删 OutboxStore 中该线程的 MMS 记录 → 删除线程后,幽灵会话仍可能出现在列表(loadAll 会带上 outbox 行)。`ThreadDetailScreen.kt:773` 的单条删除有 `id<0 → OutboxStore.remove`,但批量/线程删除没有。
- SMS 与 MMS 的"自己发出的消息"生命周期渲染不一致(见 §7)。
- 无草稿持久化:输入框内容仅存在于 Composable state。

---

## 5. Conversation 生命周期

**项目里没有独立的 Conversation 领域对象。** "会话"由三处分别推导:

| 位置 | 推导方式 | 用途 |
|---|---|---|
| `SmsRepository` | `Telephony.Threads.getOrCreateThreadId(address)` | 发送时确定 thread_id |
| `UiState.threadsFor/visibleThreads` | `groupBy { threadId ?: address.hashCode() }` + 最新消息 + 未读数 | 列表行 |
| `ListWidgetViewsFactory` | 直接 groupBy threadId | widget 行 |

SmsThreadItem = 最新 SmsMessage + unreadCount + totalCount,是纯 UI 聚合,无状态、无方法。

归档是**线程级属性**(thread_id 集合),但通过 `MessageCategory.ARCHIVED`(消息级分类)传播 — 一个概念从线程层泄漏到消息层,导致 classify() 需要 archived 参数、ARCHIVED 出现在 category 枚举里、SmsFilter 需要特殊分支(`ALL -> category != ARCHIVED`)。

---

## 6. SMS/MMS 数据流(细节)

| 维度 | SMS | MMS |
|---|---|---|
| 接收持久化 | app 自己 insert(SMS_DELIVER 通道,系统不写) | 系统写库 |
| 接收通知 | SmsReceiver 分类后按设置静音 | MmsReceiver 固定一条"收到彩信"通知 |
| 发送 | SmsManager.sendTextMessage | 自编码 PDU → sendMultimediaMessage |
| 发送记录 | 系统 sms 表(TYPE=6 占位 → 2/5) | 私有 OutboxStore(JSON + 图片副本) |
| 状态来源 | `type` 列 | `msg_box` 列 / outbox `failed` |
| 已读标记 | sms READ=1 | mms read=1 |
| 删除 | resolver.delete(sms/id) | resolver.delete(mms/id) / OutboxStore.remove |
| address 解析 | ADDRESS 列 | addr 表 FROM(137)/TO(151) + 红acted 回退 |

关键历史决策(注释证据):
- 三源合并是为了"有些 ROM 不写第三方发送记录"(`SmsRepository.kt:212-216`)
- MMS outbox 私有存储是为了"有些 ROM 吃掉 pending pdu 行"(`OutboxStore.kt:8-11`)
- MMS date 秒/毫秒归一化(`SmsRepository.kt:345`)、红acted 号码回退(`SmsRepository.kt:363`)、默认短信应用资格要求(manifest 注释) — 都是 ROM 兼容补丁,重构时不得动语义。

---

## 7. 主要状态机

### 7.1 发送/投递状态机(SendStatus) — 3 个映射,1 处语义分叉

```
             SMS type               MMS msg_box            Outbox failed
SENT       type == 2 (SENT)         box == 2               failed == false
SENDING    type ∈ {4 QUEUED, 6 OUTBOX}   box == 4
FAILED     type == 5                box == 5               failed == true
NONE       其它(含 type=1 收件)       其它(含 box=1)          —
```
- 映射实现:`SmsRepository.kt:295-300`(sms)与 `:350-355`(mms)是相同 when 结构。
- **语义分叉在 isSent**:`SmsRepository.kt:294` `isSent = type == MESSAGE_TYPE_SENT`(只有 type=2 算"已发"),而 `:349` `isSent = box != 1`(MMS 一切非收件算已发),outbox 恒 `isSent=true`(`:202`)。
- **后果**:`insertPendingSms` 写入 TYPE=6(OUTBOX)后,到 SmsSentReceiver 把它翻成 TYPE=2 之前,这条**自己的发送**会以 `isSent=false` 渲染在左侧(接收样式),且 received 分支向 BubbleContent 传 `SendStatus.NONE`(`ThreadDetailScreen.kt:1051`),SENDING 图标不显示;而 MMS 的 outbox 记录直接渲染右侧。同一领域状态两种 UI 行为 — **行为不一致,需真机确认后修复**。

### 7.2 消息分类状态机(MessageCategory)

```
classify(body, hasContact, archived):
  archived → ARCHIVED
  isPickupCode → PACKAGE
  extractCode != null → CODE
  hasTrackingNumber → ECOMMERCE
  AD_KEYWORDS → AD → BANK → ECOMMERCE → SERVICE → CARRIER
  hasContact → PERSON else OTHER
```
- 11 个值中 ARCHIVED 是线程属性混入消息分类(见 §5)。
- PACKAGE/CODE 的先后关系是刻意的(取件码不进验证码过滤器,注释在 `SmsModels.kt:58`)。

### 7.3 防轰炸状态机 — 两处独立实现

```
antiBomb == true 且 now > antiBombUntil  → 验证码静音 + 从"全部"隐藏
实现①: SmsReceiver.kt:53  (bombActive, 控制通知/剪贴板)
实现②: SmsViewModel.kt:97  (antiBombActive, 控制列表过滤)
```
同一业务规则、两份代码、一个用 prefs 原始值、一个用 UiState 副本 — 修改时极易漏改一处。

### 7.4 多选状态机 — 两处平行实现

```
HomeScreen:   selectionMode + selectedIds + allSelected + exitSelection (MainScreen.kt:318-330)
ThreadDetail: multiSelect + selectedKeys + allSelected + exitMultiSelect (ThreadDetailScreen.kt:222-234)
```
相同的"进入→全选/取消全选→操作→退出"状态机,字段名都几乎一样。

### 7.5 pager ↔ filter 双向对账(SmsListContent)

`pagerState.currentPage` 与 `state.filter` 互相纠正(`MainScreen.kt:639-653`),另有 `userSwiped` 标志防首次组合覆盖深链 filter。这是刻意设计(注释说明),但确实是两个状态源,任何一方漏同步都会出现"chip 与页面不一致"。

---

## 8. 重复代码清单(全部带证据)

### A. 字面重复(逐行/近逐行)

| # | 位置 | 重复内容 | 程度 |
|---|---|---|---|
| A1 | `MmsSender.kt:152-175` vs `:178-199` | `imageBytes()` 与 `compressImage()`:解码→缩放 MAX_DIMENSION→JPEG 85→recycle,仅输出目标不同 | 逐行 |
| A2 | `ThreadDetailScreen.kt:187-206` vs `:250-267` | URI→File→PendingAttachment 转换(display_name→mime→ext→copyTo cacheDir),share 入口与文件选择器两份 | 近逐行 |
| A3 | `ThreadDetailScreen.kt:941-1007` vs `:1008-1074` | MessageBubble sent/received 分支:attachmentName+imageUris.forEach+BubbleContent 三段布局互为镜像,仅对齐/头像/颜色不同 | 结构镜像 |
| A4 | `ThreadDetailScreen.kt:541-549` vs `:591-604` | 发送成功清理:`pendingAttachment=null; input=""; if(currentThreadId==0L){...}; vm.refresh()` MMS/SMS 两分支 | 逐行 |
| A5 | `ThreadDetailScreen.kt:1130-1135` vs `:1164-1168` | selected 高亮 `primary.copy(alpha=0.25f)` 背景 | 逐行 |

### B. 结构重复(流程相同,代码不同)

| # | 位置 | 重复流程 |
|---|---|---|
| B1 | `MainScreen.kt:757-787 / 796-815 / 823-842 / 854-876` | 4 个 FilterPage:LazyColumn + `PaddingValues(16,12)` + `spacedBy(16)` + EmptyBox + items |
| B2 | `MainScreen.kt:1103-1208` | 4 个设置项行:Row(Column(weight1f){title+hint} + Switch) |
| B3 | `SmsViewModel.kt:50-55 / 60-65 / 68-73 / 121-126 / 129-134` | 5 条管道:`messages.filter{category}.flatMap{extractAllCodes}.map{copy(code)}.sortedByDescending{date}` |
| B4 | `SmsViewModel.kt:110-118` vs `:140-148` | `threadsFor()` 与 `visibleThreads`:`groupBy{threadId?:addr.hashCode}.maxBy{date}.count{!isRead}.sortedByDescending` 完全相同 |
| B5 | `SmsRepository.kt:295-300` vs `:350-355` | type/msg_box → SendStatus 的 when 映射 |
| B6 | `ThreadDetailScreen.kt:526-534` vs `:564-572` | 发送 PendingIntent 构造(ACTION_SMS_SENT + type + recordId) |
| B7 | `MainScreen.kt:422-509` vs `ThreadDetailScreen.kt:376-429` | 多选顶栏动作组:全选/取消全选 + 复制/分享/删除 + 关闭 图标按钮序列 |
| B8 | `CodeWidgetProvider.kt:139-186` / `ListWidgetProvider.kt:162-207` | Widget 各自重查 SMS provider(projection 相似、TRY/CATCH 包 query 相同) |

### C. 业务逻辑重复(优先级最高)

| # | 逻辑 | 实现位置 | 差异 |
|---|---|---|---|
| C1 | 剪贴板复制验证码/文本 | ① `SmsReceiver.kt:64-66` ② `CopyCodeReceiver.kt:15-16` ③ `MainScreen.kt:1230-1232` ④ `ThreadDetailScreen.kt:392`(LocalClipboardManager) ⑤ `:1302` | 4+ 处,API 不同 |
| C2 | 时间显示 | ① `TimeUtil.formatRelativeTime` ② `SmartCodeCard.formatSendTime`(今天 HH:mm / 更早 MM-dd) ③ `ThreadDetailScreen.kt:938` 裸 `SimpleDateFormat("HH:mm")` ④ `ListWidgetProvider.kt:283` `DateFormat.format("HH:mm")` | 4 种实现,3 种格式规则 |
| C3 | 防轰炸生效判断 | `SmsReceiver.kt:53` vs `SmsViewModel.kt:97` | 表达式一致 |
| C4 | 设置读取 | `SmsRepository` 12 个 getter vs `SmsReceiver.kt:44-53` 直读 prefs | 双实现 |
| C5 | 联系人名称解析 | `SmsRepository.lookupContact`(normalizeNumber + 全量索引) vs `ListWidgetViewsFactory.nameFor`(endsWith takeLast(7) 扫描) | 算法不同,可能结果不同! |
| C6 | 头像调色板 | `theme/Color.kt:24-33` AvatarPalette vs `ListWidgetProvider.kt:243-269` intArray | 同 8 色两份硬编码 |
| C7 | SIM 选择 SmsManager | `ThreadDetailScreen.kt:574-578` vs `MmsSender.kt:112-116` | `subId>0 ? getSmsManagerForSubscriptionId : getDefault` |
| C8 | URI→File 附件物化 | `ThreadDetailScreen.kt:187-206/238-247/250-267` | 3 处(见 A2) |

### D. UI 行为重复

| # | 行为 | 位置 |
|---|---|---|
| D1 | 删除确认 AlertDialog | ① MainScreen.kt:366-387(批量) ② ThreadDetailScreen.kt:709-731(批量) ③ :1076-1095(单条) — 结构相同,仅 title/body 不同 |
| D2 | 长按弹出操作菜单(menuAt + MessageActionMenu) | `ThreadDetailScreen.kt:1114/1161/1226` — MmsImage/FileAttachmentCard/BubbleContent 三处各自持有 menuAt state + pointerInput |
| D3 | 空态 EmptyBox | 4 个 FilterPage + ContactsScreen(样式不同) |
| D4 | SIM 下拉选择 | `MainScreen.kt:1069-1098`(设置页) vs `ThreadDetailScreen.kt:1480-1529`(输入栏,带 checkbox) |
| D5 | DropdownMenu 触发模式 | moreMenu/attachMenu/simMenu ×2 — Box{IconButton+DropdownMenu} |

### E. 概念重复

| # | 概念 | 现状 | 结论 |
|---|---|---|---|
| E1 | Message | `SmsMessage` 是唯一模型,但 3 个映射器(query/queryMms/outboxMessages)各自拼装,outbox 用**负 id** 伪装成 MMS | 统一模型 ✓,但"负 id"是隐式魔法(UI 靠 `id<0` 判断来源,`ThreadDetailScreen.kt:775`);三源应抽象为"来源适配器"或至少把负 id 封装成显式来源枚举 |
| E2 | 发送状态 | SendStatus + isSent 两字段由 3 处映射(见 §7.1) | 概念重复且语义分叉 |
| E3 | Contact | `ContactInfo(name, photoUri, number)` 在 Repository 内多次构造;widget 用独立查询 | 概念一致,实现分叉(C5) |
| E4 | Conversation | 无领域对象,SmsThreadItem 纯 UI 聚合 + 3 处分组逻辑(§5) | 可接受(小项目),但删除线程不清理 outbox 的残留问题源于没有统一入口 |
| E5 | 归档 | thread 级状态经 message category 传播 | 概念混淆(§5) |
| E6 | 已读 | `isRead` 由 provider 列提供;打开线程=置已读 | 一致 ✓ |

---

## 9. 高耦合清单

| 节点 | 耦合来源 | 行数 | 风险 |
|---|---|---|---|
| ThreadDetailScreen | 发送/删除/分享/媒体/通讯录全内联 + 所有 UI 状态 | 1601 | 改发送逻辑必动 UI;无法单测发送路径 |
| SmsRepository | 数据访问+设置+联系人+归档 | 548 | 设置键改动波及接收端;联系人算法双实现 |
| UiState | 27 字段 + 业务管道 | 149 | 管道重复 5 次;任何新过滤视图都复制粘贴 |
| SmsReceiver | 接收+入库+分类+设置+通知+剪贴板+widget | 106 | 设置判断与 Repository 双源 |
| MainScreen | 权限+默认app+搜索+多选+防轰炸+pager+设置 | 1233 | HomeScreen 单体函数 ~300 行 |

---

## 10. 跨层泄漏清单

| 泄漏 | 位置 | 说明 |
|---|---|---|
| UI → SmsManager | `ThreadDetailScreen.kt:579` | 发送主路径在 Composable 回调里直接调系统 API;无 Repository 包装,发送逻辑不可测、不可复用(Widget 无发送入口,目前是唯一的) |
| UI → ContentResolver | `ThreadDetailScreen.kt:779-785` | 删除消息直接拼 `content://sms/{id}` URI |
| UI → MediaStore | `ThreadDetailScreen.kt:882-898` | 保存图片到相册的逻辑在 UI 文件 |
| UI → ContactsContract/BlockedNumber | `:839 / :861` | 加联系人/拉黑在 UI |
| UI → SharedPreferences | `SmsReceiver.kt:44` | 接收端绕过 Repository 直读设置(与 Repository getter 双实现,C4) |
| Repository → SharedPreferences | `SmsRepository.kt:59-116` | 设置持久化塞进数据仓库(12 个方法) |
| ViewModel → SubscriptionManager | `SmsViewModel.kt:315` | SIM 枚举直连系统 API(可接受但属于系统服务层) |
| Widget → provider 直查 | `CodeWidgetProvider.kt:151` `ListWidgetProvider.kt:173` | 受 RemoteViews 进程限制,**合理泄漏**(注释已说明),但查询 projection 与 Repository 重复(B8) |
| 模型 → 解析器 | `SmsModels.kt:109-112` | SmsMessage 属性调 SmsParser(merchantName/isPickupCode)— 轻微反向依赖,可接受 |

---

## 11. 可复用组件候选

| 候选 | 内容 | 来源 | 价值 |
|---|---|---|---|
| `MessageSender` | SMS/MMS 发送编排(占位行 + PendingIntent + SmsManager/SIM 选择 + 成功清理) | A4/B6/C7 + ThreadDetail 发送段 | 高 — 移除 UI→SmsManager 泄漏,发送路径可测 |
| `Clipboard.copyCode(context, code)` | 剪贴板+Toast 封装 | C1 的 3 处(排除 LocalClipboardManager 变体) | 高 — 4+ 处统一 |
| `CodeEntryExtractor`(或顶层函数) | `filter→flatMap(extractAllCodes)→map(copy)→sorted` 管道 | B3 5 处 | 高 — 消除 5 份管道 |
| `ThreadGrouper`(顶层函数) | `groupBy→maxBy→count→sortedBy` | B4 2 处 | 中 |
| `MessageRow`/`ConversationRow` | 列表行(头像+名称+预览+时间+角标) | MessageItem + widget row 语义 | 中 — 但 RemoteViews 与 Compose 不能共享组件,只能共享纯函数(名称/时间/头像色) |
| `AvatarPalette` 纯函数化 | `avatarColorFor` + widget int 色 | C6 | 中 — widget 无法用 Compose Color,需 Int 化纯函数 |
| `DeleteConfirmDialog` | 三处删除确认 | D1 | 低-中 — 参数化后省 ~20 行/处 |
| `MultiSelectState`(或统一模式) | selectionMode/selectedIds/allSelected/exit | D(多选) | 中 — 但两处 UI 语义略有差异(列表行 vs 消息键),需谨慎 |
| `MessageActionsMenu` | 长按菜单统一弹出管理 | D2 | 低 — 三处已经共用 MessageActionMenu 内容,缺的只是 menuAt 状态提升 |
| `SettingsStore` | 12 个 getter/setter 移出 Repository | C4 | 高 — 消除双源 + 收窄 Repository |

---

## 12. 假抽象清单

| 名称 | 判定 | 说明 |
|---|---|---|
| `SmsParser` | ✅ 真抽象 | 纯函数、无 Android 依赖、有测试(137 行)—— 全项目最健康的部分,继续往里加逻辑即可 |
| `SmsMessage` | ✅ 合理 | 三源统一模型合理;负 id 是隐式魔法,建议将来显式化来源,但不急 |
| `SendStatus` | ⚠️ 半成品 | 枚举本身对,但映射散在 2 处且 isSent 语义分叉(§7.1) |
| `UiState` | ⚠️ 伪状态 | 计算属性承担业务管道(5 份重复),它应是"状态容器"而非"管道执行者" |
| `OutboxStore` | ✅ 职责单一 | 但内部序列化重复(add 内联 vs toJson)且 **toJson 丢失 name/mime 字段**(见 P0-1) |
| `TimeUtil` | ✅ | 单函数文件,但 4 种时间格式未收拢 |
| `Utils/Helper/Common/Base` | ✅ 不存在 | 项目没有万能工具类 — 好 |
| `archivedUri` (`SmsRepository.kt:27`) | ⚠️ 死抽象 | 定义了从未作为查询 URI 使用,只有 `uri == archivedUri` 恒 false 的死分支(L302) |
| `SmsFilter.matches(category, body="")` | ⚠️ 死参数 | `body` 参数从未被使用(`SmsModels.kt:55-62`) |

---

## 13. 重构优先级(重复次数 × 修改频率 × 出错风险)

### P0(可能造成数据错误/行为错误)

| # | 问题 | 证据 | 风险 |
|---|---|---|---|
| P0-1 | **OutboxStore 序列化丢字段**:`mark()/remove()` 走 `write→toJson()`,而 `toJson` **不含 `name`/`mime`**(`OutboxStore.kt:102-117`),只有 `add()` 内联版有。发送失败标记或删除任一 MMS 记录后,整个列表的附件名/类型被清空 → 已发送文件附件变成无名字卡 | `OutboxStore.kt:32-55 vs 102-117` | 数据错误,用户可见 |
| P0-2 | **SMS 发送中消息渲染在接收侧**:`insertPendingSms` 写 TYPE=6,`query()` 的 `isSent = type == MESSAGE_TYPE_SENT`(`SmsRepository.kt:294`)→ OUTBOX 行 isSent=false → 左侧渲染;MMS outbox 恒 isSent=true → 右侧。同一"我的发送"两种 UI 行为 | §7.1 | ~~行为不一致~~ **已解决(4.4)**:smsIsSent 统一为 SENT/QUEUED/OUTBOX → 右侧 + SENDING 图标,与 MMS 对齐 |
| P0-3 | **LazyColumn key 冲突风险**:HomeScreen `items(key = threadId)`(`MainScreen.kt:776/864`);`threadsFor` 分组对 threadId=0 有 address 回退(`SmsViewModel.kt:112`),但 item key 仍用**原始 threadId** — 两条不同地址的 threadId=0 消息会生成重复 key → `IllegalArgumentException` | `SmsViewModel.kt:112` vs `MainScreen.kt:776` | 潜在 crash(取决于 provider 数据) |
| P0-4 | **删除线程不清理 OutboxStore**:`deleteThread`(`SmsRepository.kt:119-135`)只删 provider 行;该线程的 MMS outbox 记录仍在 `loadAll` → 幽灵会话 | `SmsRepository.kt:119` vs `ThreadDetailScreen.kt:775` | 数据残留 |

### P1(严重重复/严重耦合)

| # | 问题 | 证据 |
|---|---|---|
| P1-1 | ThreadDetailScreen 1601 行全能页 + UI→SmsManager/ContentResolver/MediaStore 泄漏 | §9/§10 |
| P1-2 | 5 条 code/pickup 提取管道重复 | B3 |
| P1-3 | 剪贴板复制 4-5 处实现 | C1 |
| P1-4 | 设置双源(Repository getter vs SmsReceiver 直读) | C4 |
| P1-5 | 防轰炸判断双实现 | C3 |
| P1-6 | 联系人解析双算法(widget 与 app 可能显示不同名字) | C5 |
| P1-7 | 头像调色板双份硬编码 | C6 |
| P1-8 | 时间格式 4 种实现 | C2 |
| P1-9 | 多选状态机平行实现 ×2 + 顶栏动作组重复 | B7/D |
| P1-10 | 发送流程(PendingIntent+SIM+清理)重复 + UI 直连 SmsManager | A4/B6/C7 |

### P2(普通重复)

| # | 问题 |
|---|---|
| P2-1 | 4 个 FilterPage 结构重复(B1) |
| P2-2 | 4 个设置项行重复(B2) |
| P2-3 | SendStatus 映射 ×2(B5) |
| P2-4 | 删除确认对话框 ×3(D1) |
| P2-5 | Widget 查询代码重复(B8) |
| P2-6 | threadsFor vs visibleThreads 重复(B4) |
| P2-7 | 三处长按菜单 menuAt 状态管理(D2) |

### P3(风格/死代码)

| # | 问题 |
|---|---|
| P3-1 | `archivedUri` 死分支 + `SmsFilter.matches` 死参数 |
| P3-2 | MessageList.kt:36-38 重复 import(Check_circle/Package_2/Safety_check 导入两次) |
| P3-3 | mms/pdu AOSP 移植代码无来源注释(建议加一行 provenance,勿重构) |

---

## 14. 每项重构的风险

| 候选 | 风险 | 缓解 |
|---|---|---|
| MessageSender 抽取 | 发送是主路径,改动影响真机发送 | 先抽取纯函数(成功清理、PendingIntent 构造),再动 SmsManager 调用;真机验证 SMS+MMS+双卡 |
| 管道统一(B3) | UiState 计算属性被 4 个页面依赖,行为差异微妙(codeEntries 排除 PACKAGE、allCodeEntries 含 PACKAGE) | 每个管道先写测试锁定行为,再合并;**不要**合并语义不同的变体 |
| 剪贴板统一 | SmsReceiver 的静默复制(无 Toast)与 UI 的复制(有 Toast)行为不同 | 保留 silent/withToast 参数 |
| SettingsStore 抽取 | 纯搬移,Repository 接口不变 | 编译级验证;SmsReceiver 切到 SettingsStore 后行为不变 |
| 防轰炸统一 | 两处语义相同,但一处读 prefs、一处读 UiState | 抽成 `fun antiBombActive(antiBomb, until, now)` 纯函数,两处调用 |
| OutboxStore 修复(P0-1) | JSON 格式兼容:旧文件缺 name/mime 字段 | `optString` 已有默认值;新 toJson 补字段即可,向前兼容 |
| isSent 统一(P0-2) | **必须先真机确认当前行为**,可能是刻意设计 | 确认前不改;确认后按"发送中显示右侧+SENDING 图标"统一 |
| 头像调色板统一 | widget 是 RemoteViews,不能用 Compose Color | 纯函数返回 Int/Long 色值,Compose 层转换 |

---

## 15. 推荐的渐进式重构路线

按"风险从低到高、收益从高到低"排序,每步独立可编译可验证:

```
Phase 1(纯函数提取,零行为风险,测试可锁定)
  1.1 OutboxStore 序列化去重 + 补 name/mime 字段  ← P0-1,唯一修 bug 项
  1.2 antiBombActive 纯函数统一两处判断           ← C3
  1.3 时间格式化收拢(4 处 → 2 个纯函数:列表相对时间/气泡绝对时间)
  1.4 剪贴板复制封装(静默/带 Toast 两个变体)
  1.5 code/pickup 提取管道统一(SmsViewModel 5 处 → 1 个纯函数 + 语义参数)
  1.6 线程分组纯函数(threadsFor/visibleThreads 共用)

Phase 2(职责归位,编译级验证)
  2.1 SettingsStore 从 SmsRepository 拆出,SmsReceiver 改用  ← C4/P1-4
  2.2 头像调色板纯函数化(widget Int 色 + Compose Color 适配) ← C6
  2.3 SendStatus 映射集中(单文件 when,isSent 先保持现状)   ← B5
  2.4 发送 PendingIntent 构造 + 成功清理 提取               ← A4/B6(不碰 SmsManager)

Phase 3(组件抽取,行为等价)
  3.1 删除确认对话框统一参数化                          ← D1
  3.2 FilterPage 骨架统一(empty/threads/codes 三种内容槽) ← B1
  3.3 设置项行组件                                        ← B2
  3.4 多选顶栏动作组(两页共用,参数化)                    ← B7

Phase 4(结构性重构,需真机回归)
  4.1 MessageSender:发送主路径移出 Composable          ← P1-1/P1-10
  4.2 联系人解析统一(widget 复用 repository 算法或纯函数化) ← P0 相关,C5
  4.3 删除线程清理 OutboxStore                           ← P0-4
  4.4 isSent 语义统一(真机确认后)                       ← P0-2
  4.5 Conversation 领域对象(如确有必要,先不引入)        ← E4

Phase 5(不做清单)
  - 不引入 Hilt/ViewModel Factory/UseCase 层/多模块
  - 不重写 mms/pdu(AOSP 移植,加 provenance 注释即可)
  - 不合并 widget 与 app 的组件(RemoteViews 限制)—— 只共享纯函数
  - 不"顺手"升级依赖、改 UI、修无关 bug
```

---

## 附:UNKNOWN 清单(不猜测,待验证)

| # | 事项 | 待验证方式 |
|---|---|---|
| U1 | SMS TYPE=6 行 isSent=false → 左侧渲染,是 bug 还是刻意? | 真机发送后立即观察气泡位置 |
| U2 | threadId=0 消息是否真实存在(影响 P0-3 是否触发) | 真机查 provider 数据 |
| U3 | OutboxStore name/mime 丢失是否已被用户观察到(可能触发过"附件变无名字") | 询问用户或查旧版行为 |
| U4 | `archiveThread` 成功后系统 archived 视图是否真的更新(注释声称,但代码走本地集合) | 行为核对 |
| U5 | ListWidgetViewsFactory 与 Repository 联系人解析结果不一致的实例 | 构造测试号码对照 |
| U6 | `SettingsScreen` 的 `antiBombUntil` 倒计时在 app 重启后是否恢复正确(antiBombUntil 是持久化的,UiState 初始 0L,首次 refresh 会读入) | 代码路径核对即可,低风险 |
