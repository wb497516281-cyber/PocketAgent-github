package com.pocket.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocket.agent.data.settings.ProviderConfig
import com.pocket.agent.data.settings.ProviderPreset
import com.pocket.agent.data.settings.ProviderPresets

/**
 * Provider management.
 *
 * Cards list what is configured; the editor is a full screen surface so the
 * API key field can use a password transformation without a cramped dialog.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val editor = state.editor
    if (editor != null) {
        ProviderEditor(
            editor = editor,
            isNew = editor.id == null,
            onChange = viewModel::updateEditor,
            onSave = viewModel::save,
            onTest = viewModel::testEditor,
            onFetchModels = viewModel::fetchModels,
            onSelectModel = viewModel::selectModel,
            onToggleVision = viewModel::setSupportsVision,
            onDismiss = viewModel::dismissEditor,
        )
        return
    }

    if (state.showPresetChooser) {
        PresetChooser(
            onPick = viewModel::choosePreset,
            onCustom = viewModel::startCustom,
            onDismiss = viewModel::dismissChooser,
        )
        return
    }

    SettingsList(
        state = state,
        modifier = modifier,
        onCreate = viewModel::startCreate,
        onEdit = viewModel::startEdit,
        onDelete = viewModel::delete,
        onSetDefault = viewModel::setDefault,
        onTest = viewModel::testConnection,
        onDismissResult = viewModel::dismissResult,
        onUpdateTavilyDraft = viewModel::updateTavilyKeyDraft,
        onSaveTavilyKey = viewModel::saveTavilyKey,
        onClearTavilyKey = viewModel::clearTavilyKey,
        onDismissTavilyStatus = viewModel::dismissTavilyStatus,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(
    state: SettingsUiState,
    onCreate: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onTest: (ProviderConfig) -> Unit,
    onDismissResult: (String) -> Unit,
    onUpdateTavilyDraft: (String) -> Unit,
    onSaveTavilyKey: () -> Unit,
    onClearTavilyKey: () -> Unit,
    onDismissTavilyStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ProviderConfig?>(null) }

    pendingDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 Provider？") },
            text = { Text("「${provider.label}」及其保存的 API Key 都会被移除，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(provider.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加 Provider") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.providers.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "还没有配置任何模型",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "点右下角「添加 Provider」，选一个主流服务商即可开始；OpenAI、DeepSeek、" +
                                "Qwen、Kimi、GLM、OpenRouter 等都已内置，只需填 API Key。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    isDefault = provider.id == state.defaultProviderId,
                    isTesting = provider.id in state.testingIds,
                    result = state.results[provider.id],
                    onEdit = { onEdit(provider) },
                    onDelete = { pendingDelete = provider },
                    onSetDefault = { onSetDefault(provider.id) },
                    onTest = { onTest(provider) },
                    onDismissResult = { onDismissResult(provider.id) },
                )
            }
            item(key = "search-config") {
                SearchConfigCard(
                    draft = state.tavilyKeyDraft,
                    saved = state.tavilyKeySaved,
                    busy = state.tavilyBusy,
                    status = state.tavilyStatus,
                    onDraftChange = onUpdateTavilyDraft,
                    onSave = onSaveTavilyKey,
                    onClear = onClearTavilyKey,
                    onDismissStatus = onDismissTavilyStatus,
                )
            }
        }
    }
}

/**
 * Tavily key management for the web_search tool.
 *
 * Lives below the provider list because it is one global setting rather than
 * per-provider configuration.
 */
@Composable
private fun SearchConfigCard(
    draft: String,
    saved: Boolean,
    busy: Boolean,
    status: String?,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "联网搜索配置",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "填写 Tavily API Key 后，Agent 在需要最新信息时会自动联网搜索。" +
                    "Key 只保存在本机加密存储中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let { message ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismissStatus, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text("Tavily API Key") },
                placeholder = { Text("tvly-...") },
                supportingText = {
                    Text(if (saved) "已保存，留空则保持不变" else "在 tavily.com 申请，免费额度即可试用")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave, enabled = !busy) { Text("保存") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onClear, enabled = saved && !busy) { Text("清除") }
                if (busy) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isDefault: Boolean,
    isTesting: Boolean,
    result: TestOutcome?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onTest: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name.ifBlank { provider.model },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = provider.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDefault) {
                    AssistChip(
                        onClick = onSetDefault,
                        label = { Text("默认") },
                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                } else {
                    TextButton(onClick = onSetDefault) { Text("设为默认") }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (provider.extraHeaders.isNotEmpty()) {
                Text(
                    text = "附加请求头：${provider.extraHeaders.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (provider.supportsVision) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "支持视觉输入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            result?.let { outcome ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = if (outcome.ok) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Icon(
                            imageVector = if (outcome.ok) Icons.Filled.Check else Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = outcome.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismissResult, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = !isTesting) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中")
                    } else {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试连接")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetChooser(
    onPick: (ProviderPreset) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = ProviderPresets.all.groupBy { it.region }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择服务商") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "custom") {
                PresetRow(
                    title = "自定义",
                    subtitle = "手动填写 Base URL 与模型名",
                    trailing = null,
                    onClick = onCustom,
                )
            }

            groups.forEach { (region, presets) ->
                item(key = "header-${region.name}") {
                    Text(
                        text = region.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                items(presets, key = { it.id }) { preset ->
                    PresetRow(
                        title = preset.displayName,
                        subtitle = preset.baseUrl,
                        trailing = preset.defaultModel,
                        onClick = { onPick(preset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    title: String,
    subtitle: String,
    trailing: String?,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
    editor: EditorState,
    isNew: Boolean,
    onChange: ((EditorState) -> EditorState) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onFetchModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleVision: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加 Provider" else "编辑 Provider") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = editor.name,
                onValueChange = { value -> onChange { it.copy(name = value) } },
                label = { Text("名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = editor.baseUrl,
                onValueChange = { value -> onChange { it.copy(baseUrl = value) } },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                supportingText = { Text("请求会发送到 {Base URL}/chat/completions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = editor.apiKey,
                onValueChange = { value -> onChange { it.copy(apiKey = value) } },
                label = { Text("API Key") },
                placeholder = if (editor.keyHint.isBlank()) {
                    null
                } else {
                    { Text(editor.keyHint) }
                },
                supportingText = {
                    Text(
                        if (editor.hasStoredKey && editor.apiKey.isBlank()) {
                            "已保存，留空则保持不变"
                        } else {
                            "保存在本机加密存储中"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (editor.availableModels.isNotEmpty()) {
                ModelDropdown(
                    models = editor.availableModels,
                    selected = editor.model,
                    onSelect = onSelectModel,
                )
            } else {
                OutlinedTextField(
                    value = editor.model,
                    onValueChange = { value -> onChange { it.copy(model = value) } },
                    label = { Text("模型名") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onFetchModels, enabled = !editor.isLoadingModels) {
                    if (editor.isLoadingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("获取中")
                    } else {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("获取模型")
                    }
                }
                if (editor.availableModels.isNotEmpty() && !editor.isLoadingModels) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onFetchModels) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("刷新")
                    }
                }
            }
            editor.modelsStatus?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (editor.modelsStatusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleVision(!editor.supportsVision) }
                    .padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = editor.supportsVision,
                    onCheckedChange = onToggleVision,
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text("支持视觉输入", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (editor.supportsVision) {
                            "图片会直接发给模型理解"
                        } else {
                            "图片只发文字：本机先识别图中文字，识别不到会提示切换模型"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editor.suggestedModels.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "常用模型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editor.suggestedModels.forEach { suggestion ->
                        val selected = suggestion == editor.model.trim()
                        val leading: (@Composable () -> Unit)? = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        }
                        AssistChip(
                            onClick = { onChange { it.copy(model = suggestion) } },
                            label = { Text(suggestion) },
                            leadingIcon = leading,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = editor.extraHeaders,
                onValueChange = { value -> onChange { it.copy(extraHeaders = value) } },
                label = { Text("附加请求头（可选）") },
                placeholder = { Text("HTTP-Referer: https://example.com") },
                supportingText = { Text("每行一条「名称: 值」，用于 OpenRouter 等需要额外头的服务") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            editor.note?.let { note ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            editor.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave) { Text("保存") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onTest) { Text("测试并保存") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Model picker that doubles as a search box.
 *
 * Real catalogues run to a hundred entries, so the anchor field is editable:
 * opening it starts a fresh query, typing filters the menu, and picking an
 * entry writes it straight into the draft. A typed name the catalogue does
 * not carry is offered as its own menu entry, so manual input stays possible
 * even after a successful fetch. Dismissing without a pick restores the
 * saved selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    val matches = if (searching) filterModelMatches(models, query) else models
    val typed = query.trim()
    val customName = typed.takeIf {
        searching && it.isNotEmpty() && models.none { model -> model.equals(it, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            // Opening starts a clean search; closing restores the pick.
            searching = open
            if (open) query = ""
        },
    ) {
        OutlinedTextField(
            value = if (searching) query else selected,
            onValueChange = { input ->
                query = input
                searching = true
                expanded = true
            },
            label = { Text("模型名") },
            placeholder = { Text("搜索模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                searching = false
            },
        ) {
            customName?.let { name ->
                DropdownMenuItem(
                    text = { Text("使用「$name」") },
                    onClick = {
                        onSelect(name)
                        searching = false
                        expanded = false
                    },
                )
            }
            if (matches.isEmpty() && customName == null) {
                DropdownMenuItem(
                    text = { Text("没有匹配的模型") },
                    enabled = false,
                    onClick = {},
                )
            }
            matches.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onSelect(model)
                        searching = false
                        expanded = false
                    },
                )
            }
        }
    }
}
