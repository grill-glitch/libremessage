package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Group
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.librelab.messaging.R
import org.librelab.messaging.data.SmsFilter
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsThreadItem
import org.librelab.messaging.data.SmsViewModel
import org.librelab.messaging.data.UiState

private data class ComposeTarget(val number: String, val body: String)

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.POST_NOTIFICATIONS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: SmsViewModel = viewModel(),
    initialNumber: String = "",
    initialBody: String = ""
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var composeTarget by remember {
        mutableStateOf(
            if (initialNumber.isNotBlank() || initialBody.isNotBlank()) {
                ComposeTarget(initialNumber, initialBody)
            } else null
        )
    }
    var selectedThread by remember { mutableStateOf<SmsThreadItem?>(null) }
    val searchFocus = remember { FocusRequester() }

    // Multi-select mode (long-press a conversation row to enter).
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val visibleThreadIds = state.visibleThreads.map { it.message.threadId }
    val allSelected = visibleThreadIds.isNotEmpty() && selectedIds.containsAll(visibleThreadIds)
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
        showDeleteConfirm = false
    }

    // Entering search mode: focus the search field so the keyboard pops up.
    LaunchedEffect(state.searchActive) {
        if (state.searchActive) searchFocus.requestFocus()
    }

    // Refresh whenever the app resumes (permissions / default-app may have changed).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refresh() }
    val defaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh() }

    // Open the system default-SMS-app picker. On Android 10+ this is a Role
    // request (ACTION_CHANGE_DEFAULT is no longer handled by AOSP MaterialSymbols.Outlined.Settings);
    // below that fall back to the legacy intent.
    val onSetDefaultApp: () -> Unit = {
        val intent = defaultSmsRequestIntent(context)
        if (intent == null) {
            Toast.makeText(context, R.string.default_app_unavailable, Toast.LENGTH_LONG).show()
        } else {
            try {
                defaultLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.default_app_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    // First-run auto-request for the missing runtime permissions.
    LaunchedEffect(Unit) {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    // Conversation detail takes over the whole screen when a row is tapped.
    val thread = selectedThread
    if (thread != null) {
        LaunchedEffect(thread.message.threadId) { vm.openThread(thread.message.threadId) }
        ThreadDetailScreen(
            threadId = thread.message.threadId,
            address = thread.message.address,
            sender = thread.message.sender,
            vm = vm,
            onBack = {
                vm.closeThread()
                selectedThread = null
            }
        )
        return
    }

    if (composeTarget != null) {
        val target = composeTarget!!
        ComposeMessageScreen(
            initialNumber = target.number,
            initialBody = target.body,
            onBack = { composeTarget = null }
        )
        return
    }

    // Full-screen settings page.
    if (state.showSettings) {
        SettingsScreen(
            isDefaultSmsApp = state.isDefaultSmsApp,
            showAdsInAll = state.showAdsInAll,
            onSetDefaultApp = onSetDefaultApp,
            onToggleAdsInAll = { vm.setShowAdsInAll(it) },
            onBack = { vm.setShowSettings(false) }
        )
        return
    }

    // Batch-delete confirmation.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除会话") },
            text = { Text("删除选中的 ${selectedIds.size} 个会话？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteThreads(selectedIds.toList())
                        exitSelection()
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selectionMode -> Text(
                            text = "已选 ${selectedIds.size} 项",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        state.searchActive -> TextField(
                            value = state.searchQuery,
                            onValueChange = vm::setSearchQuery,
                            placeholder = { Text("搜索短信") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus)
                        )
                        else -> Text(
                            text = "短信",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectedIds = if (allSelected) emptySet() else visibleThreadIds.toSet()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (allSelected) {
                                    MaterialSymbols.Outlined.Deselect
                                } else {
                                    MaterialSymbols.Outlined.Select_all
                                },
                                contentDescription = "全选",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = {
                                vm.archiveThreads(selectedIds.toList(), archive = state.filter != SmsFilter.ARCHIVED)
                                exitSelection()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Archive,
                                contentDescription = if (state.filter == SmsFilter.ARCHIVED) "取消归档" else "归档",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = { exitSelection() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "取消选择",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        IconButton(
                        onClick = {
                            if (state.searchActive) {
                                vm.setSearchQuery("")
                                vm.setSearchActive(false)
                            } else {
                                vm.setSearchActive(true)
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (state.searchActive) MaterialSymbols.Outlined.Close else MaterialSymbols.Outlined.Search,
                            contentDescription = if (state.searchActive) "关闭搜索" else "搜索",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = { vm.setShowSettings(true) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { composeTarget = ComposeTarget("", "") },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Edit,
                    contentDescription = "新建短信",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.tab == 0,
                    onClick = { vm.setTab(0) },
                    icon = { Icon(MaterialSymbols.Outlined.Chat, contentDescription = null) },
                    label = { Text("短信") }
                )
                NavigationBarItem(
                    selected = state.tab == 1,
                    onClick = {
                        vm.setTab(1)
                        vm.loadContacts()
                    },
                    icon = { Icon(MaterialSymbols.Outlined.Group, contentDescription = null) },
                    label = { Text("联系人") }
                )
            }
        }
    ) { innerPadding ->
        when (state.tab) {
            0 -> SmsListContent(
                state = state,
                onFilter = vm::setFilter,
                onCopyCode = { code -> copyCode(context, code) },
                onOpenOriginal = { msg ->
                    selectedThread = SmsThreadItem(msg, 0, 1)
                },
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelect = { t ->
                    val id = t.message.threadId
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onLongPress = { t ->
                    selectionMode = true
                    selectedIds = setOf(t.message.threadId)
                },
                onRequestPermission = {
                    permLauncher.launch(REQUIRED_PERMISSIONS)
                },
                onSetDefaultApp = onSetDefaultApp,
                onOpenThread = { selectedThread = it },
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            )
            1 -> ContactsScreen(
                contacts = state.contacts,
                onContactClick = { number -> composeTarget = ComposeTarget(number, "") },
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            )
        }
    }
}

@Composable
private fun SmsListContent(
    state: UiState,
    onFilter: (SmsFilter) -> Unit,
    onCopyCode: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onSetDefaultApp: () -> Unit,
    onOpenThread: (SmsThreadItem) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (SmsThreadItem) -> Unit = {},
    onLongPress: (SmsThreadItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!state.hasSmsPermission) {
            item {
                SetupCard(
                    title = "需要短信权限",
                    body = "授权后即可读取系统短信并自动提取验证码",
                    buttonText = "授权",
                    onClick = onRequestPermission
                )
            }
        } else if (!state.isDefaultSmsApp) {
            item {
                SetupCard(
                    title = "尚未设为默认短信应用",
                    body = "设为默认后才能读取短信数据库",
                    buttonText = "去设置",
                    onClick = onSetDefaultApp
                )
            }
        }

        // Filter chips on top; the smart banner sits below and only shows
        // on the 全部 (ALL) filter.
        item { FilterChipRow(selected = state.filter, onSelect = onFilter) }
        if (state.filter == SmsFilter.ALL) {
            item {
                SmartCodeCard(
                    message = state.latestCode,
                    allCodes = state.allCodeEntries,
                    onCopy = onCopyCode,
                    onOpenOriginal = onOpenOriginal
                )
            }
        }

        // 验证码/包裹 filters: the messages themselves become cards.
        if (state.filter == SmsFilter.CODE) {
            val codes = state.allCodeEntries
            if (codes.isEmpty()) {
                item { EmptyBox("暂无验证码短信") }
            } else {
                items(codes, key = { "${it.key}_${it.code}" }) { msg ->
                    CodeCardRow(
                        codeMsg = msg,
                        onCopy = onCopyCode,
                        onOpenOriginal = onOpenOriginal
                    )
                }
            }
        } else if (state.filter == SmsFilter.PACKAGE) {
            val pickups = state.allPickups
            if (pickups.isEmpty()) {
                item { EmptyBox("暂无取件码短信") }
            } else {
                items(pickups, key = { "${it.key}_${it.code}" }) { msg ->
                    CodeCardRow(
                        codeMsg = msg,
                        onCopy = onCopyCode,
                        onOpenOriginal = onOpenOriginal
                    )
                }
            }
        } else {
            val threads = state.visibleThreads
            if (threads.isEmpty()) {
                item { EmptyBox("暂无短信") }
            } else {
                items(threads, key = { it.message.threadId }) { thread ->
                    MessageItem(
                        thread = thread,
                        onClick = {
                            if (selectionMode) onToggleSelect(thread) else onOpenThread(thread)
                        },
                        onLongClick = { onLongPress(thread) },
                        selected = thread.message.threadId in selectedIds
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SetupCard(
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    isDefaultSmsApp: Boolean,
    showAdsInAll: Boolean,
    onSetDefaultApp: () -> Unit,
    onToggleAdsInAll: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "默认短信应用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isDefaultSmsApp) {
                Text(
                    text = "当前已是系统默认短信应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "设为默认短信应用后，本应用才能读取系统短信数据库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(onClick = onSetDefaultApp) {
                    Text("设为默认短信应用")
                }
            }

            HorizontalDivider()

            // 全部标签显示广告短信
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "全部标签显示广告短信",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "关闭后，广告短信只在“广告”标签中显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showAdsInAll,
                    onCheckedChange = onToggleAdsInAll
                )
            }
        }
    }
}

/**
 * Intent that opens the system default-SMS-app picker. Android 10+ uses the
 * RoleManager role request; pre-Q falls back to ACTION_CHANGE_DEFAULT.
 * Returns null when the platform exposes no entry point.
 */
private fun defaultSmsRequestIntent(context: Context): Intent? {
    val roleIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
    } else null
    if (roleIntent?.resolveActivity(context.packageManager) != null) return roleIntent
    val legacy = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    return legacy.takeIf { it.resolveActivity(context.packageManager) != null }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("sms_code", code))
    Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
}
