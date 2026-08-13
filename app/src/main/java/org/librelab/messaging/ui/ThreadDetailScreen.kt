package org.librelab.messaging.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.os.Build
import android.os.Environment
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Call
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Sim_card
import com.composables.icons.materialsymbols.outlined.Done_all
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.File_open
import com.composables.icons.materialsymbols.outlined.Image
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Send
import org.librelab.messaging.R
import org.librelab.messaging.SmsSentReceiver
import org.librelab.messaging.data.ContactInfo
import org.librelab.messaging.data.PendingAttachment
import org.librelab.messaging.data.SendStatus
import org.librelab.messaging.data.SimCard
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsViewModel
import org.librelab.messaging.ui.theme.AvatarColor
import org.librelab.messaging.ui.theme.avatarColorFor
import org.librelab.messaging.util.MmsSender
import org.librelab.messaging.util.OutboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation detail: received bubbles on the left, sent on the right,
 * auto-scroll to the newest message, bottom input bar sends via SmsManager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(
    threadId: Long,
    address: String,
    sender: String,
    vm: SmsViewModel,
    onBack: () -> Unit,
    initialAttachmentUri: String = "",
    initialBody: String = ""
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val messages = state.threadMessages
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var viewerUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // SIM chosen for this send (0 = follow the settings default / auto).
    var pickedSubId by remember { mutableStateOf(0) }
    // Effective SIM: per-send pick wins, else the settings default (0 = auto).
    val effectiveSubId = if (pickedSubId > 0) pickedSubId else state.defaultSubId

    // New-thread mode: the FAB opens this screen with threadId=0. Instead of
    // a conversation, show a recipient field (auto-focused, keyboard pops)
    // plus a contact list filtered by the typed query (A-Z when empty).
    // After the first send we flip to the real thread id so the outgoing
    // message appears in the list.
    var currentThreadId by remember { mutableStateOf(threadId) }
    val isNewThread = currentThreadId == 0L
    var newNumber by remember { mutableStateOf(if (isNewThread) address else "") }
    val numberFocus = remember { FocusRequester() }
    val collator = remember { java.text.Collator.getInstance(java.util.Locale.CHINA) }
    val contacts = state.contacts
    LaunchedEffect(Unit) {
        if (isNewThread) {
            vm.loadContacts()
            numberFocus.requestFocus()
            // Share intents: prefill the text body and/or attachment.
            if (initialBody.isNotBlank()) input = initialBody
            if (initialAttachmentUri.isNotBlank()) {
                runCatching {
                    val uri = android.net.Uri.parse(initialAttachmentUri)
                    val name = uri.getQueryParameter("display_name")
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "file"
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val file = File(context.cacheDir, "pending_mms_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    pendingAttachment = PendingAttachment(
                        file, name, mime,
                        isImage = mime.startsWith("image/")
                    )
                }.onFailure {
                    Toast.makeText(context, R.string.toast_read_image_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val filteredContacts = remember(newNumber, contacts) {
        val q = newNumber.trim()
        if (q.isEmpty()) {
            contacts.sortedWith(compareBy(collator) { it.name })
        } else {
            contacts.filter { c -> (c.number?.contains(q) == true) || c.name.contains(q) }
                .sortedWith(compareBy(collator) { it.name })
        }
    }
    // Messages are sent to the chosen number in new-thread mode.
    val targetAddress = if (isNewThread) newNumber else address

    // Multi-select (entered from the long-press menu 多选 item).
    var multiSelect by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    // Top-bar "more" overflow menu.
    var moreMenuOpen by remember { mutableStateOf(false) }
    val selectedMessages = messages.filter { it.key in selectedKeys }
    val allMessageKeys = messages.map { it.key }
    val allSelected = allMessageKeys.isNotEmpty() && selectedKeys.containsAll(allMessageKeys)
    fun exitMultiSelect() {
        multiSelect = false
        selectedKeys = emptySet()
        confirmBatchDelete = false
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val file = File(context.cacheDir, "pending_mms_${System.currentTimeMillis()}.jpg")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                pendingAttachment = PendingAttachment(file, "IMG.jpg", "image/jpeg", isImage = true)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.toast_read_image_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val name = uri.getQueryParameter("display_name")
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "file"
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = name.substringAfterLast('.', "").lowercase()
                val file = File(context.cacheDir, "pending_mms_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                pendingAttachment = PendingAttachment(file, name, mime, isImage = false)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.toast_read_image_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // System back is managed by Navigation Compose (predictive back gesture);
    // the top-bar arrow uses onBack. No BackHandler here — it would break
    // the framework-driven predictive back animation.

    // reverseLayout: index 0 (newest) renders at the bottom, right above the
    // input bar; the default position already shows the newest messages, so
    // no scroll is needed on open or when new messages arrive.

    // When the user starts typing, keep the newest message at the bottom —
    // but ONLY if they were already there (scrolling up to read history must
    // keep its place — no yanking back to the newest message).
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(inputFocused) {
        if (inputFocused && messages.isNotEmpty()) {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            if (firstVisible == 0) {
                listState.scrollToItem(0)
            }
        }
    }

    Scaffold(
        // imePadding: input bar AND message list move together with the
        // keyboard (the list's layout height shrinks in sync). reverseLayout
        // parks the newest message right above the input bar; the whole
        // conversation (including old history) stays browsable.
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    if (multiSelect) {
                        Text(
                            text = stringResource(R.string.selected_count, selectedKeys.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else if (isNewThread) {
                        // Recipient field lives in the top bar itself (the
                        // "新短信" title spot); the contact list renders right
                        // below the bar.
                        OutlinedTextField(
                            value = newNumber,
                            onValueChange = { newNumber = it },
                            placeholder = { Text(stringResource(R.string.title_new_sms)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(numberFocus)
                        )
                    } else {
                        // After the first send from a share intent the thread
                        // id flips to the real one; fall back through sender →
                        // address → the number the user actually typed. When
                        // opened from a widget we only have the thread id, so
                        // infer the contact from the loaded messages.
                        val inferredContact = messages.firstOrNull { !it.isSent }
                            ?.let { it.contactName ?: it.sender.ifBlank { it.address } }
                            .orEmpty()
                        val titleText = if (isNewThread) newNumber
                        else sender.ifBlank { address }.ifBlank { inferredContact }.ifBlank { newNumber }
                        val ac = avatarColorFor(titleText)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(
                                initial = titleText.firstOrNull()?.toString() ?: "?",
                                size = 36,
                                container = ac.container,
                                content = ac.content
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (multiSelect) exitMultiSelect() else onBack()
                        }
                    ) {
                        Icon(
                            imageVector = if (multiSelect) MaterialSymbols.Outlined.Close else MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = if (multiSelect) {
                                stringResource(R.string.action_cancel_selection)
                            } else {
                                stringResource(R.string.action_back)
                            },
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (multiSelect) {
                        IconButton(
                            onClick = {
                                selectedKeys = if (allSelected) emptySet() else allMessageKeys.toSet()
                            }
                        ) {
                            Icon(
                                imageVector = if (allSelected) MaterialSymbols.Outlined.Deselect else MaterialSymbols.Outlined.Select_all,
                                contentDescription = stringResource(R.string.action_select_all),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                val text = selectedMessages.joinToString("\n") { it.body }
                                if (text.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_copied_count, selectedMessages.size),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                exitMultiSelect()
                            }
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Content_copy,
                                contentDescription = stringResource(R.string.action_copy),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                val text = selectedMessages.joinToString("\n") { it.body }
                                if (text.isNotBlank()) shareText(context, text)
                                exitMultiSelect()
                            }
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { confirmBatchDelete = true }
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val dial = if (isNewThread) newNumber else address
                                if (dial.isNotBlank()) {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(dial)}"))
                                    )
                                }
                            }
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Call,
                                contentDescription = stringResource(R.string.action_dial),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box {
                            IconButton(onClick = { moreMenuOpen = true }) {
                                Icon(
                                    MaterialSymbols.Outlined.More_vert,
                                    contentDescription = stringResource(R.string.action_more),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_add_contact)) },
                                    onClick = {
                                        moreMenuOpen = false
                                        val dial = if (isNewThread) newNumber else address
                                        if (dial.isNotBlank()) addToContacts(context, dial)
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.action_block_number),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        moreMenuOpen = false
                                        val dial = if (isNewThread) newNumber else address
                                        if (dial.isNotBlank()) blockNumber(context, dial)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            MessageInputBar(
                value = input,
                onValueChange = { input = it },
                onFocusChanged = { inputFocused = it },
                pendingAttachment = pendingAttachment,
                onAttachImage = { imagePicker.launch("image/*") },
                onAttachFile = { filePicker.launch("*/*") },
                onRemoveAttachment = { pendingAttachment = null },
                simCards = state.simCards,
                defaultSubId = state.defaultSubId,
                pickedSubId = pickedSubId,
                onPickSim = { pickedSubId = it },
                onSetDefaultSubId = { vm.setDefaultSubId(it) },
                onSend = { sendSubId ->
                    val text = input.trim()
                    if (text.isEmpty() && pendingAttachment == null) return@MessageInputBar
                    if (targetAddress.isBlank()) {
                        Toast.makeText(context, R.string.toast_enter_recipient, Toast.LENGTH_SHORT).show()
                        return@MessageInputBar
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(context, R.string.toast_no_send_permission, Toast.LENGTH_SHORT).show()
                        return@MessageInputBar
                    }
                    // Sub-id for this send: an explicit pick wins, else the
                    // settings default (0 = system auto).
                    val useSubId = if (sendSubId > 0) sendSubId else effectiveSubId
                    val attachment = pendingAttachment
                    val ok: Boolean
                    if (attachment != null) {
                        // MMS: keep our own outbox copy; the sent callback flips
                        // it to FAILED if the stack reports an error. Runs off
                        // the UI thread because MMS data may need enabling first.
                        val outboxId = -System.currentTimeMillis()
                        val sentIntent = PendingIntent.getBroadcast(
                            context,
                            outboxId.toInt(),
                            Intent(SmsSentReceiver.ACTION_SMS_SENT)
                                .setPackage(context.packageName)
                                .putExtra(SmsSentReceiver.EXTRA_TYPE, "mms")
                                .putExtra(SmsSentReceiver.EXTRA_RECORD_ID, outboxId),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        val scope2 = scope
                        scope2.launch(Dispatchers.IO) {
                            val sent = MmsSender.send(
                                context, targetAddress, text, attachment, outboxId, sentIntent, useSubId
                            ) != null
                            withContext(Dispatchers.Main) {
                                if (sent) {
                                    pendingAttachment = null
                                    input = ""
                                    if (currentThreadId == 0L) {
                                        val realId = Telephony.Threads.getOrCreateThreadId(context, targetAddress)
                                        currentThreadId = realId
                                        vm.openThread(realId)
                                    }
                                    vm.refresh()
                                } else {
                                    Toast.makeText(
                                        context,
                                        R.string.toast_mms_data_required,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        return@MessageInputBar
                    } else {
                        // SMS: insert our own outbox row (the system does not write
                        // one on this ROM), then flip its type via the sent callback.
                        val recordId = vm.insertPendingSms(targetAddress, text)
                        val sentIntent = PendingIntent.getBroadcast(
                            context,
                            recordId.toInt(),
                            Intent(SmsSentReceiver.ACTION_SMS_SENT)
                                .setPackage(context.packageName)
                                .putExtra(SmsSentReceiver.EXTRA_TYPE, "sms")
                                .putExtra(SmsSentReceiver.EXTRA_RECORD_ID, recordId),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        ok = try {
                            val smsManager = if (useSubId > 0) {
                                SmsManager.getSmsManagerForSubscriptionId(useSubId)
                            } else {
                                SmsManager.getDefault()
                            }
                            smsManager.sendTextMessage(targetAddress, null, text, sentIntent, null)
                            true
                        } catch (e: Exception) {
                            vm.markSmsFailed(recordId)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_send_failed, e.message),
                                Toast.LENGTH_SHORT
                            ).show()
                            false
                        }
                    }
                    if (ok) {
                        pendingAttachment = null
                        input = ""
                        if (currentThreadId == 0L) {
                            // First send from new-thread mode: resolve the
                            // real thread and open it (updates selectedThreadId
                            // so the ContentObserver refresh reloads this
                            // thread instead of the empty thread-0 list).
                            val realId = Telephony.Threads.getOrCreateThreadId(context, targetAddress)
                            currentThreadId = realId
                            vm.openThread(realId)
                        }
                        vm.refresh() // ContentObserver also fires; refresh re-loads the thread
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isNewThread) {
                // Contact picker below the top bar (the recipient field
                // itself lives in the bar); filters by the typed query and
                // shows all contacts A-Z when empty. The whole picker sits in
                // one rounded-rectangle surface.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        if (filteredContacts.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.empty_contact_match),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 14.dp)
                                )
                            }
                        } else {
                            items(filteredContacts, key = { (it.number ?: "") + it.name }) { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { contact.number?.let { newNumber = it } }
                                        .padding(vertical = 12.dp, horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                contact.number?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            // Newest message hugs the input bar: reversed data +
            // reverseLayout (index 0 = newest, rendered at the bottom).
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages.asReversed(), key = { it.key }) { message ->
                MessageBubble(
                    message = message,
                    myInitial = stringResource(R.string.me),
                    multiSelect = multiSelect,
                    selected = message.key in selectedKeys,
                    onToggleSelect = { key ->
                        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                    },
                    onStartMultiSelect = { msg ->
                        multiSelect = true
                        selectedKeys = setOf(msg.key)
                    },
                    onOpenImage = { viewerUri = it },
                    onSaveImage = { uri ->
                        val saved = saveImageToGallery(context, uri)
                        Toast.makeText(
                            context,
                            if (saved) R.string.toast_saved_to_gallery else R.string.toast_save_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDelete = { msg ->
                        deleteMessage(context, msg)
                        vm.refresh()
                    }
                )
            }
            }
        }
    }

    // Batch-delete confirmation (multi-select mode).
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_message_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_message_body, selectedMessages.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedMessages.forEach { deleteMessage(context, it) }
                        vm.refresh()
                        exitMultiSelect()
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Full-screen image viewer: pinch/double-tap to zoom, single tap (no
    // ripple) to dismiss.
    viewerUri?.let { uri ->
        Dialog(onDismissRequest = { viewerUri = null }) {
            var scale by remember { mutableFloatStateOf(1f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { viewerUri = null },
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                        }
                    }
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                )
            }
        }
    }
}

/** Delete a message: system SMS/MMS rows, or the local outbox record. */
private fun deleteMessage(context: Context, message: SmsMessage) {
    try {
        if (message.id < 0) {
            // Outbox record (local JSON), negative synthetic id.
            OutboxStore.remove(context, message.id)
        } else if (message.isMms) {
            context.contentResolver.delete(
                android.net.Uri.parse("content://mms/${message.id}"), null, null
            )
        } else {
            context.contentResolver.delete(
                android.net.Uri.parse("content://sms/${message.id}"), null, null
            )
        }
    } catch (e: Exception) {
        Toast.makeText(context, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
    }
}

/** Share a message (text, or the first image for MMS) via the system chooser. */
private fun shareMessage(context: Context, message: SmsMessage) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        if (message.imageUris.isNotEmpty()) {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, message.imageUris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message.body)
        }
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
}

/** Share plain text via the system chooser (used by multi-select 分享). */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
}

/**
 * Open the system contact editor pre-filled with this number, so the user
 * can save the sender as a contact (or add the number to an existing one).
 */
private fun addToContacts(context: Context, number: String) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, number)
        putExtra(
            ContactsContract.Intents.Insert.PHONE_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        )
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, R.string.toast_no_contact_app, Toast.LENGTH_SHORT).show()
        }
}

/**
 * Block a number. There is no public API to write the system block list
 * (insertBlockedNumber is @SystemApi behind a signature permission), so we
 * open the platform blocked-numbers settings — the same flow third-party
 * SMS apps (QKSMS, Simple SMS…) use. ACTION_BLOCKED_NUMBERS_SETTINGS is a
 * hidden constant; its stable action string is used directly.
 */
private fun blockNumber(context: Context, number: String) {
    val canBlock = runCatching {
        BlockedNumberContract.canCurrentUserBlockNumbers(context)
    }.getOrDefault(false)
    if (!canBlock) {
        Toast.makeText(context, R.string.block_number_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    val settingsIntent = Intent("android.settings.BLOCKED_NUMBER_LIST").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(settingsIntent) }
        .onFailure {
            Toast.makeText(context, R.string.block_number_unavailable, Toast.LENGTH_SHORT).show()
        }
}

/** Copy an MMS part into the gallery (Pictures/Librelab). */
private fun saveImageToGallery(context: Context, uri: android.net.Uri): Boolean = try {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
    val name = "MMS_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Librelab")
        } else {
            put(
                MediaStore.Images.Media.DATA,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    .absolutePath + "/Librelab/$name"
            )
        }
    }
    val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val out = resolver.openOutputStream(outUri) ?: return false
    out.use { it.write(bytes) }
    true
} catch (e: Exception) {
    false
}

/** 32dp round initial avatar next to a bubble (36dp variant used in the top bar). */
@Composable
private fun InitialAvatar(
    initial: String,
    size: Int,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(size.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = content
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: SmsMessage,
    myInitial: String,
    multiSelect: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onStartMultiSelect: (SmsMessage) -> Unit = {},
    onOpenImage: (android.net.Uri) -> Unit,
    onSaveImage: (android.net.Uri) -> Unit,
    onDelete: (SmsMessage) -> Unit
) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.date))
    val hasImages = message.imageUris.isNotEmpty() || message.attachmentName != null
    var confirmDelete by remember { mutableStateOf(false) }
    if (message.isSent) {
        // Sent — right aligned; images sit outside the bubble, bubble holds text+time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(horizontalAlignment = Alignment.End) {
                // File attachment: show a file card (name + icon) instead of
                // the image viewer path.
                if (message.attachmentName != null) {
                    FileAttachmentCard(
                        name = message.attachmentName,
                        message = message,
                        onRequestDelete = { confirmDelete = true },
                        multiSelect = multiSelect,
                        selected = selected,
                        onToggle = { onToggleSelect(message.key) }
                    )
                }
                message.imageUris.forEach { uri ->
                    MmsImage(
                        uri,
                        message,
                        onOpenImage,
                        onSaveImage,
                        multiSelect = multiSelect,
                        selected = selected,
                        onToggle = { onToggleSelect(message.key) },
                        onStartMultiSelect = { onStartMultiSelect(message) },
                        onRequestDelete = { confirmDelete = true }
                    )
                }
                if (message.body.isNotBlank()) {
                    BubbleContent(
                        message = message,
                        time = time,
                        sendStatus = message.sendStatus,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        selected = selected,
                        multiSelect = multiSelect,
                        onToggle = { onToggleSelect(message.key) },
                        onStartMultiSelect = { onStartMultiSelect(message) },
                        onRequestDelete = { confirmDelete = true }
                    )
                } else if (hasImages) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SendStatusIcon(message.sendStatus)
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            InitialAvatar(
                initial = myInitial,
                size = 32,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    } else {
        // Received — left aligned; images outside the bubble
        val avatarColor = avatarColorFor(message.contactName ?: message.address)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            InitialAvatar(
                initial = message.sender.firstOrNull()?.toString() ?: "?",
                size = 32,
                container = avatarColor.container,
                content = avatarColor.content
            )
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                if (message.attachmentName != null) {
                    FileAttachmentCard(
                        name = message.attachmentName,
                        message = message,
                        onRequestDelete = { confirmDelete = true },
                        multiSelect = multiSelect,
                        selected = selected,
                        onToggle = { onToggleSelect(message.key) }
                    )
                }
                message.imageUris.forEach { uri ->
                    MmsImage(
                        uri,
                        message,
                        onOpenImage,
                        onSaveImage,
                        multiSelect = multiSelect,
                        selected = selected,
                        onToggle = { onToggleSelect(message.key) },
                        onStartMultiSelect = { onStartMultiSelect(message) },
                        onRequestDelete = { confirmDelete = true }
                    )
                }
                if (message.body.isNotBlank()) {
                    BubbleContent(
                        message = message,
                        time = time,
                        sendStatus = SendStatus.NONE,
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        selected = selected,
                        multiSelect = multiSelect,
                        onToggle = { onToggleSelect(message.key) },
                        onStartMultiSelect = { onStartMultiSelect(message) },
                        onRequestDelete = { confirmDelete = true }
                    )
                } else if (hasImages) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SendStatusIcon(message.sendStatus)
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_single_title)) },
            text = { Text(stringResource(R.string.dialog_delete_irreversible)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    onDelete(message)
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** MMS image, rendered outside the bubble with a soft corner radius.
 * Tap to view full-screen; long-press pops the action menu at the touch.
 * In multi-select mode the tap toggles selection instead of opening the
 * viewer (and the border highlights the selected state). */
@Composable
private fun MmsImage(
    uri: android.net.Uri,
    message: SmsMessage,
    onOpenImage: (android.net.Uri) -> Unit,
    onSaveImage: (android.net.Uri) -> Unit,
    multiSelect: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
    onStartMultiSelect: () -> Unit = {},
    onRequestDelete: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box {
        AsyncImage(
            model = uri,
            contentDescription = stringResource(R.string.image),
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .sizeIn(maxWidth = 220.dp, maxHeight = 280.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (multiSelect) onToggle() else onOpenImage(uri) },
                        onLongPress = { if (multiSelect) onToggle() else menuAt = it }
                    )
                }
                .then(
                    if (selected) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    } else Modifier
                )
        )
        menuAt?.let {
            MessageActionMenu(
                offset = it,
                message = message,
                hasImages = true,
                onDismiss = { menuAt = null },
                onSaveImage = onSaveImage,
                onStartMultiSelect = onStartMultiSelect,
                onRequestDelete = onRequestDelete
            )
        }
    }
}

/** File attachment bubble card: icon + file name, tap to open. */
@Composable
private fun FileAttachmentCard(
    name: String,
    message: SmsMessage,
    onRequestDelete: () -> Unit,
    multiSelect: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {}
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier
            .padding(bottom = 6.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (multiSelect) onToggle() else menuAt = Offset(0f, 0f) },
                    onLongPress = { if (multiSelect) onToggle() else menuAt = it }
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.File_open,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
    menuAt?.let {
        MessageActionMenu(
            offset = it,
            message = message,
            hasImages = false,
            onDismiss = { menuAt = null },
            onSaveImage = {},
            onStartMultiSelect = { onToggle() },
            onRequestDelete = onRequestDelete
        )
    }
}

@Composable
private fun BubbleContent(
    message: SmsMessage,
    time: String,
    sendStatus: SendStatus,
    shape: RoundedCornerShape,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    selected: Boolean = false,
    multiSelect: Boolean = false,
    onToggle: () -> Unit = {},
    onStartMultiSelect: () -> Unit = {},
    onRequestDelete: () -> Unit
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box {
        Surface(
            shape = shape,
            color = container,
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (multiSelect) onToggle() },
                    onLongPress = { if (multiSelect) onToggle() else menuAt = it }
                )
            }
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content
                )
                Spacer(Modifier.size(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    SendStatusIcon(sendStatus)
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        menuAt?.let {
            MessageActionMenu(
                offset = it,
                message = message,
                hasImages = false,
                onDismiss = { menuAt = null },
                onSaveImage = {},
                onStartMultiSelect = onStartMultiSelect,
                onRequestDelete = onRequestDelete
            )
        }
    }
}

/** Compact action menu popped at the long-press position; tap outside dismisses. */
@Composable
private fun MessageActionMenu(
    offset: Offset,
    message: SmsMessage,
    hasImages: Boolean,
    onDismiss: () -> Unit,
    onSaveImage: (android.net.Uri) -> Unit,
    onStartMultiSelect: () -> Unit = {},
    onRequestDelete: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Popup(
        offset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
        onDismissRequest = onDismiss
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_multi_select)) },
                onClick = {
                    onDismiss()
                    onStartMultiSelect()
                }
            )
            if (message.body.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        clipboard.setText(AnnotatedString(message.body))
                        Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                onClick = {
                    shareMessage(context, message)
                    onDismiss()
                }
            )
            if (hasImages) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_save_image)) },
                    onClick = {
                        message.imageUris.firstOrNull()?.let(onSaveImage)
                        onDismiss()
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    onDismiss()
                    onRequestDelete()
                }
            )
        }
    }
}

/** Delivery state icon next to the time of an outgoing bubble. */
@Composable
private fun SendStatusIcon(status: SendStatus) {
    val icon = when (status) {
        SendStatus.SENDING -> MaterialSymbols.Outlined.Check
        SendStatus.SENT -> MaterialSymbols.Outlined.Done_all
        SendStatus.FAILED -> MaterialSymbols.Outlined.Error
        SendStatus.NONE -> return
    }
    val tint = when (status) {
        SendStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(14.dp)
            .padding(end = 4.dp)
    )
}

@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    pendingAttachment: PendingAttachment?,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: (Int) -> Unit,
    simCards: List<SimCard> = emptyList(),
    defaultSubId: Int = 0,
    pickedSubId: Int = 0,
    onPickSim: (Int) -> Unit = {},
    onSetDefaultSubId: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var attachMenuOpen by remember { mutableStateOf(false) }
    var simMenuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // The whole Scaffold rides above the keyboard (Scaffold imePadding),
        // so this bar needs no extra padding.
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Pending attachment preview
            if (pendingAttachment != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pendingAttachment.isImage) {
                        AsyncImage(
                            model = pendingAttachment.file,
                            contentDescription = stringResource(R.string.pending_image),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        // File attachment: icon + name instead of a thumbnail.
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.File_open,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = pendingAttachment.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRemoveAttachment) {
                        Icon(
                            MaterialSymbols.Outlined.Close,
                            contentDescription = stringResource(R.string.action_remove_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(
                            MaterialSymbols.Outlined.Attach_file,
                            contentDescription = stringResource(R.string.action_attach),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_attach_image)) },
                            leadingIcon = {
                                Icon(MaterialSymbols.Outlined.Image, contentDescription = null)
                            },
                            onClick = {
                                attachMenuOpen = false
                                onAttachImage()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_attach_file)) },
                            leadingIcon = {
                                Icon(MaterialSymbols.Outlined.File_open, contentDescription = null)
                            },
                            onClick = {
                                attachMenuOpen = false
                                onAttachFile()
                            }
                        )
                    }
                }

                // SIM picker: tap opens the per-send SIM chooser. Each card
                // has a "set as default" checkbox — checking it persists the
                // card as the settings default (same as the settings page).
                Box {
                    IconButton(onClick = { simMenuOpen = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Sim_card,
                            contentDescription = stringResource(R.string.sim_pick_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = simMenuOpen,
                        onDismissRequest = { simMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sim_auto)) },
                            leadingIcon = {
                                Icon(MaterialSymbols.Outlined.Sim_card, contentDescription = null)
                            },
                            onClick = {
                                simMenuOpen = false
                                onPickSim(0)
                                onSend(0)
                            }
                        )
                        simCards.forEach { card ->
                            val isDefault = card.subId == defaultSubId
                            DropdownMenuItem(
                                text = { Text(card.name) },
                                leadingIcon = {
                                    Icon(MaterialSymbols.Outlined.Sim_card, contentDescription = null)
                                },
                                // "Set as default" checkbox — its own click
                                // target, separate from the item's onClick.
                                trailingIcon = {
                                    Checkbox(
                                        checked = isDefault,
                                        onCheckedChange = { checked ->
                                            simMenuOpen = false
                                            onSetDefaultSubId(if (checked) card.subId else 0)
                                        }
                                    )
                                },
                                onClick = {
                                    simMenuOpen = false
                                    onPickSim(card.subId)
                                    onSend(card.subId)
                                }
                            )
                        }
                    }
                }

                // Pill-shaped input. Long-press opens the SIM chooser when a
                // default card is configured (so the user can override it for
                // this send); with no default the send button asks instead.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    if (simCards.size > 1) simMenuOpen = true
                                }
                            )
                        }
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.placeholder_input_sms),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 14.dp)
                                .align(Alignment.CenterStart)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .heightIn(max = 96.dp)
                            .onFocusChanged { onFocusChanged(it.isFocused) },
                        maxLines = 4
                    )
                }

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    // Ask which SIM to use first when none is configured:
                    // tap sends directly once a default exists (or only one
                    // SIM is present).
                    onClick = {
                        val needsPick = simCards.isNotEmpty() &&
                            defaultSubId == 0 && pickedSubId == 0
                        if (needsPick) simMenuOpen = true else onSend(pickedSubId)
                    },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Send,
                        contentDescription = stringResource(R.string.action_send),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
