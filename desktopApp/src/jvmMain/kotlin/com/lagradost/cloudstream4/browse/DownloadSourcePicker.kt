package com.lagradost.cloudstream4.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.download.DownloadSource
import com.lagradost.cloudstream4.download.Downloads
import com.lagradost.cloudstream4.providers.loadLinksSafely
import com.lagradost.cloudstream4.theme.AppShapes
import com.lagradost.cloudstream4.theme.BlackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Asks which source to download from, as the Android app does: the best quality can be a file of
 * many gigabytes, and a source's name often tells its size. The sources show as they are found,
 * best quality first, so one can be picked before every extractor is done.
 */
@Composable
fun DownloadSourcePicker(
    source: DownloadSource,
    onPick: (ExtractorLink, List<SubtitleFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Links come from the extractors' own threads
    val found = remember(source) { MutableStateFlow<List<ExtractorLink>>(emptyList()) }
    val subtitles = remember(source) { MutableStateFlow<List<SubtitleFile>>(emptyList()) }
    var finding by remember(source) { mutableStateOf(true) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        val api = APIHolder.getApiFromNameNull(source.apiName)
        if (api == null) {
            error = "${source.apiName} is not loaded"
        } else withContext(Dispatchers.IO) {
            api.loadLinksSafely(
                source.data,
                onSubtitle = { sub -> subtitles.update { if (it.any { s -> s.url == sub.url }) it else it + sub } },
                // An extractor can still call back once the picker is closed
                onLink = { link -> if (isActive) found.update { Downloads.forPicking(it + link) } },
            )
        }
        finding = false
    }
    val links by found.collectAsState()

    AlertDialog(
        // dialog__window_background.xml
        containerColor = MaterialTheme.colorScheme.background,
        shape = AppShapes.dialog,
        onDismissRequest = onDismiss,
        title = { Text("Download from") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (finding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        when {
                            error != null -> error!!
                            finding -> "Finding sources… ${links.size} found"
                            links.isEmpty() -> "No source can be downloaded"
                            else -> "${links.size} sources"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.width(520.dp).heightIn(max = 440.dp)) {
                    items(links, key = { it.url }) { link ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(link, subtitles.value) }.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(link.name.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(details(link), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { BlackButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** For example "1080p · HLS stream · example.com" */
private fun details(link: ExtractorLink): String = listOfNotNull(
    Qualities.getStringByInt(link.quality).takeIf { it.isNotBlank() },
    if (link.type == ExtractorLinkType.M3U8) "HLS stream" else "Video file",
    runCatching { URI(link.url).host?.removePrefix("www.") }.getOrNull(),
).joinToString(" · ")
