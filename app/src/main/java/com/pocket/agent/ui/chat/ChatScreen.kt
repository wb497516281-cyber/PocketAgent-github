package com.pocket.agent.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocket.agent.agent.tools.DOCX_MIME_TYPE
import com.pocket.agent.agent.tools.PDF_MIME_TYPE
import com.pocket.agent.agent.tools.PPTX_MIME_TYPE
import com.pocket.agent.data.chat.ImageAttachment
import com.pocket.agent.data.chat.ToolCall
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.ui.theme.Amber200
import com.pocket.agent.ui.theme.DisabledContent
import com.pocket.agent.ui.theme.DisabledSurface
import com.pocket.agent.ui.theme.GlassBorder
import com.pocket.agent.ui.theme.GlassFill
import com.pocket.agent.ui.theme.GlassFillRaised
import com.pocket.agent.ui.theme.Violet300
import com.pocket.agent.ui.theme.Violet500
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The conversation screen.
 *
 * Everything the agent does arrives through one [ChatItem] stream, so the
 * transcript is a single LazyColumn that mixes user text, streamed assistant
 * text, tool call cards and error cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    currentDirName: String?,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // The system photo picker needs no storage permission; whatever it hands
    // back is compressed before it ever reaches the state.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::onImagePicked)
    }

    val lastItem = state.items.lastOrNull()
    val streamingLength = (lastItem as? ChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(state.items.size, streamingLength) {
        val lastIndex = state.items.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: lastIndex
        // Follow the stream, but never yank the view while the user scrolls back.
        if (lastVisible >= lastIndex - 2) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Pocket Agent",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearConversation) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空对话")
                        }
                    }
                    ProviderPicker(
                        providers = state.providers,
                        selected = state.selectedProvider,
                        onSelect = viewModel::selectProvider,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = {
            Composer(
                input = state.input,
                isRunning = state.isRunning,
                canSend = state.canSend,
                currentDirName = currentDirName,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onPickDir = onOpenFiles,
                pendingImages = state.pendingImages,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemoveImage = viewModel::removePendingImage,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.items.isEmpty()) {
                EmptyConversation(
                    hasProvider = state.selectedProvider != null,
                    hasDirectory = currentDirName != null,
                    onOpenSettings = onOpenSettings,
                    onOpenFiles = onOpenFiles,
                    onExampleClick = viewModel::onInputChange,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        when (item) {
                            is ChatItem.User -> UserBubble(item.text, item.images)
                            is ChatItem.Assistant -> AssistantBubble(item.text, item.streaming)
                            is ChatItem.System -> SystemNote(item.text)
                            is ChatItem.Tool -> ToolCallCard(item)
                            is ChatItem.Error -> ErrorCard(item.message, onRetry = viewModel::retry)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.banner != null,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                state.banner?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = viewModel::dismissBanner, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingConfirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConfirmation(false) },
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveConfirmation(true) }) {
                    Text(request.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveConfirmation(false) }) {
                    Text(request.denyLabel)
                }
            },
        )
    }
}

@Composable
private fun ProviderPicker(
    providers: List<ProviderConfig>,
    selected: ProviderConfig?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(GlassFillRaised)
                .clickable(enabled = providers.isNotEmpty()) { expanded = true }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .widthIn(max = 190.dp),
        ) {
            Text(
                text = selected?.label ?: "选择模型",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        onSelect(provider.id)
                        expanded = false
                    },
                    trailingIcon = {
                        if (provider.id == selected?.id) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    hasProvider: Boolean,
    hasDirectory: Boolean,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pocket Agent",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "告诉它要做什么，它会通过工具在你选择的目录里读写文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (!hasProvider) {
            TextButton(onClick = onOpenSettings) { Text("去设置里添加模型") }
        }
        if (!hasDirectory) {
            TextButton(onClick = onOpenFiles) { Text("去选择保存目录") }
        }
    }
}

/** The brand tile: a violet gradient square carrying the app initial. */
@Composable
private fun BrandMark(size: Dp = 28.dp, corner: Dp = 9.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Violet500, Violet300))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "P",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.5f).sp),
        )
    }
}

/** A starter prompt: the card names the intent, the prompt fills the input. */
private data class ExampleTask(
    val icon: ImageVector,
    val title: String,
    val prompt: String,
)

private val EXAMPLE_TASKS = listOf(
    ExampleTask(
        icon = Icons.Filled.Image,
        title = "带文字的截图，整理成 Word 周报",
        prompt = "我发一张截图给你，请读出里面的文字，整理成一份 Android 开发周报，保存为 Word 文档",
    ),
    ExampleTask(
        icon = Icons.Filled.Slideshow,
        title = "做一份会自动配图的 PPT",
        prompt = "做一份 Android 开发周报 PPT，每一页都要配上和内容相符的插图，保存为 PPT",
    ),
    ExampleTask(
        icon = Icons.Filled.Search,
        title = "查今天最新的 AI 新闻",
        prompt = "今天有什么 AI 新闻？先联网搜索，再给我总结成三条要点",
    ),
)

@Composable
private fun ExampleTaskCard(task: ExampleTask, onClick: () -> Unit) {
    Surface(
        color = GlassFill,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassFillRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    task.icon,
                    contentDescription = null,
                    tint = Violet300,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EmptyConversation(
    hasProvider: Boolean,
    hasDirectory: Boolean,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onExampleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(size = 56.dp, corner = 18.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Pocket Agent",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "读图、写文档、查网页，都在一个对话框里完成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            EXAMPLE_TASKS.forEach { task ->
                ExampleTaskCard(task = task, onClick = { onExampleClick(task.prompt) })
                Spacer(Modifier.height(10.dp))
            }
            if (!hasProvider || !hasDirectory) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasProvider) {
                        TextButton(onClick = onOpenSettings) { Text("添加模型") }
                    }
                    if (!hasDirectory) {
                        TextButton(onClick = onOpenFiles) { Text("选择目录") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, images: List<ImageAttachment>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // A turn rarely carries more than a few photos; four
                        // keeps the bubble readable and implies the rest.
                        images.take(4).forEach { image ->
                            ImageThumbnail(
                                base64 = image.base64,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                }
                if (text.isNotBlank()) {
                    if (images.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Decodes an attachment back into a thumbnail.
 *
 * The payload is the compressed base64 the compressor produced, so decoding is
 * cheap and happens on the render thread only for what is on screen. A payload
 * that refuses to decode still keeps its slot, rendered as a muted block.
 */
@Composable
private fun ImageThumbnail(base64: String, modifier: Modifier = Modifier) {
    val bitmap = remember(base64) {
        runCatching {
            val bytes = Base64.getDecoder().decode(base64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** An app-written note: OCR fallbacks, declined overwrites, guidance. */
@Composable
private fun SystemNote(text: String) {
    // Deliberately not a card, so it never reads like something the model said.
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Compressed photos waiting to go out with the next turn. */
@Composable
private fun PendingImageRow(
    images: List<ImageAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Box(modifier = Modifier.size(60.dp)) {
                ImageThumbnail(
                    base64 = image.base64,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onRemove(image.uri) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除图片",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = GlassFillRaised,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            border = BorderStroke(1.dp, GlassBorder),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                if (streaming && text.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("思考中", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(item: ChatItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (item.status) {
        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolCallStatus.DONE -> MaterialTheme.colorScheme.secondary
        ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = GlassFill,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    ToolCallStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                    )

                    ToolCallStatus.DONE -> Icon(
                        Icons.Filled.Check,
                        contentDescription = "完成",
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
                    )

                    ToolCallStatus.FAILED -> Icon(
                        Icons.Filled.Warning,
                        contentDescription = "失败",
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = toolTitle(item.call),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (item.status) {
                        ToolCallStatus.RUNNING ->
                            if (item.call.name in DOCUMENT_TOOLS) "文档生成中" else "执行中"
                        ToolCallStatus.DONE -> "完成"
                        ToolCallStatus.FAILED -> "失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }

            item.result?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded) result else result.take(160) + if (result.length > 160) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 220.dp),
                )
                if (result.length > 160) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            val fileUri = item.fileUri
            if (fileUri != null && item.status == ToolCallStatus.DONE && !item.isError) {
                Spacer(Modifier.height(8.dp))
                OpenFileButton(uri = fileUri)
            }
        }
    }
}

/**
 * Offers a freshly generated document to the system.
 *
 * Rendering a PDF or a deck can take a second, so the result arrives with the
 * SAF uri attached and the user decides when to open it. A missing viewer app
 * or a revoked grant comes back as inline text instead of a crash.
 */
@Composable
private fun OpenFileButton(uri: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        TextButton(
            onClick = { error = openGeneratedFile(context, uri) },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("打开文件", style = MaterialTheme.typography.labelMedium)
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Hands a document to whatever app can show it; null means it launched. */
private fun openGeneratedFile(context: Context, uriString: String): String? {
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeFor(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "没有找到能打开该文件的应用"
    } catch (e: SecurityException) {
        "文件不可访问，可能是授权已失效"
    }
}

private fun mimeTypeFor(uri: Uri): String {
    val name = uri.lastPathSegment?.lowercase().orEmpty()
    return when {
        name.endsWith(".pdf") -> PDF_MIME_TYPE
        name.endsWith(".docx") -> DOCX_MIME_TYPE
        name.endsWith(".pptx") -> PPTX_MIME_TYPE
        else -> "*/*"
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = "重试")
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    isRunning: Boolean,
    canSend: Boolean,
    currentDirName: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickDir: () -> Unit,
    pendingImages: List<ImageAttachment> = emptyList(),
    onPickImage: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
) {
    // The capsule floats on the bare background rather than sitting on a
    // filled bar, which is what gives the shell its layered feel.
    Surface(color = Color.Transparent) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (currentDirName == null) {
                Surface(
                    color = Amber200.copy(alpha = 0.14f),
                    contentColor = Amber200,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onPickDir),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("先选择保存目录", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            PendingImageRow(
                images = pendingImages,
                onRemove = onRemoveImage,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, GlassBorder),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    IconButton(
                        onClick = onPickImage,
                        enabled = !isRunning,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) {
                                    DisabledSurface.copy(alpha = 0.4f)
                                } else {
                                    GlassFillRaised
                                },
                            ),
                    ) {
                        Icon(
                            Icons.Filled.AddPhotoAlternate,
                            contentDescription = "选择图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(Violet300),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                        maxLines = 6,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 150.dp),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "说点什么，或者让 Agent 帮你做点事",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    if (isRunning) {
                        FilledIconButton(
                            onClick = onStop,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Grey while the turn has nothing worth sending, violet
                        // the moment it does.
                        FilledIconButton(
                            onClick = onSend,
                            enabled = canSend,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Violet500,
                                contentColor = Color.White,
                                disabledContainerColor = DisabledSurface,
                                disabledContentColor = DisabledContent,
                            ),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Tools whose running state reads better as "document being generated". */
private val DOCUMENT_TOOLS = setOf("create_pdf", "create_word", "create_ppt")

/** Short, human readable label for a tool call card. */
private fun toolTitle(call: ToolCall): String {
    val name = call.name.ifBlank { "工具" }
    val fileName = runCatching {
        Json.parseToJsonElement(call.arguments).jsonObject["file_name"]?.jsonPrimitive?.content
    }.getOrNull()
    return if (fileName.isNullOrBlank()) name else "$name · $fileName"
}
