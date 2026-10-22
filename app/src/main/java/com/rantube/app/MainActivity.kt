package com.rantube.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.URLDecoder

private val Ink = Color(0xFF101923)
private val Panel = Color(0xFF182430)
private val Muted = Color(0xFF9AA9B7)
private val Mint = Color(0xFF6BE7C8)
private val Canvas = Color(0xFF0B1219)

private enum class AppTab(val label: String, val mark: String) {
    Home("Home", "⌂"), Downloads("Downloads", "↓"), Library("Library", "▤")
}

private data class MediaEntry(val uri: String, val title: String)
private data class DownloadEntry(val id: Long, val title: String, val status: String, val progress: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RantubeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RantubeApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rantube", Context.MODE_PRIVATE) }
    val library = remember { mutableStateListOf<MediaEntry>().apply {
        prefs.getStringSet("library", emptySet()).orEmpty().forEach { saved ->
            val parts = saved.split("|", limit = 2)
            if (parts.size == 2) add(MediaEntry(parts[0], parts[1]))
        }
    } }
    val downloads = remember { mutableStateListOf<DownloadEntry>() }
    var tab by remember { mutableStateOf(AppTab.Home) }
    var linkText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MediaEntry?>(null) }
    var search by remember { mutableStateOf("") }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val entry = MediaEntry(uri.toString(), context.displayName(uri))
            if (library.none { it.uri == entry.uri }) library.add(entry)
        }
        prefs.edit().putStringSet("library", library.map { "${it.uri}|${it.title}" }.toSet()).apply()
    }

    LaunchedEffect(downloads.size, tab) {
        while (true) {
            refreshDownloads(context, prefs, downloads)
            delay(1500)
        }
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = Canvas,
        surface = Panel,
        primary = Mint,
        onPrimary = Ink,
        onBackground = Color.White,
        onSurface = Color.White
    )) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                if (selected == null) BottomBar(tab = tab, onSelect = { tab = it })
            }
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when {
                    selected != null -> PlayerScreen(
                        item = selected!!,
                        onBack = { selected = null }
                    )
                    tab == AppTab.Home -> HomeScreen(
                        onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                        linkText = linkText,
                        onLinkTextChange = { linkText = it },
                        onPaste = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                                ?.toString()?.let { linkText = it }
                        },
                        onDownload = {
                            val result = enqueueDirectDownload(context, prefs, linkText)
                            message = result
                            if (result.startsWith("Download added")) {
                                linkText = ""
                                tab = AppTab.Downloads
                            }
                        },
                        onLibrary = { tab = AppTab.Library },
                        onDownloads = { tab = AppTab.Downloads }
                    )
                    tab == AppTab.Downloads -> DownloadsScreen(downloads = downloads)
                    else -> LibraryScreen(
                        items = library,
                        search = search,
                        onSearch = { search = it },
                        onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                        onOpen = { selected = it },
                        onRemove = { entry ->
                            library.remove(entry)
                            prefs.edit().putStringSet("library", library.map { "${it.uri}|${it.title}" }.toSet()).apply()
                        }
                    )
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = Panel,
            title = { Text("Rantube", color = Color.White) },
            text = { Text(text, color = Muted) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK", color = Mint) } }
        )
    }
}

@Composable
private fun HomeScreen(
    linkText: String,
    onLinkTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onDownloads: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                    Text("R", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("RANTUBE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp)
                    Text("YOUR MEDIA SPACE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("Keep what\nyou love.", color = Color.White, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Text("A calmer place for your own media, saved links, and listening queue.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        item {
            Text("DOWNLOAD A DIRECT MEDIA LINK", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = onLinkTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a video or audio link") },
                leadingIcon = { Text("↗", color = Mint, fontSize = 20.sp) },
                trailingIcon = { TextButton(onClick = onPaste) { Text("Paste", color = Mint) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDownload,
                enabled = linkText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
            ) {
                Text("↓  Download original file", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text("Direct file links keep their source quality. Rantube doesn’t extract protected streams.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onImport) { Text("＋  Choose files from this device", color = Mint) }
        }
        item {
            Text("YOUR SPACE", color = Muted, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        }
        item { FeatureCard("▤", "Media library", "Browse and play files you choose from your device.", onLibrary) }
        item { FeatureCard("↓", "Download queue", "Track direct media links saved for offline use.", onDownloads) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17372F)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Made for your media", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Rantube works with files and direct links you’re allowed to save. It doesn’t unlock protected streams.", color = Color(0xFFC7D8D2), fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun FeatureCard(mark: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(Color(0xFF253843), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text(mark, color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Text("›", color = Muted, fontSize = 28.sp)
        }
    }
}

@Composable
private fun DownloadsScreen(downloads: List<DownloadEntry>) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading("Downloads", "Direct links saved on this device")
        if (downloads.isEmpty()) EmptyState("Nothing in the queue yet", "Paste a direct media URL from Home to add a download.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(downloads, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(if (item.progress in 0..99) "${item.status} · ${item.progress}%" else item.status, color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    items: List<MediaEntry>,
    search: String,
    onSearch: (String) -> Unit,
    onImport: () -> Unit,
    onOpen: (MediaEntry) -> Unit,
    onRemove: (MediaEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading("Your library", "Only files you choose appear here")
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search your media") },
            singleLine = true,
            colors = fieldColors()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
            Text("＋  Choose audio or video", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        val filtered = items.filter { it.title.contains(search, ignoreCase = true) }
        if (filtered.isEmpty()) EmptyState("Your library is ready", "Choose audio or video files with Android’s file picker. Rantube only gets access to the items you select.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(filtered, key = { it.uri }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Color(0xFF253843), CircleShape), contentAlignment = Alignment.Center) {
                            Text("▶", color = Mint, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.title, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRemove(item) }) { Text("Remove", color = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(item: MediaEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(PlayerMediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text("‹  Library", color = Mint) }
        Spacer(Modifier.height(12.dp))
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(245.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.height(5.dp))
        Text("Playing from your selected files", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 24.dp, bottom = 18.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).background(Color(0xFF1C2B35), CircleShape), contentAlignment = Alignment.Center) {
            Text("R", color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(13.dp))
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun BottomBar(tab: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        AppTab.entries.forEach { item ->
            Column(
                Modifier.clickable { onSelect(item) }.padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.mark, color = if (tab == item) Mint else Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(item.label, color = if (tab == item) Mint else Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Mint,
    unfocusedBorderColor = Color(0xFF35434D),
    cursorColor = Mint,
    focusedPlaceholderColor = Muted,
    unfocusedPlaceholderColor = Muted
)

private fun enqueueDirectDownload(context: Context, prefs: android.content.SharedPreferences, raw: String): String {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return "That link is not valid."
    if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
        return "Use a direct http or https media file URL. Protected platform streams are not supported."
    }
    return runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val guessed = uri.lastPathSegment?.let { URLDecoder.decode(it, "UTF-8") }
            ?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "rantube-media"
        val filename = guessed.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "rantube-media" }
        val request = DownloadManager.Request(uri)
            .setTitle(filename)
            .setDescription("Saved by Rantube")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Rantube/$filename")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = manager.enqueue(request)
        val current = prefs.getStringSet("download_ids", emptySet()).orEmpty().toMutableSet()
        current.add(id.toString())
        prefs.edit().putStringSet("download_ids", current).apply()
        "Download added to the system queue."
    }.getOrElse { "Couldn’t add this link: ${it.message ?: "download manager unavailable"}" }
}

private fun refreshDownloads(context: Context, prefs: android.content.SharedPreferences, output: MutableList<DownloadEntry>) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val records = prefs.getStringSet("download_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
    val current = records.mapNotNull { id ->
        var cursor: Cursor? = null
        try {
            cursor = manager.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) return@mapNotNull null
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Media download"
            val statusCode = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val status = when (statusCode) {
                DownloadManager.STATUS_PENDING -> "Waiting"
                DownloadManager.STATUS_RUNNING -> "Downloading"
                DownloadManager.STATUS_PAUSED -> "Paused"
                DownloadManager.STATUS_SUCCESSFUL -> "Complete"
                DownloadManager.STATUS_FAILED -> "Failed"
                else -> "Queued"
            }
            DownloadEntry(id, title, status, if (total > 0) ((soFar * 100) / total).toInt() else -1)
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
    output.clear()
    output.addAll(current)
}

private fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected media"
}

