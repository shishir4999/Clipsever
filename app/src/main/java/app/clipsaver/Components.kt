package app.clipsaver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.util.Locale

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** The popup that lists every resolution (Video tab) and audio format (Audio tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(
    details: VideoDetails,
    onDismiss: () -> Unit,
    onPick: (DownloadOption) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(details.thumbnail, Modifier.width(128.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        details.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val duration = formatDuration(details.durationSec)
                    val meta = listOfNotNull(
                        details.uploader,
                        if (duration.isEmpty()) null else duration,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.tab_video)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.tab_audio)) },
                )
            }
            val options = if (tab == 0) details.videoOptions else details.audioOptions
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.id }) { option ->
                    OptionRow(option = option, onClick = { onPick(option) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionRow(option: DownloadOption, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(option.label) },
        supportingContent = if (option.detail.isNotBlank()) {
            { Text(option.detail) }
        } else {
            null
        },
        trailingContent = {
            Text(
                stringResource(R.string.sheet_download),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun JobCard(
    job: DownloadJob,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(job.thumbnail, Modifier.width(88.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    job.option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (job.status) {
                    JobStatus.RUNNING -> {
                        LinearProgressIndicator(
                            progress = { job.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        val eta = formatEta(job.etaSeconds)
                        val etaLabel = if (eta.isEmpty()) "" else " · " + stringResource(R.string.eta_left, eta)
                        Text(
                            "${(job.progress * 100).toInt()}%$etaLabel",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    JobStatus.QUEUED -> Text(
                        stringResource(R.string.status_queued),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    JobStatus.DONE -> Text(
                        stringResource(R.string.status_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    JobStatus.FAILED -> Text(
                        job.message ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    JobStatus.CANCELLED -> Text(
                        stringResource(R.string.status_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End) {
                when (job.status) {
                    JobStatus.QUEUED, JobStatus.RUNNING -> IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                    JobStatus.DONE -> {
                        IconButton(onClick = onOpen) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.open))
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove))
                        }
                    }
                    JobStatus.FAILED, JobStatus.CANCELLED -> IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove))
                    }
                }
            }
        }
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    return if (seconds >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}

fun formatDuration(seconds: Long): String = formatEta(seconds)
