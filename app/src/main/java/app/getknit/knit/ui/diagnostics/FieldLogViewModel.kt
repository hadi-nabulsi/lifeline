package app.getknit.knit.ui.diagnostics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldLogExport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FieldLogUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    // Lines in the file as it would be shared now, the header aside.
    val events: Int = 0,
    val bytes: Long = 0,
    // The head of the file, as the user would send it.
    val preview: List<String> = emptyList(),
    // Lines past the preview, so the screen can say there is more.
    val more: Int = 0,
)

/**
 * Backs the Field log screen (ADR 2026-10.a239): what the log holds, the file the user would send, and the
 * switch and Clear. Nothing here transmits — [stageForShare] returns a Uri for the system share sheet, and the
 * user picks where it goes.
 */
class FieldLogViewModel(
    private val log: FieldLog,
    private val export: FieldLogExport,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(FieldLogUiState())
    val state: StateFlow<FieldLogUiState> = _state.asStateFlow()

    // One-shot snackbar feedback as a string-resource id, matching CrashLogViewModel's channel.
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val enabled = settings.fieldLogEnabled.first()
            val doc = export.render()
            val lines = doc.text.lines().filter { it.isNotEmpty() }
            _state.value =
                FieldLogUiState(
                    loading = false,
                    enabled = enabled,
                    events = doc.events,
                    bytes = doc.text.length.toLong(),
                    preview = lines.take(PREVIEW_LINES),
                    more = (lines.size - PREVIEW_LINES).coerceAtLeast(0),
                )
        }
    }

    fun onShareFailed() {
        _events.tryEmit(R.string.crash_share_error)
    }

    suspend fun stageForShare(): Uri? = export.stage()

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            log.setEnabled(value) { settings.setFieldLogEnabled(it) }
            refresh()
        }
    }

    fun clear() {
        viewModelScope.launch {
            log.clear()
            _events.tryEmit(R.string.field_log_cleared)
            refresh()
        }
    }

    private companion object {
        /** Enough to read what a line looks like without laying out a mebibyte of text. */
        const val PREVIEW_LINES = 200
    }
}
