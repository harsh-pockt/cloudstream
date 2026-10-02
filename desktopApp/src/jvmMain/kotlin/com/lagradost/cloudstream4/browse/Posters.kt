package com.lagradost.cloudstream4.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.AppDirs
import okio.Path.Companion.toOkioPath

/**
 * Loads posters with OkHttp like the Android app does. OkHttp tries every address a host has,
 * which the JDK client used by Coil's default network module does not.
 */
fun desktopImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
    .crossfade(200)
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
    .diskCache {
        DiskCache.Builder()
            .directory(AppDirs.cache.resolve("images").toOkioPath())
            .maxSizeBytes(512L * 1024 * 1024)
            .build()
    }
    // Only this fetcher, so the Ktor one that the shared module brings in is never picked instead
    .serviceLoaderEnabled(false)
    .components { add(OkHttpNetworkFetcherFactory()) }
    .build()

private val verticalWidth = 140.dp
private val horizontalWidth = 240.dp

/** An image from a provider, which may need its own headers to serve it */
@Composable
fun RemoteImage(url: String?, headers: Map<String, String>?, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().apply {
                set("User-Agent", USER_AGENT)
                headers?.forEach { (key, value) -> set(key, value) }
            }.build())
            .build()
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A poster with its title underneath */
@Composable
fun PosterCard(item: SearchResponse, horizontal: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .width(if (horizontal) horizontalWidth else verticalWidth)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier)
    ) {
        RemoteImage(
            item.posterUrl,
            item.posterHeaders,
            contentDescription = item.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (horizontal) 16f / 9f else 2f / 3f),
        )
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A titled, sideways-scrolling row of posters.
 *
 * @param trailing shown after the last poster, for example a "More" button
 */
@Composable
fun PosterRow(
    title: String,
    items: List<SearchResponse>,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    loading: Boolean = false,
    onItemClick: ((SearchResponse) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // No keys, providers sometimes list the same item twice and duplicate keys crash the row
            items(items) { item -> PosterCard(item, horizontal, onClick = onItemClick?.let { { it(item) } }) }
            if (trailing != null) item { trailing() }
        }
    }
}

/** The end of a row that has more pages */
@Composable
fun MoreButton(loading: Boolean, horizontal: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(if (horizontal) horizontalWidth else verticalWidth).aspectRatio(if (horizontal) 16f / 9f else 2f / 3f),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
        else OutlinedButton(onClick = onClick) { Text("More") }
    }
}

/** A centred message for empty, loading and error states */
@Composable
fun Message(title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        action?.let {
            Spacer(Modifier.size(16.dp))
            it()
        }
    }
}
