package com.rantube.app

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.UUID

private val Ink = Color(0xFF101923)
private val Panel = Color(0xFF182430)
private val Muted = Color(0xFF9AA9B7)
private val Mint = Color(0xFF6BE7C8)
private val Canvas = Color(0xFF0B1219)

private enum class AppTab(val label: String, val mark: String) {
    Home("Home", "⌂"), Downloads("Downloads", "↓"), Library("Library", "▤")
}

private data class MediaEntry(val uri: String, val title: String)
private data class DownloadEntry(
    val id: String,
    val title: String,
    val status: String,
    val progress: Int,
    val filePath: String?,
    val failureReason: String? = null
)
private data class DownloadFormat(val label: String, val selector: String, val audioOnly: Boolean = false)

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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("rantube", Context.MODE_PRIVATE) }
    val library = remember { mutableStateListOf<MediaEntry>().apply {
        prefs.getStringSet("library", emptySet()).orEmpty().forEach { saved ->
            val parts = saved.split("|", limit = 2)
            if (parts.size == 2) add(MediaEntry(parts[0], parts[1]))
        }
    } }
    val downloads = remember { mutableStateListOf<DownloadEntry>().apply { addAll(scanDownloadedFiles()) } }
    var engineReady by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var engineAttempt by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(AppTab.Home) }
    var linkText by remember { mutableStateOf("") }
    var mediaTitle by remember { mutableStateOf("") }
    var availableFormats by remember { mutableStateOf<List<DownloadFormat>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf(DownloadFormat("Best available", "bestvideo+bestaudio/best")) }
    var inspecting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var extractionError by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(engineAttempt) {
        engineError = null
        engineReady = false
        runCatching {
            withContext(Dispatchers.IO) {
                val downloader = YoutubeDL.getInstance()
                downloader.init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                val lastExtractorCheck = prefs.getLong("yt_dlp_update_check", 0L)
                if (System.currentTimeMillis() - lastExtractorCheck > 7L * 24 * 60 * 60 * 1000) {
                    runCatching { downloader.updateYoutubeDL(context.applicationContext) }
                        .onSuccess { prefs.edit().putLong("yt_dlp_update_check", System.currentTimeMillis()).apply() }
                }
            }
        }.onSuccess { engineReady = true }
            .onFailure { engineError = it.message ?: "The download engine could not start." }
    }

    fun inspectMedia() {
        val url = linkText.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme !in setOf("https", "http") || uri?.host.isNullOrBlank()) {
            extractionError = "Paste a full http or https media-page link first."
            return
        }
        inspecting = true
        extractionError = null
        availableFormats = emptyList()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val request = YoutubeDLRequest(url).apply { addOption("--no-playlist") }
                    YoutubeDL.getInstance().getInfo(request)
                }
            }.onSuccess { info ->
                mediaTitle = info.title?.takeIf { it.isNotBlank() } ?: "Media download"
                val heights = info.formats.orEmpty()
                    .filter { it.height > 0 && it.vcodec != null && it.vcodec != "none" }
                    .map { it.height }
                    .distinct()
                    .sortedDescending()
                availableFormats = buildList {
                    add(DownloadFormat("Best available", "bestvideo+bestaudio/best"))
                    heights.forEach { height ->
                        add(DownloadFormat("Up to ${height}p", "bestvideo[height<=$height]+bestaudio/best[height<=$height]"))
                    }
                    add(DownloadFormat("Audio only · MP3", "bestaudio/best", audioOnly = true))
                }
                selectedFormat = availableFormats.first()
                if (heights.isEmpty()) extractionError = "The page was read, but yt-dlp did not report selectable video formats. Try another public media link."
            }.onFailure { error ->
                extractionError = error.message?.lineSequence()?.firstOrNull()?.take(220)
                    ?: "Couldn’t read formats from this link. The page may require sign-in or may not be supported."
            }
            inspecting = false
        }
    }

    fun startDownload() {
        val url = linkText.trim()
        if (availableFormats.isEmpty()) {
            extractionError = "Check the link first so Rantube can show its available formats."
            return
        }
        val downloadId = UUID.randomUUID().toString()
        val title = mediaTitle.ifBlank { "Media download" }
        val format = selectedFormat
        val folder = downloadDirectory()
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "Rantube media" }
        val outputTemplate = File(folder, "$safeName [%(id)s].%(ext)s").absolutePath
        downloads.add(0, DownloadEntry(downloadId, title, "Starting", -1, null))
        downloading = true
        extractionError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    folder.mkdirs()
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--newline")
                        addOption("--no-part")
                        if (!format.audioOnly) addOption("--merge-output-format", "mp4")
                        addOption("-f", format.selector)
                        addOption("-o", outputTemplate)
                        addOption("--print", "after_move:filepath")
                        if (format.audioOnly) {
                            addOption("-x")
                            addOption("--audio-format", "mp3")
                        }
                    }
                    YoutubeDL.getInstance().execute(request, downloadId) { percent, _, _ ->
                        val progress = percent.toInt().coerceIn(0, 100)
                        scope.launch(Dispatchers.Main) {
                            updateDownload(downloads, downloadId) { it.copy(status = "Downloading", progress = progress) }
                        }
                    }
                }
            }.onSuccess { response ->
                val printedPath = response.out.lineSequence().map { it.trim() }
                    .lastOrNull { it.isNotBlank() && File(it).isFile }
                val downloaded = printedPath?.let(::File) ?: folder.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("$safeName [") }
                    ?.maxByOrNull { it.lastModified() }
                if (downloaded != null && downloaded.length() > 0) {
                    updateDownload(downloads, downloadId) {
                        it.copy(status = "Complete", progress = 100, filePath = downloaded.absolutePath)
                    }
                } else {
                    updateDownload(downloads, downloadId) {
                        it.copy(status = "Failed", failureReason = "yt-dlp finished, but Rantube could not find a saved media file. See the download log and try again.")
                    }
                }
                linkText = ""
                tab = AppTab.Downloads
            }.onFailure { error ->
                val detail = error.message?.lineSequence()?.lastOrNull()?.take(220)
                    ?: "The link could not be downloaded. It may require a login or be unavailable."
                updateDownload(downloads, downloadId) { it.copy(status = "Failed", failureReason = detail) }
                extractionError = detail
            }
            downloading = false
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
        if (!engineReady) {
            EngineLoadingScreen(error = engineError, onRetry = { engineAttempt++ })
            return@MaterialTheme
        }

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
                        mediaTitle = mediaTitle,
                        formats = availableFormats,
                        selectedFormat = selectedFormat,
                        inspecting = inspecting,
                        downloading = downloading,
                        extractionError = extractionError,
                        onInspect = ::inspectMedia,
                        onSelectFormat = { selectedFormat = it },
                        onDownload = ::startDownload,
                        onShareWebsite = { shareText(context, "Share Rantube", "Try Rantube: https://randankevin004-hub.github.io/Rantube/") },
                        onShareRepository = { shareText(context, "Share Rantube source", "Rantube source code: https://github.com/randankevin004-hub/Rantube") },
                        onLibrary = { tab = AppTab.Library },
                        onDownloads = { tab = AppTab.Downloads }
                    )
                    tab == AppTab.Downloads -> DownloadsScreen(
                        downloads = downloads,
                        onOpen = { item ->
                            item.filePath?.let { openDownloadedFile(context, File(it)) }?.let { message = it }
                        },
                        onShare = { item ->
                            item.filePath?.let { shareDownloadedFile(context, File(it)) }?.let { message = it }
                        }
                    )
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
    mediaTitle: String,
    formats: List<DownloadFormat>,
    selectedFormat: DownloadFormat,
    inspecting: Boolean,
    downloading: Boolean,
    extractionError: String?,
    onInspect: () -> Unit,
    onSelectFormat: (DownloadFormat) -> Unit,
    onDownload: () -> Unit,
    onShareWebsite: () -> Unit,
    onShareRepository: () -> Unit,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onDownloads: () -> Unit
) {
    var showFormats by remember { mutableStateOf(false) }
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
            Text("DOWNLOAD A MEDIA PAGE OR FILE", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = onLinkTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste an Instagram, video, or file link") },
                leadingIcon = { Text("↗", color = Mint, fontSize = 20.sp) },
                trailingIcon = { TextButton(onClick = onPaste) { Text("Paste", color = Mint) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onInspect,
                enabled = linkText.isNotBlank() && !inspecting && !downloading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
            ) {
                if (inspecting) CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                else Text("Check available formats", fontWeight = FontWeight.Bold)
            }
            if (formats.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202F38)), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(mediaTitle, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text("${formats.size} download options found", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showFormats = true }) {
                            Text("Format: ${selectedFormat.label}  ·  Change", color = Mint)
                        }
                        Button(
                            onClick = onDownload,
                            enabled = !downloading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                        ) {
                            if (downloading) CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                            else Text("↓  Download ${selectedFormat.label}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            extractionError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFFA6A6), fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Rantube reads formats provided by the page’s extractor. It can’t access DRM-protected streams or content requiring unavailable sign-in.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onImport) { Text("＋  Choose files from this device", color = Mint) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareWebsite) { Text("Share website", color = Mint) }
                TextButton(onClick = onShareRepository) { Text("Share source", color = Mint) }
            }
        }
        item {
            Text("YOUR SPACE", color = Muted, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        }
        item { FeatureCard("▤", "Media library", "Browse and play files you choose from your device.", onLibrary) }
        item { FeatureCard("↓", "Download queue", "Track extracted media and check completed files.", onDownloads) }
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

    if (showFormats) {
        AlertDialog(
            onDismissRequest = { showFormats = false },
            containerColor = Panel,
            title = { Text("Choose a format", color = Color.White) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(formats, key = { it.label }) { format ->
                        TextButton(
                            onClick = { onSelectFormat(format); showFormats = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (selectedFormat == format) "✓  ${format.label}" else format.label,
                                color = if (selectedFormat == format) Mint else Color.White,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFormats = false }) { Text("Close", color = Mint) } }
        )
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
private fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onOpen: (DownloadEntry) -> Unit,
    onShare: (DownloadEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading("Downloads", "Files are saved in Downloads/Rantube")
        if (downloads.isEmpty()) EmptyState("Nothing in the queue yet", "Paste a media page or file link on Home and check its formats.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(downloads, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                append(item.status)
                                if (item.progress in 0..99) append(" · ${item.progress}%")
                            },
                            color = if (item.status.startsWith("Failed") || item.status.startsWith("Link page")) Color(0xFFFFA6A6) else Muted,
                            fontSize = 13.sp
                        )
                        if (item.failureReason != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(item.failureReason, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        if (item.status == "Starting" || item.status == "Extracting" || item.status == "Downloading") {
                            Spacer(Modifier.height(10.dp))
                            if (item.progress >= 0) {
                                LinearProgressIndicator(
                                    progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Mint,
                                    trackColor = Color(0xFF35434D)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Mint,
                                    trackColor = Color(0xFF35434D)
                                )
                            }
                        } else if (item.status == "Complete") {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Mint,
                                trackColor = Color(0xFF35434D)
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                TextButton(onClick = { onOpen(item) }) { Text("Open", color = Mint) }
                                TextButton(onClick = { onShare(item) }) { Text("Share file", color = Mint) }
                            }
                        }
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
private fun EngineLoadingScreen(error: String?, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(76.dp).background(Mint, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                Text("↓", color = Ink, fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(18.dp))
            Text("RANTUBE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 23.sp)
            Spacer(Modifier.height(8.dp))
            if (error == null) {
                CircularProgressIndicator(color = Mint, modifier = Modifier.padding(16.dp))
                Text("Getting the download engine ready…", color = Muted, fontSize = 14.sp)
                Text("This can take a little longer the first time.", color = Muted, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(12.dp))
                Text("The download engine could not start", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(error, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                    Text("Try again", fontWeight = FontWeight.Bold)
                }
            }
        }
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

private fun updateDownload(
    downloads: MutableList<DownloadEntry>,
    id: String,
    transform: (DownloadEntry) -> DownloadEntry
) {
    val index = downloads.indexOfFirst { it.id == id }
    if (index >= 0) downloads[index] = transform(downloads[index])
}

private fun downloadDirectory(): File = File(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    "Rantube"
)

private fun scanDownloadedFiles(): List<DownloadEntry> = downloadDirectory().listFiles()
    ?.filter {
        it.isFile && it.length() > 0 && it.extension.lowercase() in
            setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")
    }
    ?.sortedByDescending { it.lastModified() }
    ?.map { file -> DownloadEntry(file.absolutePath, file.nameWithoutExtension, "Complete", 100, file.absolutePath) }
    .orEmpty()

private fun openDownloadedFile(context: Context, file: File): String? {
    if (!file.isFile) return "Rantube can’t find that saved file. Look in Downloads/Rantube in the Files app."
    val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", file) }.getOrNull()
        ?: return "Rantube couldn’t create a safe file link."
    val mimeType = context.contentResolver.getType(uri) ?: "video/*"
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        null
    }.getOrElse { "No app on this device can open this media file. Check Downloads/Rantube in the Files app." }
}

private fun shareDownloadedFile(context: Context, file: File): String? {
    if (!file.isFile) return "Rantube can’t find that saved file to share."
    val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", file) }.getOrNull()
        ?: return "Rantube couldn’t create a safe file link to share."
    val mimeType = context.contentResolver.getType(uri) ?: "video/*"
    return runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, "Rantube media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share downloaded media"))
        null
    }.getOrElse { "Couldn’t open the share menu for this file." }
}

private fun shareText(context: Context, title: String, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
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
