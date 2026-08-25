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
import android.net.Uri
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.toRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.librelab.messaging.R
import org.librelab.messaging.data.SmsFilter
import org.librelab.messaging.data.SimCard
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsThreadItem
import kotlinx.coroutines.launch
import org.librelab.messaging.data.SmsViewModel
import org.librelab.messaging.data.UiState
import org.librelab.messaging.ui.components.ConfirmDialog
import org.librelab.messaging.ui.components.CodeListPage
import org.librelab.messaging.ui.components.MultiSelectActions
import org.librelab.messaging.ui.components.ThreadListPage
import org.librelab.messaging.util.copyCodeToClipboard

private data class ComposeTarget(val number: String, val body: String)

/** Type-safe navigation routes (Navigation Compose 2.8+ serializable API). */
@kotlinx.serialization.Serializable
private object HomeRoute

@kotlinx.serialization.Serializable
private data class ThreadRoute(
    val threadId: Long,
    val address: String,
    val sender: String,
    val attachmentUri: String = "",
    val body: String = ""
)

@kotlinx.serialization.Serializable
private object SettingsRoute

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.POST_NOTIFICATIONS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: SmsViewModel = viewModel(),
    initialNumber: String = "",
    initialBody: String = "",
    initialAttachmentUri: String = "",
    shortcutTarget: Int = 0,
    initialThreadId: Long = -1L
) {
    val navController = rememberNavController()

    // Deep-link: launch straight into a new-conversation draft when the
    // activity was started with a number/body/attachment (share intent),
    // or into the matching filter tab via a launcher shortcut (2 = codes,
    // 3 = pickups), or a specific thread from a widget row tap (4).
    //
    // Keyed on the launch params (not Unit): onNewIntent re-composes
    // MainScreen with new values, and this must re-run to navigate.
    LaunchedEffect(shortcutTarget, initialThreadId) {
        if (shortcutTarget == 1 ||
            initialNumber.isNotBlank() || initialBody.isNotBlank() || initialAttachmentUri.isNotBlank()
        ) {
            navController.navigate(
                ThreadRoute(0L, initialNumber, "", initialAttachmentUri, initialBody)
            )
        } else if (shortcutTarget == 2) {
            vm.setFilter(SmsFilter.CODE)
        } else if (shortcutTarget == 3) {
            vm.setFilter(SmsFilter.PACKAGE)
        } else if (shortcutTarget == 4 && initialThreadId > 0) {
            navController.navigate(ThreadRoute(initialThreadId, "", ""))
        } else {
            // Plain launch (app icon, widget header / "more" link): come
            // back to the home conversation list. Without this, a warm
            // start while a thread is open would stay on that thread and
            // the widget "more" link would appear dead.
            navController.navigate(HomeRoute) {
                popUpTo(navController.graph.id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        // Opaque container: during a transition the sliding/fading pages
        // would otherwise reveal the white window background underneath.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // Standard Material 3 transitions (the same feel as system settings):
        // forward = shared-axis slide left + fade, back = previous page
        // scales up from 90% while the current page slides right and fades.
        // Predictive-back friendly: slideIntoContainer / slideOutOfContainer
        // tie the animation progress to the system back gesture.
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
        },
        popEnterTransition = {
            scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing))
        }
    ) {
        composable<HomeRoute> {
            HomeScreen(
                vm = vm,
                onOpenThread = { thread ->
                    navController.navigate(
                        ThreadRoute(thread.message.threadId, thread.message.address, thread.message.sender)
                    )
                },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onCompose = { number ->
                    // FAB: start a new conversation inside the existing detail
                    // screen (threadId=0 → contact picker at the top).
                    navController.navigate(ThreadRoute(0L, number, ""))
                }
            )
        }

        composable<ThreadRoute> { entry ->
            val route = entry.toRoute<ThreadRoute>()
            LaunchedEffect(route.threadId) { vm.openThread(route.threadId) }
            ThreadDetailScreen(
                threadId = route.threadId,
                address = route.address,
                sender = route.sender,
                initialAttachmentUri = route.attachmentUri,
                initialBody = route.body,
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute> {
            val s by vm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                isDefaultSmsApp = s.isDefaultSmsApp,
                showAdsInAll = s.showAdsInAll,
                notifyAds = s.notifyAds,
                autoCopyCode = s.autoCopyCode,
                antiBomb = s.antiBomb,
                defaultSubId = s.defaultSubId,
                simCards = s.simCards,
                onSetDefaultApp = rememberSetDefaultApp(vm),
                onToggleAdsInAll = { vm.setShowAdsInAll(it) },
                onToggleNotifyAds = { vm.setNotifyAds(it) },
                onToggleAutoCopyCode = { vm.setAutoCopyCode(it) },
                onToggleAntiBomb = { vm.setAntiBomb(it) },
                onSetDefaultSubId = { vm.setDefaultSubId(it) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Default-SMS-app picker: Role request on Android 10+, legacy
 * ACTION_CHANGE_DEFAULT intent below.
 */
@Composable
private fun rememberSetDefaultApp(vm: SmsViewModel): () -> Unit {
    val context = LocalContext.current
    val defaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh() }
    return {
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
}

/** Home tab: conversation list, chips, banner, multi-select, FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    vm: SmsViewModel,
    onOpenThread: (SmsThreadItem) -> Unit,
    onOpenSettings: () -> Unit,
    onCompose: (String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchFocus = remember { FocusRequester() }

    // Multi-select mode (long-press a conversation row to enter).
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Scrolling down hides the FAB + bottom bar; scrolling up brings them
    // back (animated). Only on the conversation list, not in selection mode.
    var hideChrome by remember { mutableStateOf(false) }
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
    val onSetDefaultApp = rememberSetDefaultApp(vm)

    // Bottom bar hides while content scrolls up, reappears on scroll down
    // (Material 3 official scroll behavior with fling/snap).
    val scrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()

    // First-run auto-request for the missing runtime permissions.
    LaunchedEffect(Unit) {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    // Batch-delete confirmation.
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_thread_title),
            body = stringResource(R.string.dialog_delete_thread_body, selectedIds.size),
            onConfirm = {
                vm.deleteThreads(selectedIds.toList())
                exitSelection()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selectionMode -> Text(
                            text = stringResource(R.string.selected_count, selectedIds.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        state.searchActive -> TextField(
                            value = state.searchQuery,
                            onValueChange = vm::setSearchQuery,
                            placeholder = { Text(stringResource(R.string.placeholder_search_sms)) },
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
                            text = stringResource(R.string.tab_sms),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        MultiSelectActions(
                            allSelected = allSelected,
                            onToggleAll = {
                                selectedIds = if (allSelected) emptySet() else visibleThreadIds.toSet()
                            },
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(48.dp),
                            onClose = { exitSelection() }
                        ) {
                            IconButton(
                                onClick = {
                                    vm.archiveThreads(selectedIds.toList(), archive = state.filter != SmsFilter.ARCHIVED)
                                    exitSelection()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Archive,
                                    contentDescription = if (state.filter == SmsFilter.ARCHIVED) {
                                        stringResource(R.string.action_unarchive)
                                    } else {
                                        stringResource(R.string.action_archive)
                                    },
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
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
                                contentDescription = if (state.searchActive) {
                                    stringResource(R.string.action_close_search)
                                } else {
                                    stringResource(R.string.action_search)
                                },
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Settings,
                                contentDescription = stringResource(R.string.action_settings),
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
        // FAB floats above the bottom menu (independent button, not inside
        // the bar); the bar itself still hides on scroll via the official
        // scroll behavior.
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onCompose("") },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_new_sms),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        bottomBar = {
            BottomAppBar(
                scrollBehavior = scrollBehavior
            ) {
                NavigationBarItem(
                    selected = state.tab == 0,
                    onClick = { vm.setTab(0) },
                    icon = { Icon(MaterialSymbols.Outlined.Chat, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_sms)) }
                )
                NavigationBarItem(
                    selected = state.tab == 1,
                    onClick = {
                        vm.setTab(1)
                        vm.loadContacts()
                    },
                    icon = { Icon(MaterialSymbols.Outlined.Group, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_contacts)) }
                )
            }
        },
    ) { innerPadding ->
        when (state.tab) {
            0 -> SmsListContent(
                state = state,
                onFilter = vm::setFilter,
                onCopyCode = { code -> copyCode(context, code) },
                onOpenOriginal = { msg ->
                    onOpenThread(SmsThreadItem(msg, 0, 1))
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
                onOpenThread = onOpenThread,
                onDisableAntiBomb = { vm.setAntiBomb(false) },
                onTempUnmuteCodes = { vm.temporarilyUnmuteCodes() },
                onExtendUnmute = { vm.extendUnmuteCodes() },
                onRestoreMute = { vm.restoreCodeMute() },
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            )
            1 -> ContactsScreen(
                contacts = state.contacts,
                onContactClick = { number -> onCompose(number) },
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
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
    onDisableAntiBomb: () -> Unit = {},
    onTempUnmuteCodes: () -> Unit = {},
    onExtendUnmute: () -> Unit = {},
    onRestoreMute: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val filters = remember { SmsFilter.entries.toList() }
    val pagerState = rememberPagerState(initialPage = filters.indexOf(state.filter)) { filters.size }

    // Anti-bomb countdown ticker: re-renders the card every second while a
    // temporary "accept codes" window is open.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.antiBombUntil) {
        while (state.antiBombUntil > 0L && System.currentTimeMillis() < state.antiBombUntil) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val antiBombRemainSec =
        ((state.antiBombUntil - nowTick) / 1000).coerceAtLeast(0).toInt()

    // Swipe left/right on the list switches the category chip. Only fire
    // after the user actually swiped — on first composition currentPage is
    // the initial page (possibly ALL) and would clobber a filter set by a
    // launcher shortcut / deep link.
    var userSwiped by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && userSwiped) {
            onFilter(filters[pagerState.currentPage])
        }
        if (pagerState.isScrollInProgress) userSwiped = true
    }

    // External filter changes (settings shortcut, deep link) move the pager
    // to the matching page.
    LaunchedEffect(state.filter) {
        val idx = filters.indexOf(state.filter)
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }

    Column(modifier = modifier) {
        if (!state.hasSmsPermission) {
            SetupCard(
                title = stringResource(R.string.setup_permission_title),
                body = stringResource(R.string.setup_permission_body),
                buttonText = stringResource(R.string.action_grant),
                onClick = onRequestPermission
            )
        } else if (!state.isDefaultSmsApp) {
            SetupCard(
                title = stringResource(R.string.setup_default_title),
                body = stringResource(R.string.setup_default_body),
                buttonText = stringResource(R.string.action_go_settings),
                onClick = onSetDefaultApp
            )
        }

        // 防验证码轰炸卡片:功能开启时告知用户,并提供关闭/临时接收入口。
        if (state.antiBomb) {
            AntiBombCard(
                remainSec = antiBombRemainSec,
                onDisable = onDisableAntiBomb,
                onTempUnmute = onTempUnmuteCodes,
                onExtend = onExtendUnmute,
                onRestore = onRestoreMute
            )
        }

        // Filter chips pinned on top; swiping the content below switches the
        // active category, and tapping a chip animates the pager to it.
        // The sliding indicator under the chips follows the drag so the
        // highlight moves with the finger.
        FilterChipRow(
            page = pagerState.currentPage,
            pageOffset = pagerState.currentPageOffsetFraction,
            onSelect = { filter ->
                onFilter(filter)
                scope.launch { pagerState.animateScrollToPage(filters.indexOf(filter)) }
            }
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val filter = filters[page]
            when (filter) {
                SmsFilter.ALL -> AllFilterPage(
                    state = state,
                    onCopyCode = onCopyCode,
                    onOpenOriginal = onOpenOriginal,
                    onOpenThread = onOpenThread,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress,
                )
                SmsFilter.CODE -> CodeFilterPage(
                    state = state,
                    onCopyCode = onCopyCode,
                    onOpenOriginal = onOpenOriginal,
                )
                SmsFilter.PACKAGE -> PackageFilterPage(
                    state = state,
                    onCopyCode = onCopyCode,
                    onOpenOriginal = onOpenOriginal,
                )
                SmsFilter.AD -> ThreadFilterPage(
                    filter = filter,
                    state = state,
                    onOpenThread = onOpenThread,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress,
                )
                SmsFilter.ARCHIVED -> ThreadFilterPage(
                    filter = filter,
                    state = state,
                    onOpenThread = onOpenThread,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@Composable
private fun AllFilterPage(
    state: UiState,
    onCopyCode: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit,
    onOpenThread: (SmsThreadItem) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (SmsThreadItem) -> Unit,
    onLongPress: (SmsThreadItem) -> Unit,
) {
    // ALL = the default filter; compute directly so the page renders the
    // instant it settles, without waiting for chip state to propagate.
    ThreadListPage(
        threads = state.threadsFor(SmsFilter.ALL),
        emptyText = stringResource(R.string.empty_sms),
        onOpenThread = onOpenThread,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress,
        header = {
            SmartCodeCard(
                message = state.latestCode,
                allCodes = state.allCodeEntries,
                onCopy = onCopyCode,
                onOpenOriginal = onOpenOriginal
            )
        }
    )
}

@Composable
private fun CodeFilterPage(
    state: UiState,
    onCopyCode: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit,
) {
    CodeListPage(
        entries = state.codesFor(),
        emptyText = stringResource(R.string.empty_codes),
        onCopy = onCopyCode,
        onOpenOriginal = onOpenOriginal
    )
}

@Composable
private fun PackageFilterPage(
    state: UiState,
    onCopyCode: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit,
) {
    CodeListPage(
        entries = state.pickupsFor(),
        emptyText = stringResource(R.string.empty_pickups),
        onCopy = onCopyCode,
        onOpenOriginal = onOpenOriginal
    )
}

@Composable
private fun ThreadFilterPage(
    filter: SmsFilter,
    state: UiState,
    onOpenThread: (SmsThreadItem) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (SmsThreadItem) -> Unit,
    onLongPress: (SmsThreadItem) -> Unit,
) {
    ThreadListPage(
        threads = state.threadsFor(filter),
        emptyText = stringResource(R.string.empty_sms),
        onOpenThread = onOpenThread,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress
    )
}

@Composable
private fun AntiBombCard(
    remainSec: Int,
    onDisable: () -> Unit,
    onTempUnmute: () -> Unit,
    onExtend: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "防验证码轰炸已开启",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "验证码已静音：不再弹通知，也不在首页显示。需要接收验证码时可临时放行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (remainSec > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "临时接收中，剩余 $remainSec 秒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestore) { Text("恢复静音") }
                    OutlinedButton(onClick = onExtend) { Text("+1 分钟") }
                }
                Spacer(Modifier.height(8.dp))
            }
            // 关闭功能 always visible, regardless of the countdown state.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDisable) { Text("关闭功能") }
                if (remainSec == 0) {
                    OutlinedButton(onClick = onTempUnmute) { Text("临时关闭 1 分钟") }
                }
            }
        }
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
    notifyAds: Boolean,
    autoCopyCode: Boolean,
    antiBomb: Boolean,
    defaultSubId: Int,
    simCards: List<SimCard>,
    onSetDefaultApp: () -> Unit,
    onToggleAdsInAll: (Boolean) -> Unit,
    onToggleNotifyAds: (Boolean) -> Unit,
    onToggleAutoCopyCode: (Boolean) -> Unit,
    onToggleAntiBomb: (Boolean) -> Unit,
    onSetDefaultSubId: (Int) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = stringResource(R.string.action_back),
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
                text = stringResource(R.string.settings_default_app),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isDefaultSmsApp) {
                Text(
                    text = stringResource(R.string.settings_default_app_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_default_app_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(onClick = onSetDefaultApp) {
                    Text(stringResource(R.string.action_set_default_app))
                }
            }

            HorizontalDivider()

            // 默认电话卡(发送短信用哪张卡;无 = 系统默认)
            var simMenuOpen by remember { mutableStateOf(false) }
            val defaultCard = simCards.firstOrNull { it.subId == defaultSubId }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_sim),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.settings_sim_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    TextButton(onClick = { simMenuOpen = true }) {
                        Text(
                            text = defaultCard?.name ?: stringResource(R.string.settings_sim_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = simMenuOpen,
                        onDismissRequest = { simMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_sim_none)) },
                            onClick = {
                                simMenuOpen = false
                                onSetDefaultSubId(0)
                            }
                        )
                        simCards.forEach { card ->
                            DropdownMenuItem(
                                text = { Text(card.name) },
                                onClick = {
                                    simMenuOpen = false
                                    onSetDefaultSubId(card.subId)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // 全部标签显示广告短信
            SettingsSwitchRow(
                title = stringResource(R.string.settings_ads_in_all),
                hint = stringResource(R.string.settings_ads_in_all_hint),
                checked = showAdsInAll,
                onCheckedChange = onToggleAdsInAll
            )

            HorizontalDivider()

            // 广告短信静音
            SettingsSwitchRow(
                title = stringResource(R.string.settings_mute_ads),
                hint = stringResource(R.string.settings_mute_ads_hint),
                checked = !notifyAds,
                onCheckedChange = { onToggleNotifyAds(!it) }
            )

            HorizontalDivider()

            // 验证码自动复制 (disabled while anti-bombing is on)
            SettingsSwitchRow(
                title = "验证码自动复制",
                hint = if (antiBomb) {
                    "防轰炸开启期间已停用"
                } else {
                    "收到验证码短信时，自动把验证码复制到剪贴板"
                },
                checked = autoCopyCode,
                onCheckedChange = onToggleAutoCopyCode,
                enabled = !antiBomb
            )

            HorizontalDivider()

            // 防验证码轰炸
            SettingsSwitchRow(
                title = "防验证码轰炸",
                hint = "静音验证码通知，验证码不在首页显示，防止轰炸骚扰",
                checked = antiBomb,
                onCheckedChange = onToggleAntiBomb
            )
        }
    }
}

/** One settings row: title + hint on the left, a switch on the right. */
@Composable
private fun SettingsSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
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

private fun copyCode(context: Context, code: String) = copyCodeToClipboard(context, code)
