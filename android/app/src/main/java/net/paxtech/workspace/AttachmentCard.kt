package net.paxtech.workspace

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import net.paxtech.workspace.data.AttachmentContent
import net.paxtech.workspace.session.*

internal val LocalAttachmentLoader =
    staticCompositionLocalOf<suspend (ChatAttachment) -> AttachmentContent> {
        { error("Open this attachment from its session.") }
    }

@Composable
internal fun AttachmentCard(attachment: ChatAttachment) {
    val load = LocalAttachmentLoader.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember(attachment) { mutableStateOf<AttachmentContent?>(null) }
    var loading by remember(attachment) { mutableStateOf(false) }
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    var fullscreen by remember(attachment) { mutableStateOf(false) }
    var saved by remember(attachment) { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(content?.mimeType ?: "application/octet-stream")
        ) { uri ->
            val current = content
            if (uri != null && current != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(current.bytes)
                            } ?: error("Could not open the save location.")
                        }
                        saved = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = "Could not save this file. Try another location."
                    }
                }
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("attachment:${attachment.name}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(content?.name ?: attachment.name, style = MaterialTheme.typography.titleSmall)
            Text(
                content?.let { "${it.mimeType} · ${formatFileSize(it.bytes.size)}" }
                    ?: attachment.mimeType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val loaded = content
            if (loaded != null) {
                if (loaded.mimeType in rasterImageTypes) {
                    AttachmentImage(
                        loaded,
                        Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 280.dp).clickable {
                            fullscreen = true
                        },
                        onError = {
                            error =
                                "This image could not be previewed. You can still save the file."
                        },
                    )
                    TextButton(onClick = { fullscreen = true }) { Text("View full image") }
                } else if (
                    loaded.mimeType in setOf("text/plain", "text/markdown", "application/json")
                ) {
                    SelectionContainer {
                        Text(
                            remember(loaded) {
                                loaded.bytes
                                    .copyOfRange(0, minOf(loaded.bytes.size, 16000))
                                    .toString(Charsets.UTF_8)
                            },
                            Modifier.fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { save.launch(loaded.name) }) { Text("Save file") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val uri =
                                        withContext(Dispatchers.IO) {
                                            val dir =
                                                File(context.cacheDir, "shared-attachments").apply {
                                                    mkdirs()
                                                }
                                            dir.listFiles()
                                                ?.filter {
                                                    System.currentTimeMillis() - it.lastModified() >
                                                        86400000
                                                }
                                                ?.forEach { it.delete() }
                                            val file =
                                                File(
                                                    dir,
                                                    "${UUID.randomUUID()}-${attachmentName(loaded.name)}",
                                                )
                                            file.writeBytes(loaded.bytes)
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.attachments",
                                                file,
                                            )
                                        }
                                    val intent =
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, loaded.mimeType)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    intent.clipData = ClipData.newRawUri(loaded.name, uri)
                                    context.startActivity(Intent.createChooser(intent, "Open file"))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    error =
                                        "Could not open this file. Save it and open it with a compatible app."
                                }
                            }
                        }
                    ) {
                        Text("Open with…")
                    }
                }
                if (saved) Text("File saved", style = MaterialTheme.typography.labelSmall)
            } else {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                content = load(attachment)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not load this file."
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) {
                    Text(
                        if (loading) "Loading…"
                        else if (error != null) "Retry"
                        else if (attachment.mimeType in rasterImageTypes) "Load image"
                        else "Load file"
                    )
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (fullscreen && content != null) {
        val loaded = content!!
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { fullscreen = false }) { Text("Close preview") }
                        TextButton(onClick = { save.launch(loaded.name) }) { Text("Save file") }
                    }
                    Text(
                        loaded.name,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    var scale by remember { mutableFloatStateOf(1f) }
                    var x by remember { mutableFloatStateOf(0f) }
                    var y by remember { mutableFloatStateOf(0f) }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(loaded) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                x =
                                    (x + pan.x).coerceIn(
                                        -size.width * (scale - 1) / 2,
                                        size.width * (scale - 1) / 2,
                                    )
                                y =
                                    (y + pan.y).coerceIn(
                                        -size.height * (scale - 1) / 2,
                                        size.height * (scale - 1) / 2,
                                    )
                            }
                        }
                    ) {
                        AttachmentImage(
                            loaded,
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = x
                                translationY = y
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            scale = 1f
                            x = 0f
                            y = 0f
                        }
                    ) {
                        Text("Reset zoom")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentImage(
    content: AttachmentContent,
    modifier: Modifier,
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val request =
        remember(content, context) {
            ImageRequest.Builder(context)
                .data(content.bytes)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .size(2048)
                .build()
        }
    var decoded by remember(content) { mutableStateOf(false) }
    var failed by remember(content) { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(
            model = request,
            contentDescription = content.name,
            modifier =
                Modifier.fillMaxSize().semantics {
                    stateDescription =
                        if (decoded) "Image loaded"
                        else if (failed) "Preview unavailable" else "Loading image"
                },
            onSuccess = { decoded = true },
            onError = {
                failed = true
                onError()
            },
        )
        if (!decoded && !failed)
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
    }
}

private fun formatFileSize(bytes: Int): String =
    if (bytes < 1024) "$bytes B"
    else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"
