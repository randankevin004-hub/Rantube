package com.rantube.app

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaScannerConnection
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.ImeAction
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Ink = Color(0xFF101923)
private val Panel = Color(0xFF182430)
private val Muted = Color(0xFF9AA9B7)
private val Mint = Color(0xFF6BE7C8)
private val Canvas = Color(0xFF0B1219)

private enum class AppTab(val label: String, val mark: String) {
    Home("Home", "⌂"), Browse("Browse", "◎"), Downloads("Downloads", "↓"), Vault("Vault", "▣"), Library("Library", "▤")
}

private data class MediaEntry(val uri: String, val title: String)
private data class DownloadEntry(
    val id: String,
    val title: String,
    val status: String,
    val progress: Int,
    val filePath: String?,
    val failureReason: String? = null,
    val etaSeconds: Long? = null
)
private data class DownloadFormat(val label: String, val selector: String, val audioOnly: Boolean = false, val sizeBytes: Long? = null)
private data class VaultEntry(val id: String, val title: String, val mimeType: String, val extension: String)
private data class PageVideo(val title: String, val url: String)
private data class SiteShortcut(val name: String, val url: String, val private: Boolean = false)

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
    val downloads = remember { mutableStateListOf<DownloadEntry>().apply { addAll(scanDownloadedFiles(context)) } }
    val vaultItems = remember { mutableStateListOf<VaultEntry>().apply { addAll(readVaultItems(prefs)) } }
    var engineReady by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var engineAttempt by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(AppTab.Home) }
    var linkText by remember { mutableStateOf("") }
    var browserStartUrl by remember { mutableStateOf("https://www.google.com") }
    var browserView by remember { mutableStateOf<WebView?>(null) }
    var browserAddress by remember { mutableStateOf("") }
    var mediaTitle by remember { mutableStateOf("") }
    var availableFormats by remember { mutableStateOf<List<DownloadFormat>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<DownloadFormat?>(null) }
    var showFormatChooser by remember { mutableStateOf(false) }
    var showAllFormats by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var extractionError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MediaEntry?>(null) }
    var vaultUnlocked by remember { mutableStateOf(false) }
    var vaultPlaybackFile by remember { mutableStateOf<File?>(null) }
    var search by remember { mutableStateOf("") }
    var showPrivateShortcuts by remember { mutableStateOf(false) }
    var showAddShortcut by remember { mutableStateOf(false) }
    var privateShortcuts by remember {
        mutableStateOf(prefs.getString("private_shortcuts", "").orEmpty().lineSequence().mapNotNull { row ->
            val parts = row.split("|", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) SiteShortcut(parts[0], parts[1], true) else null
        }.toList())
    }

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
            .onFailure { engineError = "Please check your connection and try again." }
    }

    fun inspectMedia(sourceUrl: String = linkText.trim()) {
        val url = sourceUrl.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme !in setOf("https", "http") || uri?.host.isNullOrBlank()) {
            extractionError = "Enter a full website link first."
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
                val allVideoFormats = info.formats.orEmpty()
                    .filter { it.height > 0 && it.vcodec != null && it.vcodec != "none" }
                val heights = allVideoFormats.map { it.height }.distinct().sortedDescending()
                val bestAudio = info.formats.orEmpty().filter { it.vcodec == "none" && it.acodec != null && it.acodec != "none" }
                    .maxByOrNull { it.fileSizeApproximate.takeIf { size -> size > 0 } ?: it.fileSize }
                availableFormats = buildList {
                    heights.forEach { height ->
                        val easyLabel = when {
                            height >= 1080 -> "Clear picture (${height}p)"
                            height >= 720 -> "Good picture (${height}p)"
                            else -> "Smaller file (${height}p)"
                        }
                        val video = allVideoFormats.filter { it.height == height }
                            .maxByOrNull { it.fileSizeApproximate.takeIf { size -> size > 0 } ?: it.fileSize }
                        val videoBytes = video?.let { it.fileSizeApproximate.takeIf { size -> size > 0 } ?: it.fileSize }?.takeIf { it > 0 }
                        val audioBytes = bestAudio?.let { it.fileSizeApproximate.takeIf { size -> size > 0 } ?: it.fileSize }?.takeIf { it > 0 }
                        val totalBytes = if (videoBytes != null && audioBytes != null) videoBytes + audioBytes else videoBytes
                        add(DownloadFormat(easyLabel, "bestvideo[height<=$height]+bestaudio/best[height<=$height]", sizeBytes = totalBytes))
                    }
                    add(DownloadFormat("MP3 audio", "bestaudio/best", audioOnly = true, sizeBytes = bestAudio?.let { it.fileSizeApproximate.takeIf { size -> size > 0 } ?: it.fileSize }?.takeIf { it > 0 }))
                }
                selectedFormat = null
                if (heights.isEmpty()) extractionError = "Rantube couldn't find a video choice on this page. Try opening the video first."
                else showFormatChooser = true
            }.onFailure { error ->
                extractionError = "Rantube couldn't open download choices for this link. The page may need you to sign in or may not be supported."
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
        val format = selectedFormat ?: run {
            showFormatChooser = true
            return
        }
        val folder = downloadDirectory(context)
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
                    YoutubeDL.getInstance().execute(request, downloadId) { percent, etaSeconds, _ ->
                        val progress = percent.toInt().coerceIn(0, 100)
                        scope.launch(Dispatchers.Main) {
                            updateDownload(downloads, downloadId) {
                                it.copy(status = "Downloading", progress = progress, etaSeconds = etaSeconds.takeIf { eta -> eta >= 0 })
                            }
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
                    runCatching { withContext(Dispatchers.IO) { publishDownloadedFile(context, downloaded) } }
                        .onSuccess { savedUri ->
                            downloaded.delete()
                            updateDownload(downloads, downloadId) {
                                it.copy(status = "Complete", progress = 100, filePath = savedUri.toString())
                            }
                        }
                        .onFailure {
                            updateDownload(downloads, downloadId) { it.copy(status = "Failed", failureReason = "Rantube couldn't save the file to Downloads/Rantube. Please check your free storage and try again.") }
                        }
                } else {
                    updateDownload(downloads, downloadId) {
                    it.copy(status = "Failed", failureReason = "Rantube finished, but couldn't find the saved file. Check Downloads/Rantube in the Files app.")
                    }
                }
                linkText = ""
                tab = AppTab.Downloads
            }.onFailure { error ->
                val detail = "Rantube couldn't download this page. It may need you to sign in or may not be supported."
                updateDownload(downloads, downloadId) { it.copy(status = "Failed", failureReason = detail) }
                extractionError = detail
            }
            downloading = false
        }
    }

    fun searchOrOpen(value: String = linkText) {
        val text = value.trim()
        if (text.isBlank()) return
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri?.scheme in setOf("https", "http") && !uri?.host.isNullOrBlank()) {
            linkText = text
            browserStartUrl = text
            browserAddress = text
            tab = AppTab.Browse
        } else {
            browserStartUrl = "https://www.google.com/search?q=${Uri.encode(text)}"
            browserAddress = browserStartUrl
            tab = AppTab.Browse
        }
    }

    fun openShortcut(shortcut: SiteShortcut) {
        browserStartUrl = shortcut.url
        browserAddress = shortcut.url
        tab = AppTab.Browse
    }

    BackHandler(enabled = tab == AppTab.Browse && browserView?.canGoBack() == true) {
        browserView?.goBack()
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
                if (selected == null) BottomBar(tab = tab, onSelect = { tab = it; if (it != AppTab.Vault) vaultUnlocked = false })
            }
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when {
                    selected != null -> PlayerScreen(
                        item = selected!!,
                        onBack = { selected = null; vaultPlaybackFile?.let { it.delete() }; vaultPlaybackFile = null; vaultUnlocked = false }
                    )
                    tab == AppTab.Home -> HomeScreen(
                        linkText = linkText,
                        onLinkTextChange = { linkText = it },
                        onPaste = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                                ?.toString()?.let { linkText = it }
                        },
                        onSearch = { searchOrOpen() },
                        onShortcut = ::openShortcut,
                        privateShortcuts = privateShortcuts,
                        showPrivateShortcuts = showPrivateShortcuts,
                        onTogglePrivateShortcuts = { showPrivateShortcuts = !showPrivateShortcuts },
                        onAddPrivateShortcut = { showAddShortcut = true },
                        onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                        onShareWebsite = { shareText(context, "Share Rantube", "Try Rantube: https://randankevin004-hub.github.io/Rantube/") },
                        onShareRepository = { shareText(context, "Share Rantube source", "Rantube source code: https://github.com/randankevin004-hub/Rantube") },
                        onLibrary = { tab = AppTab.Library },
                        onDownloads = { tab = AppTab.Downloads }
                    )
                    tab == AppTab.Browse -> BrowserScreen(
                        startUrl = browserStartUrl,
                        address = browserAddress,
                        onAddressChange = { browserAddress = it },
                        onSearch = { value ->
                            val trimmed = value.trim()
                            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
                            val target = if (uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) trimmed
                            else "https://www.google.com/search?q=${Uri.encode(trimmed)}"
                            browserStartUrl = target
                            browserAddress = target
                        },
                        onWebView = { browserView = it },
                        onDownload = { url ->
                            linkText = url
                            inspectMedia(url)
                        },
                        onHome = { tab = AppTab.Home }
                    )
                    tab == AppTab.Downloads -> DownloadsScreen(
                        downloads = downloads,
                        onOpen = { item ->
                            item.filePath?.let { openDownloadedFile(context, it) }?.let { message = it }
                        },
                        onShare = { item ->
                            item.filePath?.let { shareDownloadedFile(context, it) }?.let { message = it }
                        },
                        onMoveToVault = { item ->
                            val source = item.filePath
                            if (!prefs.contains("vault_pin_hash")) { tab = AppTab.Vault; message = "Create your vault PIN first, then move downloads into it." }
                            else if (source == null) message = "Rantube couldn't find that saved file."
                            else {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { moveToVault(context, prefs, source, item.title) }
                                    }.onSuccess { saved ->
                                        vaultItems.add(0, saved)
                                        downloads.removeAll { it.filePath == source }
                                        message = "Moved to your locked vault. The public download copy was removed."
                                    }.onFailure { message = "Rantube couldn't move that file into the vault. Please try again." }
                                }
                            }
                        }
                    )
                    tab == AppTab.Vault -> VaultScreen(
                        hasPin = prefs.contains("vault_pin_hash"),
                        unlocked = vaultUnlocked,
                        items = vaultItems,
                        isCreatingPin = !prefs.contains("vault_pin_hash"),
                        onUnlock = { pin -> verifyVaultPin(prefs, pin).also { vaultUnlocked = it } },
                        onSetPin = { pin -> setVaultPin(prefs, pin); vaultUnlocked = true },
                        onLock = { vaultUnlocked = false },
                        onOpen = { entry ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { decryptVaultFile(context, entry) } }
                                    .onSuccess { file -> vaultPlaybackFile = file; selected = MediaEntry(Uri.fromFile(file).toString(), entry.title) }
                                    .onFailure { message = "Rantube couldn't open this vault video." }
                            }
                        },
                        onDelete = { entry ->
                            runCatching { deleteVaultItem(context, prefs, entry) }
                                .onSuccess { vaultItems.remove(entry); message = "Removed from the vault." }
                                .onFailure { message = "Rantube couldn't remove this vault file." }
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

        if (showFormatChooser && availableFormats.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { showFormatChooser = false },
                containerColor = Panel,
                contentColor = Color.White,
                dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = Muted) }
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                    Text("Choose what to save", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(mediaTitle, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("AUDIO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    availableFormats.filter { it.audioOnly }.forEach { format ->
                        FormatChoice(format, selectedFormat == format) { selectedFormat = format }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("VIDEO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    val videoChoices = availableFormats.filterNot { it.audioOnly }
                    (if (showAllFormats) videoChoices else videoChoices.take(2)).forEach { format ->
                        FormatChoice(format, selectedFormat == format) { selectedFormat = format }
                    }
                    if (videoChoices.size > 2) {
                        TextButton(onClick = { showAllFormats = !showAllFormats }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showAllFormats) "Show fewer formats" else "Show all formats (${videoChoices.size})", color = Mint)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("File sizes are estimates and may change slightly.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { showFormatChooser = false; startDownload() },
                        enabled = selectedFormat != null && !downloading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                    ) { Text(if (downloading) "Saving…" else "Download", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showAddShortcut) {
        AddPrivateShortcutDialog(
            onDismiss = { showAddShortcut = false },
            onSave = { name, url ->
                val shortcut = SiteShortcut(name, url, true)
                privateShortcuts = privateShortcuts + shortcut
                prefs.edit().putString("private_shortcuts", privateShortcuts.joinToString("\n") { "${it.name}|${it.url}" }).apply()
                showAddShortcut = false
                showPrivateShortcuts = true
            }
        )
    }

    extractionError?.let { error ->
        AlertDialog(
            onDismissRequest = { extractionError = null },
            containerColor = Panel,
            title = { Text("Rantube couldn't do that", color = Color.White) },
            text = { Text(error, color = Muted) },
            confirmButton = { TextButton(onClick = { extractionError = null }) { Text("OK", color = Mint) } }
        )
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
    onSearch: () -> Unit,
    onShortcut: (SiteShortcut) -> Unit,
    privateShortcuts: List<SiteShortcut>,
    showPrivateShortcuts: Boolean,
    onTogglePrivateShortcuts: () -> Unit,
    onAddPrivateShortcut: () -> Unit,
    onShareWebsite: () -> Unit,
    onShareRepository: () -> Unit,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onDownloads: () -> Unit
) {
    val shortcuts = listOf(
        SiteShortcut("YouTube", "https://www.youtube.com"),
        SiteShortcut("Instagram", "https://www.instagram.com"),
        SiteShortcut("TikTok", "https://www.tiktok.com"),
        SiteShortcut("Facebook", "https://www.facebook.com"),
        SiteShortcut("Reddit", "https://www.reddit.com")
    )
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
                    Text("SEARCH · WATCH · SAVE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("Find it. Watch it.\nSave it.", color = Color.White, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Text("Search the web or paste a video link to get started.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        item {
            Text("SEARCH OR PASTE A LINK", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = onLinkTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search for a song or paste a link") },
                leadingIcon = { Text("⌕", color = Mint, fontSize = 24.sp) },
                trailingIcon = { TextButton(onClick = onPaste) { Text("Paste", color = Mint) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSearch,
                enabled = linkText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
            ) {
                Text("Search", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("QUICK LINKS", color = Muted, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(shortcuts, key = { it.name }) { shortcut ->
                    Card(
                        modifier = Modifier.width(102.dp).clickable { onShortcut(shortcut) },
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            QuickLinkIcon(shortcut.name)
                            Spacer(Modifier.height(7.dp))
                            Text(shortcut.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Private shortcuts", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onTogglePrivateShortcuts) { Text(if (showPrivateShortcuts) "Hide" else "Show", color = Mint) }
                    }
                    Text("Hidden until you choose to show them.", color = Muted, fontSize = 12.sp)
                    if (showPrivateShortcuts) {
                        privateShortcuts.forEach { shortcut ->
                            TextButton(onClick = { onShortcut(shortcut) }, modifier = Modifier.fillMaxWidth()) {
                                Text("↗  ${shortcut.name}", color = Mint, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        TextButton(onClick = onAddPrivateShortcut) { Text("＋ Add a private site", color = Mint) }
                    }
                }
            }
        }
        item {
            Text("YOUR MEDIA", color = Muted, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        }
        item { FeatureCard("▤", "My files", "Play audio and video you choose from your phone.", onLibrary) }
        item { FeatureCard("↓", "Downloads", "See what is saving and open finished files.", onDownloads) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImport) { Text("Choose files", color = Mint) }
                TextButton(onClick = onShareWebsite) { Text("Share app", color = Mint) }
                TextButton(onClick = onShareRepository) { Text("Source", color = Mint) }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun QuickLinkIcon(name: String) {
    val (color, glyph, shape) = when (name.lowercase()) {
        "youtube" -> Triple(Color(0xFFFF0033), "▶", RoundedCornerShape(12.dp))
        "instagram" -> Triple(Color(0xFFC13584), "◎", CircleShape)
        "tiktok" -> Triple(Color(0xFF111111), "♪", RoundedCornerShape(12.dp))
        "facebook" -> Triple(Color(0xFF1877F2), "f", CircleShape)
        "reddit" -> Triple(Color(0xFFFF4500), "●", CircleShape)
        else -> Triple(Color(0xFF253843), name.take(1).uppercase(), CircleShape)
    }
    Box(Modifier.size(42.dp).background(color, shape), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontWeight = FontWeight.Black, fontSize = if (name.equals("facebook", true)) 28.sp else 21.sp)
    }
}

@Composable
private fun FormatChoice(format: DownloadFormat, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).background(
            if (selected) Color(0xFF203C37) else Color.Transparent,
            RoundedCornerShape(16.dp)
        ).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).background(Color(0xFF253843), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Text(if (format.audioOnly) "♫" else "▶", color = Mint, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(format.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(format.sizeBytes?.let(::formatFileSize) ?: "Size unavailable", color = Muted, fontSize = 12.sp)
        }
        Box(Modifier.size(22.dp).background(if (selected) Mint else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Text("✓", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            else Box(Modifier.size(20.dp).background(Color.Transparent, CircleShape))
        }
    }
}

@Composable
private fun AddPrivateShortcutDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Add a private shortcut", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This shortcut stays hidden until you show private shortcuts.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, colors = fieldColors())
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Website address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
                if (name.isNotBlank() && Uri.parse(target).host?.contains('.') == true) onSave(name.trim(), target)
            }) { Text("Save", color = Mint) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun BrowserScreen(
    startUrl: String,
    address: String,
    onAddressChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onWebView: (WebView?) -> Unit,
    onDownload: (String) -> Unit,
    onHome: () -> Unit
) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var pageVideos by remember { mutableStateOf<List<PageVideo>>(emptyList()) }
    var showPageVideos by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton(onClick = onHome) { Text("⌂", color = Mint, fontSize = 20.sp) }
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search or enter a website") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSearch(address) }),
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors()
            )
            TextButton(onClick = { onSearch(address) }) { Text("Go", color = Mint) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (view?.canGoBack() == true) view?.goBack() }) { Text("‹ Back", color = Mint) }
            TextButton(onClick = { view?.reload() }) { Text("Refresh", color = Mint) }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                        override fun onPageFinished(view: WebView, url: String) {
                            onAddressChange(url)
                            val script = """(()=>JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a=>({url:a.href,title:(a.innerText||a.getAttribute('title')||'Video').trim().replace(/\s+/g,' ')})).filter(x=>/(youtube\.com\/(watch|shorts)|youtu\.be\/|instagram\.com\/(reel|p)\/|tiktok\.com\/@[^/]+\/video\/)/i.test(x.url)).filter((x,i,all)=>all.findIndex(y=>y.url===x.url)===i).slice(0,12)))()"""
                            view.evaluateJavascript(script) { raw ->
                                runCatching {
                                    val encoded = JSONObject("{\"value\":$raw}").getString("value")
                                    val array = JSONArray(encoded)
                                    (0 until array.length()).map { i -> array.getJSONObject(i) }
                                        .map { PageVideo(it.optString("title", "Video"), it.optString("url")) }
                                        .filter { it.url.isNotBlank() }
                                }.onSuccess { pageVideos = it; showPageVideos = false }
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    view = this
                    onWebView(this)
                    loadUrl(startUrl)
                }
            },
            update = { webView ->
                view = webView
                onWebView(webView)
            }
        )
        Spacer(Modifier.height(9.dp))
        Button(
            onClick = { view?.url?.let(onDownload) },
            enabled = view?.url?.startsWith("http") == true,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
        ) { Text("↓  Download this video", fontWeight = FontWeight.Bold) }
        if (pageVideos.isNotEmpty()) {
            TextButton(onClick = { showPageVideos = !showPageVideos }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showPageVideos) "Hide videos found (${pageVideos.size})" else "Show videos found (${pageVideos.size})", color = Mint)
            }
            if (showPageVideos) pageVideos.take(5).forEach { video ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(video.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDownload(video.url) }) { Text("↓", color = Mint, fontSize = 20.sp) }
                }
            }
        }
    }
    LaunchedEffect(startUrl, view) {
        if (view != null && view?.url != startUrl) view?.loadUrl(startUrl)
    }
    DisposableEffect(view) { onDispose { onWebView(null) } }
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
    onShare: (DownloadEntry) -> Unit,
    onMoveToVault: (DownloadEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading("Downloads", "Saved files are in Files → Downloads → Rantube")
        if (downloads.isEmpty()) EmptyState("No downloads yet", "Search for a video or paste a link on Home, then choose Download.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(downloads, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                item.status == "Downloading" -> "${item.progress.coerceAtLeast(0)}% · ${item.etaSeconds?.let(::formatRemainingTime) ?: "Calculating time left"}"
                                item.status == "Complete" -> "Complete"
                                else -> item.status
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
                                TextButton(onClick = { onMoveToVault(item) }) { Text("Move to vault", color = Mint) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultScreen(
    hasPin: Boolean,
    unlocked: Boolean,
    items: List<VaultEntry>,
    isCreatingPin: Boolean,
    onUnlock: (String) -> Boolean,
    onSetPin: (String) -> Unit,
    onLock: () -> Unit,
    onOpen: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeading("Private vault", "Your hidden videos are encrypted on this phone")
        if (!unlocked) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isCreatingPin) "Create a PIN" else "Enter your PIN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (isCreatingPin) "Choose a PIN with at least 6 numbers. Remember it: it cannot be reset." else "Enter the PIN you created for this vault.", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = fieldColors())
                    if (isCreatingPin) OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(12) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Enter the PIN again") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = fieldColors())
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFFA6A6), fontSize = 13.sp)
                    Button(onClick = {
                        error = when {
                            pin.length < 6 -> "Use at least 6 numbers."
                            isCreatingPin && pin != confirmation -> "The PINs don't match. Try again."
                            isCreatingPin -> { onSetPin(pin); pin = ""; confirmation = ""; "" }
                            else -> if (onUnlock(pin)) { pin = ""; "" } else "That PIN isn't correct. Try again."
                        }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                        Text(if (isCreatingPin) "Create locked vault" else "Unlock vault", fontWeight = FontWeight.Bold)
                    }
                    if (isCreatingPin) Text("Move finished downloads into the vault from the Downloads screen.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} ${if (items.size == 1) "video" else "videos"}", color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onLock) { Text("Lock vault", color = Mint) }
            }
            if (items.isEmpty()) EmptyState("Your vault is empty", "Go to Downloads and tap Move to vault on a finished file.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(items, key = { it.id }) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(Color(0xFF253843), CircleShape), contentAlignment = Alignment.Center) { Text("▶", color = Mint) }
                            Spacer(Modifier.width(10.dp))
                            Text(entry.title, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onOpen(entry) }) { Text("Play", color = Mint) }
                            TextButton(onClick = { onDelete(entry) }) { Text("Delete", color = Muted) }
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
                Text("Getting things ready…", color = Muted, fontSize = 14.sp)
                Text("This can take a little longer the first time.", color = Muted, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(12.dp))
                Text("Rantube couldn't start", color = Color.White, fontWeight = FontWeight.Bold)
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
        Modifier.fillMaxWidth().background(Ink).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        AppTab.entries.forEach { item ->
            Column(
                Modifier.clickable { onSelect(item) }.padding(horizontal = 7.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.mark, color = if (tab == item) Mint else Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(item.label, color = if (tab == item) Mint else Muted, fontSize = 10.sp)
            }
        }
    }
}

private const val VaultKeyAlias = "rantube.vault.aes.v1"

private fun vaultKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(VaultKeyAlias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(VaultKeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return generator.generateKey()
}

private fun moveToVault(context: Context, prefs: android.content.SharedPreferences, source: String, title: String): VaultEntry {
    val id = UUID.randomUUID().toString()
    val uri = Uri.parse(source)
    val displayName = if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } else File(source).name
    val extension = displayName?.substringAfterLast('.', "mp4")?.lowercase()?.ifBlank { "mp4" } ?: "mp4"
    val mime = context.contentResolver.getType(uri) ?: mimeTypeFor(File("media.$extension"))
    val entry = VaultEntry(id, title, mime, extension)
    val directory = File(context.filesDir, "vault").apply { mkdirs() }
    val encrypted = File(directory, "$id.rtv")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, vaultKey()) }
    DataOutputStream(FileOutputStream(encrypted)).use { output ->
        output.writeInt(cipher.iv.size)
        output.write(cipher.iv)
        CipherOutputStream(output, cipher).use { cipherOutput ->
            val input = if (uri.scheme == "content") context.contentResolver.openInputStream(uri) else FileInputStream(File(source))
            (input ?: error("Source file is missing")).use { it.copyTo(cipherOutput) }
        }
    }
    if (!encrypted.isFile || encrypted.length() <= 16) {
        encrypted.delete()
        error("Vault encryption failed")
    }
    val rows = prefs.getStringSet("vault_items", emptySet()).orEmpty().toMutableSet()
    rows.add(encodeVaultEntry(entry))
    prefs.edit().putStringSet("vault_items", rows).apply()
    val deleted = if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) > 0 else File(source).delete()
    if (!deleted) {
        encrypted.delete()
        rows.remove(encodeVaultEntry(entry))
        prefs.edit().putStringSet("vault_items", rows).apply()
        error("Could not remove the public file")
    }
    return entry
}

private fun decryptVaultFile(context: Context, entry: VaultEntry): File {
    val encrypted = File(File(context.filesDir, "vault"), "${entry.id}.rtv")
    require(encrypted.isFile) { "Vault item is missing" }
    val destination = File(context.cacheDir, "vault_${entry.id}.${entry.extension}")
    try {
        DataInputStream(FileInputStream(encrypted)).use { input ->
            val ivSize = input.readInt()
            require(ivSize in 12..16) { "Invalid vault file" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, iv)) }
            CipherInputStream(input, cipher).use { source -> FileOutputStream(destination).use { source.copyTo(it) } }
        }
        return destination
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}

private fun deleteVaultItem(context: Context, prefs: android.content.SharedPreferences, entry: VaultEntry) {
    File(File(context.filesDir, "vault"), "${entry.id}.rtv").delete()
    val rows = prefs.getStringSet("vault_items", emptySet()).orEmpty().toMutableSet()
    rows.remove(encodeVaultEntry(entry))
    prefs.edit().putStringSet("vault_items", rows).apply()
}

private fun encodeVaultEntry(entry: VaultEntry): String = listOf(
    entry.id,
    Base64.encodeToString(entry.title.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
    entry.mimeType,
    entry.extension
).joinToString("|")

private fun readVaultItems(prefs: android.content.SharedPreferences): List<VaultEntry> =
    prefs.getStringSet("vault_items", emptySet()).orEmpty().mapNotNull { row ->
        runCatching {
            val parts = row.split("|", limit = 4)
            require(parts.size == 4)
            VaultEntry(parts[0], String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8), parts[2], parts[3])
        }.getOrNull()
    }.sortedBy { it.title.lowercase() }

private fun setVaultPin(prefs: android.content.SharedPreferences, pin: String) {
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    val hash = pinHash(pin, salt)
    prefs.edit()
        .putString("vault_pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString("vault_pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
        .apply()
}

private fun verifyVaultPin(prefs: android.content.SharedPreferences, pin: String): Boolean {
    val salt = prefs.getString("vault_pin_salt", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return false
    val expected = prefs.getString("vault_pin_hash", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return false
    return MessageDigest.isEqual(expected, pinHash(pin, salt))
}

private fun pinHash(pin: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
    return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    bytes >= 1024L * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "opus" -> "audio/opus"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
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

private fun formatRemainingTime(seconds: Long): String = when {
    seconds < 60 -> "About ${seconds.coerceAtLeast(1)} sec left"
    seconds < 3600 -> "About ${seconds / 60} min left"
    else -> "About ${seconds / 3600} hr ${seconds % 3600 / 60} min left"
}

private fun updateDownload(
    downloads: MutableList<DownloadEntry>,
    id: String,
    transform: (DownloadEntry) -> DownloadEntry
) {
    val index = downloads.indexOfFirst { it.id == id }
    if (index >= 0) downloads[index] = transform(downloads[index])
}

private fun downloadDirectory(context: Context): File = File(
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
    "RantubeTemp"
)

private fun publishDownloadedFile(context: Context, source: File): Uri {
    require(source.isFile && source.length() > 0) { "Downloaded file is empty" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(source))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Rantube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: error("Could not create the saved file")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output -> FileInputStream(source).use { it.copyTo(output) } }
                ?: error("Could not write the saved file")
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, completed, null, null)
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
    val destination = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Rantube/${source.name}")
    destination.parentFile?.mkdirs()
    FileInputStream(source).use { input -> FileOutputStream(destination).use(input::copyTo) }
    MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mimeTypeFor(destination)), null)
    return Uri.fromFile(destination)
}

private fun scanDownloadedFiles(context: Context): List<DownloadEntry> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        return runCatching {
            context.contentResolver.query(collection, projection, selection, arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Rantube/"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                buildList {
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val uri = Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString())
                        val name = cursor.getString(nameIndex).orEmpty()
                        add(DownloadEntry(uri.toString(), File(name).nameWithoutExtension, "Complete", 100, uri.toString()))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    return emptyList()
}

private fun parseSavedUri(context: Context, saved: String): Uri? = runCatching {
    val parsed = Uri.parse(saved)
    if (parsed.scheme == "content") parsed else FileProvider.getUriForFile(context, "${context.packageName}.provider", File(saved))
}.getOrNull()

private fun openDownloadedFile(context: Context, saved: String): String? {
    val uri = parseSavedUri(context, saved) ?: return "Rantube can’t find that saved file. Look in Downloads/Rantube in the Files app."
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        null
    }.getOrElse { "No app on this device can open this media file. Check Downloads/Rantube in the Files app." }
}

private fun shareDownloadedFile(context: Context, saved: String): String? {
    val uri = parseSavedUri(context, saved) ?: return "Rantube can’t find that saved file to share."
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
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
