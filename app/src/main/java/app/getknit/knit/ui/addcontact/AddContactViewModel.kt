package app.getknit.knit.ui.addcontact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.mesh.crypto.ContactCard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Add-contact screen shows. */
sealed interface AddContactUiState {
    /** Waiting for a link to be typed, pasted, or handed in. */
    data object Idle : AddContactUiState

    /** A parsed card, previewed before the user commits. */
    data class Preview(
        val ready: ContactImporter.Preview.Ready,
    ) : AddContactUiState

    data object Importing : AddContactUiState
}

/** One-shot outcomes for the screen's snackbar / navigation. */
sealed interface AddContactEvent {
    data class Imported(
        val nodeId: String,
    ) : AddContactEvent

    data object Self : AddContactEvent

    data class Mismatch(
        val displayName: String,
    ) : AddContactEvent

    data object Invalid : AddContactEvent
}

/**
 * Backs the Add-contact screen (docs/CONTACT_CARD.md): a link arrives from the [ContactCardInbox] (a
 * tapped `getknit.app/c` link, or a shared text) or is pasted into the field; [lookup] previews it and
 * [confirm] imports it through [ContactImporter]. Holds no `Context` — every string is resolved by the
 * screen from state — so it drives under plain JVM tests. The inbox is *observed*, not read once, so a
 * second link arriving while the screen is up (`launchSingleTop`) still loads.
 */
class AddContactViewModel(
    private val inbox: ContactCardInbox,
    private val importer: ContactImporter,
) : ViewModel() {
    val input = MutableStateFlow("")

    private val _state = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val state: StateFlow<AddContactUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AddContactEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AddContactEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            inbox.pending.collect { text ->
                if (text == null) return@collect
                inbox.consume()
                input.value = text
                lookup()
            }
        }
    }

    fun setInput(text: String) {
        input.value = text
        if (_state.value is AddContactUiState.Preview) _state.value = AddContactUiState.Idle
    }

    /** Parses and previews whatever is in the field. */
    fun lookup() {
        viewModelScope.launch {
            when (val preview = importer.preview(ContactCard.parse(input.value))) {
                is ContactImporter.Preview.Ready -> _state.value = AddContactUiState.Preview(preview)
                is ContactImporter.Preview.Self -> _events.tryEmit(AddContactEvent.Self)
                is ContactImporter.Preview.Mismatch -> _events.tryEmit(AddContactEvent.Mismatch(preview.displayName))
                is ContactImporter.Preview.Invalid -> _events.tryEmit(AddContactEvent.Invalid)
            }
        }
    }

    /** Imports the previewed card; [unblock] lifts an existing block first. */
    fun confirm(unblock: Boolean = false) {
        val preview = (_state.value as? AddContactUiState.Preview)?.ready ?: return
        _state.value = AddContactUiState.Importing
        viewModelScope.launch {
            importer.import(preview, unblock)
            _events.tryEmit(AddContactEvent.Imported(preview.nodeId))
            _state.value = AddContactUiState.Idle
        }
    }
}
