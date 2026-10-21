package info.varden.hauk.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.struct.Session
import info.varden.hauk.struct.Share
import info.varden.hauk.struct.ShareMode

/**
 * The location status displayed on the main screen.
 */
enum class LocationStatus(@StringRes val label: Int) {
    INACTIVE(R.string.label_status_none),
    WAITING(R.string.label_status_wait),
    SEARCHING(R.string.label_status_lost_gnss),
    COARSE(R.string.label_status_coarse),
    ACTIVE(R.string.label_status_ok),
}

/**
 * Observable state of the main screen.
 */
class MainUiState {
    // Inputs for creating a new session.
    var duration by mutableStateOf("")
    var durationUnit by mutableIntStateOf(Constants.DURATION_UNIT_MINUTES)
    var mode by mutableStateOf(ShareMode.CREATE_ALONE)
    var allowAdoption by mutableStateOf(true)
    var nickname by mutableStateOf("")
    var groupPin by mutableStateOf("")

    var showLogo by mutableStateOf(true)

    /**
     * Whether the session inputs are read-only because a session is running or being started.
     */
    var inputsLocked by mutableStateOf(false)

    /**
     * Whether the start/stop button is disabled while a session is being started.
     */
    var busy by mutableStateOf(false)

    /**
     * The expiry time of the active session in milliseconds since the epoch, or null if sharing is
     * inactive.
     */
    var sessionExpiry by mutableStateOf<Long?>(null)

    /**
     * The active session if additional links can be created for it, or null otherwise.
     */
    var linkSession by mutableStateOf<Session?>(null)

    /**
     * The group share hosted by the user, if any. Used to display the group PIN.
     */
    var groupShare by mutableStateOf<Share?>(null)

    var status by mutableStateOf(LocationStatus.INACTIVE)
    var serverDisconnected by mutableStateOf(false)

    /**
     * The shares that the user is currently contributing to.
     */
    val links = mutableStateListOf<Share>()
}
