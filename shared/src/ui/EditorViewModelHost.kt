package app.servicecalendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Holds the editor's ViewModels in the Activity store so they survive rotation; [close] clears them once the editor is finished. */
class EditorViewModelHost : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun close() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}

class EditorViewModelScope internal constructor(val owner: EditorViewModelHost) {
    internal var finished = false

    fun finish() {
        finished = true
    }
}

@Composable
fun rememberEditorViewModelScope(hostKey: String): EditorViewModelScope {
    val host = viewModel(key = hostKey) { EditorViewModelHost() }
    val scope = remember(host) { EditorViewModelScope(host) }
    val current by rememberUpdatedState(scope)
    DisposableEffect(host) {
        onDispose { if (current.finished) host.close() }
    }
    return scope
}
