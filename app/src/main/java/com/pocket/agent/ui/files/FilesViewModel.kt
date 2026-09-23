package com.pocket.agent.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.pocket.agent.data.file.SafFileEntry
import com.pocket.agent.data.file.SafFileRepository
import com.pocket.agent.util.AppContainer
import com.pocket.agent.util.FileNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An overwrite waiting for the user's answer. */
data class PendingOverwrite(
    val fileName: String,
    val content: String,
    val mimeType: String,
    val existingName: String,
)

/** Read-only view of a file's text. */
data class FilePreview(val name: String, val content: String)

data class FilesUiState(
    val dirUri: String? = null,
    val dirName: String? = null,
    val entries: List<SafFileEntry> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val pendingOverwrite: PendingOverwrite? = null,
    val pendingDelete: SafFileEntry? = null,
    val preview: FilePreview? = null,
) {
    val hasDirectory: Boolean get() = dirUri != null
}

/**
 * Backs the files screen.
 *
 * Every mutation funnels through [report] so a repository failure surfaces as a
 * message instead of an exception, and the listing is re-read after each change
 * because a DataStore write does not imply the tree itself changed.
 */
class FilesViewModel(
    private val fileRepository: SafFileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val uri = fileRepository.getCurrentDirUri()
            if (uri == null) {
                _uiState.update { it.copy(dirUri = null, dirName = null, entries = emptyList()) }
                return@launch
            }
            val entries = runCatching { fileRepository.listFiles(uri) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    dirUri = uri.toString(),
                    dirName = fileRepository.currentDirName(),
                    entries = entries,
                )
            }
        }
    }

    /** Called with the tree URI returned by `ACTION_OPEN_DOCUMENT_TREE`. */
    fun onDirectoryPicked(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val granted = runCatching { fileRepository.setCurrentDir(treeUri) }.getOrDefault(false)
            _uiState.update { it.copy(isBusy = false) }
            if (!granted) {
                report("该目录没有授权可持久化访问，请换一个目录重试", isError = true)
                return@launch
            }
            refresh()
        }
    }

    fun createFile(fileName: String, content: String, mimeType: String) {
        val nameError = FileNames.error(fileName)
        if (nameError != null) {
            report(nameError, isError = true)
            return
        }
        val trimmedMime = mimeType.trim().ifEmpty { SafFileRepository.DEFAULT_MIME_TYPE }
        viewModelScope.launch {
            val dirUri = fileRepository.getCurrentDirUri()
            if (dirUri == null) {
                report("请先选择保存目录", isError = true)
                return@launch
            }
            val existing = runCatching { fileRepository.findFile(dirUri, fileName) }.getOrNull()
            if (existing != null && !existing.isDirectory) {
                _uiState.update {
                    it.copy(
                        pendingOverwrite = PendingOverwrite(
                            fileName = fileName.trim(),
                            content = content,
                            mimeType = trimmedMime,
                            existingName = existing.name,
                        ),
                    )
                }
                return@launch
            }
            write(dirUri, fileName.trim(), content, trimmedMime)
        }
    }

    fun resolveOverwrite(approved: Boolean) {
        val pending = _uiState.value.pendingOverwrite ?: return
        _uiState.update { it.copy(pendingOverwrite = null) }
        if (!approved) return
        viewModelScope.launch {
            val dirUri = fileRepository.getCurrentDirUri()
            if (dirUri == null) {
                report("请先选择保存目录", isError = true)
                return@launch
            }
            write(dirUri, pending.fileName, pending.content, pending.mimeType, overwrite = true)
        }
    }

    fun deleteFile(entry: SafFileEntry) {
        viewModelScope.launch {
            val deleted = runCatching { fileRepository.delete(entry.uri) }.getOrDefault(false)
            if (deleted) {
                report("已删除 ${entry.name}", isError = false)
                refresh()
            } else {
                report("删除 ${entry.name} 失败", isError = true)
            }
        }
    }

    fun requestDelete(entry: SafFileEntry) {
        _uiState.update { it.copy(pendingDelete = entry) }
    }

    fun resolveDelete(approved: Boolean) {
        val entry = _uiState.value.pendingDelete ?: return
        _uiState.update { it.copy(pendingDelete = null) }
        if (approved) deleteFile(entry)
    }

    fun openFile(entry: SafFileEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch {
            val text = runCatching { fileRepository.readText(entry.uri) }
                .getOrElse { error ->
                    report(error.message ?: "无法读取 ${entry.name}", isError = true)
                    return@launch
                }
            _uiState.update { it.copy(preview = FilePreview(entry.name, text)) }
        }
    }

    fun closePreview() {
        _uiState.update { it.copy(preview = null) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun write(
        dirUri: Uri,
        fileName: String,
        content: String,
        mimeType: String,
        overwrite: Boolean = false,
    ) {
        _uiState.update { it.copy(isBusy = true) }
        val result = runCatching {
            fileRepository.createFile(
                dirUri = dirUri,
                fileName = fileName,
                content = content,
                mimeType = mimeType,
                overwrite = overwrite,
            )
        }
        _uiState.update { it.copy(isBusy = false) }
        result.fold(
            onSuccess = { entry ->
                report("已创建 ${entry.name}", isError = false)
                refresh()
            },
            onFailure = { error -> report(error.message ?: "创建失败", isError = true) },
        )
    }

    private fun report(message: String, isError: Boolean) {
        _uiState.update { it.copy(message = message, isError = isError) }
    }

    companion object {

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(
                    fileRepository = container.fileRepository,
                ) as T
            }
    }
}
