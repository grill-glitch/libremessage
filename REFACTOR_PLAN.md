# LibreLab Message 重构计划 (REFACTOR_PLAN)

> 配套文档:`ARCHAEOLOGY.md`(考古证据,所有"为什么"见那里)
> 执行铁律:**一次只动一个模块 → 编译 → 测试 → 真机冒烟 → 提交 → 下一处**。
> 禁止:大规模重写、同时改多个模块、顺手改 UI/依赖/架构、无证据删代码。
> 每个 Phase 完成后 `git commit` 一次;每个 Step 都可在半小时内回滚(`git revert` 单提交)。

---

## 总览

| Phase | 内容 | 风险 | 主要收益 | 依赖 |
|---|---|---|---|---|
| 1 | 纯函数提取 + 唯一 bug 修复 | 极低 | 消除 5+ 重复管道、修 P0-1 | 无 |
| 2 | 职责归位(SettingsStore/映射集中) | 低 | 收窄 Repository、消灭设置双源 | Phase 1 |
| 3 | UI 组件抽取 | 低-中 | FilterPage/对话框/设置行复用 | Phase 1-2 |
| 4 | 结构性重构(发送路径/联系人/删除线程) | 中 | UI→SmsManager 泄漏移除、数据残留修复 | Phase 2,需真机回归 |
| 5 | 不做清单 | — | 防止过度工程 | — |

---

## Phase 1 — 纯函数提取(零行为风险,每步可 JVM 测试)

### Step 1.1 修复 OutboxStore 序列化丢字段 【P0-1,唯一修 bug 项】

**现状**:`add()` 内联序列化含 `name/mime`(`OutboxStore.kt:32-55`);`write()/toJson()` 不含(`:102-117`)。`mark()/remove()` 走 write → 附件名/类型被清空。

**步骤**:
1. 删除 `add()` 的内联序列化,统一改为调用 `write(context, list + record)`。
2. `toJson()` 补上 `name`/`mime` 字段(与 add 内联版一致)。
3. 保持读路径不变(`optString("name","").ifBlank{null}` 已兼容旧数据)。

**验证**:
- 单测(新增):`add` → `mark` → `all` 后 name/mime 仍存在;`remove` 后其它记录字段完整。
- 现网兼容:旧 outbox_mms.json 无 name/mime 字段 → 读取返回 null(现有 `optString` 行为不变)。

**风险**:极低。JSON 只加字段,读路径向后兼容。

---

### Step 1.2 antiBombActive 纯函数统一 【C3】

**现状**:`SmsReceiver.kt:53` 与 `SmsViewModel.kt:97` 各写一份 `antiBomb && now > until`。

**步骤**:
1. 在 `data/SmsModels.kt`(或 SmsParser 旁)新增顶层函数:
   `fun antiBombActive(antiBomb: Boolean, until: Long, now: Long = System.currentTimeMillis()): Boolean`
2. 两处调用点替换。
3. SmsReceiver 的 prefs 读取保留到 Step 2.1(SettingsStore)再收拢。

**验证**:单测(now 边界:until=0、now==until、now>until、antiBomb=false);编译 + 现有测试全绿。

---

### Step 1.3 时间格式化收拢 【C2,4 种实现】

**现状**:① TimeUtil.formatRelativeTime(列表相对时间)② SmartCodeCard.formatSendTime(今天 HH:mm / 更早 MM-dd)③ ThreadDetailScreen.kt:938 裸 `SimpleDateFormat("HH:mm")`(气泡时间)④ widget `DateFormat.format("HH:mm")`。

**步骤**:
1. `TimeUtil.kt` 新增纯函数 `fun formatBubbleTime(dateMillis: Long): String`(即现在的 ③,无 Context 依赖)。ThreadDetailScreen 改用它。
2. `SmartCodeCard.formatSendTime` 保留(语义不同:跨天显示 MM-dd),但挪为 `TimeUtil` 顶层函数 `formatSmartCardTime`。
3. widget 的 `SmsThreadRow.time` 保留(RemoteViews 进程、android.text.format.DateFormat 依赖系统 locale)——**只共享规则,不强行统一 API**,在函数注释互相引用。
4. 新增测试:`formatBubbleTime`(今天/跨天)。

**验证**:UI 截图对比(气泡时间、卡片时间无变化);测试覆盖格式分支。

**风险**:低。③ 是裸实现,替换为同格式纯函数零风险。

---

### Step 1.4 剪贴板复制封装 【C1,4+ 处】

**现状**:SmsReceiver(静默)、CopyCodeReceiver(带 Toast)、MainScreen.copyCode(带 Toast)、ThreadDetailScreen ×2(LocalClipboardManager)。

**步骤**:
1. `util/Clipboard.kt`:
   - `fun copyToClipboard(context: Context, label: String, text: String)` — 无 Toast。
   - `fun copyCode(context: Context, code: String, showToast: Boolean = true)` — 包装 + 可选 Toast(保持 SmsReceiver 静默、UI 带 Toast 的差异)。
2. 替换:MainScreen.copyCode、CopyCodeReceiver、SmsReceiver 的剪贴板段。
3. ThreadDetailScreen 的 `LocalClipboardManager` 两处**暂不换**(Compose 内 API 更合适,且行为含 AnnotatedString)——只把逻辑注释互指,避免 Compose/非 Compose 混用。

**验证**:编译;手动:验证码通知点复制按钮 → Toast;自动复制(开/关)行为不变;列表复制按钮。

**风险**:低。纯搬移。

---

### Step 1.5 code/pickup 提取管道统一 【B3,5 处】

**现状**:`SmsViewModel.kt:50-55/60-65/68-73/121-126/129-134` 五条几乎相同的 `filter → flatMap(extractAllCodes) → map(copy(code)) → sortedByDescending`。

**语义差异(必须保留)**:
| 管道 | category 过滤 | 用途 |
|---|---|---|
| allPickups | PACKAGE | 包裹 banner |
| allCodeEntries | CODE+PACKAGE | 首页 banner 全部 |
| codeEntries | CODE | 验证码页数据源之一(被 codesFor 取代?— 见下) |
| codesFor | CODE | 验证码页 |
| pickupsFor | PACKAGE | 包裹页 |

注意:`codeEntries` 与 `codesFor` 完全相同(`:68-73` vs `:121-126`)—— 一个死一个活,先确认哪个被引用(报告时 `codeEntries` 只见于 UiState 自身,`codesFor` 被 CodeFilterPage 用)。**核对后删除死的一个**。

**步骤**:
1. 新增顶层函数(放 `data/SmsParser.kt` 旁或独立文件):
   ```kotlin
   fun codeEntries(messages: List<SmsMessage>, categories: Set<MessageCategory>): List<SmsMessage> =
       messages.filter { it.category in categories }
           .flatMap { msg -> SmsParser.extractAllCodes(msg.body).map { code -> msg.copy(code = code) } }
           .sortedByDescending { it.date }
   ```
2. 五处调用改为 `codeEntries(messages, setOf(...))`。
3. 删除确认无引用的 `codeEntries` 属性。

**验证**:为每条管道写行为测试(含"一条消息多取件码拆分"用例,复制现有 `SmsParserTest.extractMultipleExpressCodes` 的输入);主页截图对比 验证码页/包裹页/首页 banner。

**风险**:中低。管道语义完全一致,差异只在 category 集合;测试锁定。

---

### Step 1.6 线程分组纯函数 【B4,2 处】

**现状**:`threadsFor`(`SmsViewModel.kt:110-118`)与 `visibleThreads`(`:140-148`)完全相同。

**步骤**:
1. 顶层函数 `fun groupThreads(messages: List<SmsMessage>): List<SmsThreadItem>`(含 `threadId ?: address.hashCode()` 回退)。
2. 两个属性都改调它;`visibleThreads` 变为 `get() = groupThreads(visibleMessages)`。
3. 确认没有第三处隐藏分组逻辑(MainScreen 的 items key 用原始 threadId — **与分组键不一致的隐患在 Step 4.3 一并处理**,此处先标注 TODO 注释)。

**验证**:单测:threadId=0 不同 address 的两条消息 → 两组;正常线程 → 最新在前、未读计数正确。

---

### Step 1.7(附带)MmsSender 图片压缩去重 【A1】

**现状**:`imageBytes()` 与 `compressImage()` 逐行重复(缩放 MAX_DIMENSION + JPEG 85 + recycle)。

**步骤**:
1. 提取 `private fun scaledJpeg(src: File, out: OutputStream?)` 或 `decodeScaled(src): Bitmap?`(解码+缩放,共享)。
2. `imageBytes` 用 `ByteArrayOutputStream`,`compressImage` 用文件流。

**验证**:单测(1×1、超尺寸、非图片文件回退原字节);真机发一张大图 MMS 确认压缩尺寸 ≤1280。

---

## Phase 2 — 职责归位(编译级验证,不改行为)

### Step 2.1 SettingsStore 拆分 【C4/P1-4】

**现状**:12 个 SharedPreferences getter/setter 塞在 SmsRepository(`:59-116`);SmsReceiver 另直读同一文件(`:44-53`)。

**步骤**:
1. 新建 `data/SettingsStore.kt`:`object SettingsStore`(或带 context 参数的类),搬入全部 12 个方法 + 常量 `PREF_NAME = "settings_prefs"`(消除三处魔法字符串:`SmsRepository`、`SmsReceiver`、`SmsViewModel` 的间接引用)。
2. SmsRepository 删除这些方法(它只读 `archive_prefs`,保留)。**注意:Repository 的 `showAdsInAll()` 等被 SmsViewModel 调用 — 改为调用 SettingsStore**。
3. SmsReceiver 直读改为 `SettingsStore.notifyAds(context)/antiBomb(context)/antiBombUntil(context)/autoCopyCode(context)`。

**验证**:编译;全部设置开关走一遍(默认卡、广告显示、静音、自动复制、防轰炸);接收一条广告/验证码短信验证静音与复制逻辑(防轰炸逻辑已在 Phase 1 统一)。

**风险**:低。纯搬移 + 收口。

---

### Step 2.2 头像调色板纯函数化 【C6】

**现状**:Compose `AvatarPalette`(`theme/Color.kt:24-33`)与 widget `intArray`(`ListWidgetProvider.kt:243-269`)同 8 色两份。

**步骤**:
1. `theme/Color.kt` 新增:
   ```kotlin
   // 供 Compose 与 RemoteViews 共用的 8 色容器/内容 ARGB 值
   val AvatarPaletteArgb: List<Pair<Long, Long>>  // 或 IntArray
   fun avatarColorArgbFor(key: String): Pair<Int, Int>
   fun avatarColorFor(key: String): AvatarColor  // 由 Argb 转换,保持现有签名
   ```
2. Compose 侧 `avatarColorFor` 改为基于 Argb 版转换;widget 侧删除 intArray,改调 `avatarColorArgbFor`。
3. ListWidgetViewsFactory 的 `avatarColorForKey/avatarContentForKey` 删除。

**验证**:截图对比首页头像颜色与 widget 头像颜色(同号码同色);编译两处。

**风险**:低。色值不变,只是来源收拢。

---

### Step 2.3 SendStatus 映射集中 【B5】

**现状**:sms type→status(`SmsRepository.kt:295-300`)与 mms msg_box→status(`:350-355`)两个 when。

**步骤**:
1. `data/SmsModels.kt` 新增:
   ```kotlin
   fun smsSendStatus(type: Int): SendStatus   // 2→SENT, 4/6→SENDING, 5→FAILED, else NONE
   fun mmsSendStatus(box: Int): SendStatus    // 2→SENT, 4→SENDING, 5→FAILED, else NONE
   fun smsIsSent(type: Int): Boolean          // 保持现状: type==2
   fun mmsIsSent(box: Int): Boolean           // 保持现状: box!=1
   ```
2. Repository 两处调用替换。**isSent 语义分歧(P0-2)此时不动**,只集中位置,等 Step 4.4 真机确认后统一。

**验证**:单测:每个 type/box 值映射正确;编译。

---

### Step 2.4 发送 PendingIntent + 成功清理提取 【A4/B6】

**现状**:MMS/SMS 两分支各自构造 PendingIntent(内容相同,仅 type/recordId 不同),各自写成功清理 4 行。

**步骤**:
1. 顶层函数:
   ```kotlin
   fun sendResultIntent(context: Context, type: String, recordId: Long): PendingIntent
   ```
2. 成功清理提为 `fun resetAfterSend(...)` 或复用闭包——注意两分支在 MMS 走 IO 协程,清理在 Main 线程回调里执行;提取时保持这个线程语义。

**验证**:真机发 SMS + 带图 MMS 各一次,观察气泡、输入框清空、threadId 翻转。

---

## Phase 3 — UI 组件抽取(行为等价)

### Step 3.1 删除确认对话框统一 【D1,3 处】

**现状**:Home 批量删除、Detail 批量删除、Detail 单条删除 — 三个结构相同的 AlertDialog。

**步骤**:
1. `ui/components/ConfirmDialog.kt`:
   ```kotlin
   @Composable fun ConfirmDialog(
       title: String, body: String, confirmText: String = stringResource(R.string.action_delete),
       destructive: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit
   )
   ```
2. 三处替换。注意:单条删除确认按钮文字是 error 色(`ThreadDetailScreen.kt:1086`),批量是默认色 — `destructive` 参数保留此差异。

**验证**:三种删除场景各点一遍;深色/浅色主题下对比。

---

### Step 3.2 FilterPage 骨架统一 【B1,4 处】

**现状**:AllFilterPage/CodeFilterPage/PackageFilterPage/ThreadFilterPage 各自 LazyColumn + 空态 + items。

**步骤**:
1. 抽象两个内容槽,不做大组件:
   ```kotlin
   @Composable fun ThreadListPage(threads, onOpenThread, selectionMode, ...)   // All/AD/ARCHIVED 共用
   @Composable fun CodeListPage(entries, onCopy, onOpenOriginal)               // CODE/PACKAGE 共用
   ```
2. AllFilterPage 保持特殊(banner 在最前),但列表部分复用 ThreadListPage;AD/ARCHIVED 直接是 ThreadListPage 的薄包装。

**验证**:四个 filter 页切换、多选、空态、banner 位置逐一截图对比。

**风险**:中低 — 这是纯布局复用,但 LazyColumn 的 key/滚动状态差异需留意(每页独立 rememberLazyListState 保持现状)。

---

### Step 3.3 设置项行组件 【B2】

**现状**:设置页 4 行 Row+Switch 结构重复,其中 1 行(防轰炸)无 hint、1 行(SIM)是下拉。

**步骤**:
1. `@Composable fun SettingsSwitchRow(title, hint, checked, onCheckedChange, enabled=true)`。
2. 广告显示/广告静音/自动复制/防轰炸四行替换(自动复制行的 enabled 联动保留)。

**验证**:设置页截图;开关联动(防轰炸开 → 自动复制灰)不变。

---

### Step 3.4 多选顶栏动作组 【B7】

**现状**:Home 与 Detail 各有一组全选/取消全选 + 复制/分享/删除 + 关闭图标按钮(icon 集不同)。

**步骤**:
1. 仅提取公共骨架:`@Composable fun MultiSelectActions(allSelected, onToggleAll, onClose, actions: @Composable RowScope.() -> Unit)`。
2. 两页各自提供自己的图标序列(Home 无复制/分享,Detail 有)— **不要强行统一图标**。

**验证**:两页多选操作全流程。

---

## Phase 4 — 结构性重构(需真机回归)

### Step 4.1 MessageSender:发送路径移出 Composable 【P1-1/P1-10】

**目标**:ThreadDetailScreen 的 onSend 段(约 100 行)→ `data/MessageSender.kt`,消除 UI→SmsManager 泄漏。

**步骤**:
1. `class MessageSender(private val context: Context, private val vm: SmsViewModel)`(或纯函数 + 回调):
   - `sendSms(address, body, subId, onResult: (Long) -> Unit)`(占位行 + PendingIntent + SmsManager + 异常 → markSmsFailed)
   - `sendMms(address, body, attachment, subId, onResult)`(MmsSender 编排 + 清理)
2. UI 只保留输入校验(空文本/空号码/权限)与回调里清理+refresh。
3. **行为逐字保持**:MMS 走 IO 协程、SMS 同步 + 异常 Toast、成功后 currentThreadId 翻转逻辑。

**验证**:真机:新会话发短信(首条翻线程)、带图 MMS、双 SIM 选择、无权限 Toast、MMS 数据未开 Toast。编译。

**风险**:中 — 发送是主路径。Phase 1/2 的纯函数(Step 2.4)先落地,这一步只是把它们组织进一个类,降低单步风险。

---

### Step 4.2 联系人解析统一 【C5】

**现状**:Repository(normalizeNumber + 全量索引,有缓存)vs widget(endsWith takeLast(7) 线性扫描,有缓存)— 可能给出不同名字。

**步骤**:
1. 把 Repository 的 `normalizeNumber` + 索引逻辑提取为可被 RemoteViews 进程使用的形式(纯函数 + 无 Compose 依赖)。
2. widget 侧先改为**同一算法**(数字归一化后全量索引),确认结果一致后删除旧算法。
3. 注明:widget 进程独立,缓存无法共享,但**算法**必须一致。

**验证**:构造"+86188****"与"188****"对照测试;真机对比 app 列表与 widget 列表同一号码的名字。

**风险**:中 — 联系人解析改动影响所有名字显示;算法统一后行为可能"改变"(widget 从近似匹配变为精确匹配),需用户确认这是期望的修正。

---

### Step 4.3 删除线程清理 OutboxStore 【P0-4】

**步骤**:
1. `SmsRepository.deleteThread` 增加:查询该线程 outbox 记录 → `OutboxStore.remove`(或在 Repository 注入 OutboxStore 过滤)。
2. 同时处理 **item key 隐患(P0-3)**:`groupThreads` 分组键与 items key 统一 — 引入 `SmsThreadItem.key = "t$threadId"`(线程 id 前缀)或分组时用回退键生成线程 key,MainScreen 的 `items(key = ...)` 改用之。

**验证**:删除含已发 MMS 的线程 → 列表无幽灵;两条 threadId=0 不同地址消息共存不崩溃(构造测试数据)。

---

### Step 4.4 isSent 语义统一 【P0-2,先确认后动】

**前置**:真机观察 —— 发 SMS 后气泡初始位置(左/右)与图标。询问用户当前行为是否可接受。

- 若现状=刻意(发送中显示左侧):在 `smsIsSent` 注释明确此契约,不改。
- 若现状=缺陷:统一为 `isSent = type in (2, 4, 6)`(与 MMS `box != 1` 对齐),OUTBOX/QUEUED 渲染右侧 + SENDING 图标。

**验证**:发送瞬间截图(肉眼难抓可用 logcat 打印);发送成功/失败后位置与图标正确。

---

## Phase 5 — 明确不做(防过度工程)

| 事项 | 原因 |
|---|---|
| 引入 Hilt / DI 框架 | 项目无测试注入需求,单例/构造注入足够 |
| UseCase 层 / 多模块 | 7k 行 Kotlin,分层过度 |
| Navigation 组件替换自建 pager | 现状工作正常,注释解释了设计 |
| 重写 mms/pdu | AOSP 移植代码,只加 provenance 注释(P3-3) |
| 草稿持久化 | 无需求证据,先不做 |
| widget 与 Compose 共享组件 | RemoteViews 限制,只共享纯函数 |
| `archivedUri` 系统归档支持 | 当前 ROM 无 archived 列,本地集合是兼容方案;仅删死分支(P3-1) |

---

## 执行检查单(每个 Step 提交前)

```
□ 只改了一个模块
□ 编译通过 (assembleDebug)
□ 现有测试全绿 (SmsParserTest)
□ 该 Step 新增的行为测试存在(纯函数类 Step 必测)
□ 真机冒烟(UI 类 Step)
□ 提交信息说明:改了什么 + 验证了什么
□ git log 可回滚到上一步
```

## 建议提交顺序(11 次提交)

1. `fix(outbox): 修复 mark/remove 丢失附件名与 MIME 字段` (1.1)
2. `refactor: 统一防轰炸生效判断为纯函数` (1.2)
3. `refactor: 收拢时间格式化(气泡时间/卡片时间)` (1.3)
4. `refactor: 封装剪贴板复制(静默/带提示)` (1.4)
5. `refactor: 统一验证码/取件码提取管道` (1.5)
6. `refactor: 线程分组提取为纯函数` (1.6) + MmsSender 去重 (1.7)
7. `refactor: 设置持久化拆出 SettingsStore,接收端切用` (2.1)
8. `refactor: 头像调色板单一来源(widget 共用)` (2.2) + SendStatus 映射集中 (2.3) + 发送 PendingIntent 提取 (2.4)
9. `refactor: 删除确认框/FilterPage/设置行/多选动作组 组件化` (3.1-3.4)
10. `refactor: 发送路径移入 MessageSender` (4.1) + 联系人解析统一 (4.2)
11. `fix: 删除线程清理 outbox 记录与列表 key 冲突` (4.3) + isSent 统一 (4.4, 视真机确认)

> Phase 1-3 全部完成后,若用户满意,可暂缓 Phase 4 继续观察;重构永远可以停在任何一步。
