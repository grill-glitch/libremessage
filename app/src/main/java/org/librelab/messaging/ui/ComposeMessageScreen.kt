package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.librelab.messaging.R

/** New-message screen: entry from the FAB or from an smsto: SENDTO intent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMessageScreen(
    initialNumber: String,
    initialBody: String,
    onBack: () -> Unit
) {
    var number by remember { mutableStateOf(initialNumber) }
    var body by remember { mutableStateOf(initialBody) }
    var sending by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_new_sms), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = stringResource(R.string.action_back))
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
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text(stringResource(R.string.recipient)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.sms_body)) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Button(
                onClick = {
                    if (number.isBlank()) {
                        Toast.makeText(context, R.string.toast_enter_recipient, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(context, R.string.toast_no_send_permission, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    sending = true
                    try {
                        SmsManager.getDefault().sendTextMessage(number, null, body, null, null)
                        Toast.makeText(context, R.string.toast_sms_sent, Toast.LENGTH_SHORT).show()
                        onBack()
                    } catch (e: Exception) {
                        sending = false
                        Toast.makeText(context, context.getString(R.string.toast_send_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (sending) stringResource(R.string.action_sending) else stringResource(R.string.action_send))
            }
        }
    }
}
