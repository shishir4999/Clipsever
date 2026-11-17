package app.clipsaver

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val updating by Engine.updating.collectAsStateWithLifecycle()
    val updateResult by Engine.updateResult.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = { Engine.requestUpdate(context) },
                        enabled = engine is EngineState.Ready && !updating,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.engine_update),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LinkInput(
                    input = state.input,
                    busy = state.loading,
                    onChange = vm::onInputChange,
                    onPaste = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            focus.clearFocus()
                            vm.onPaste(pasted.trim())
                        }
                    },
                    onGo = {
                        focus.clearFocus()
                        vm.fetch()
                    },
                )
            }

            item { EngineStatus(engine, updating, updateResult) }

            if (state.loading) {
                item { BusyRow(stringResource(R.string.fetching)) }
            }

            val error = state.error
            if (error != null) {
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.downloads),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (jobs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_downloads),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCancel = { vm.cancel(job.id) },
                        onOpen = { openFile(context, job.savedUri) },
                        onRemove = { vm.remove(job.id) },
                    )
                }
            }
        }
    }

    val details = state.details
    if (details != null) {
        FormatSheet(
            details = details,
            onDismiss = vm::dismissSheet,
            onPick = { option ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.download(option)
            },
        )
    }
}

@Composable
private fun LinkInput(
    input: String,
    busy: Boolean,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onGo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.hint_link)) },
            singleLine = true,
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_paste))
            }
            Button(onClick = onGo, enabled = !busy, modifier = Modifier.weight(2f)) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_get))
            }
        }
    }
}

@Composable
private fun EngineStatus(engine: EngineState, updating: Boolean, updateResult: String?) {
    when {
        engine is EngineState.Loading -> BusyRow(stringResource(R.string.engine_loading))
        engine is EngineState.Failed -> Text(
            stringResource(R.string.engine_failed, engine.reason),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        updating -> BusyRow(stringResource(R.string.engine_updating))
        updateResult != null -> Text(
            updateResult,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun openFile(context: Context, savedUri: String?) {
    if (savedUri == null) return
    val uri = Uri.parse(savedUri)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_player, Toast.LENGTH_SHORT).show()
    }
}
