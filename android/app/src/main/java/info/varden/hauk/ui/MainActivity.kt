package info.varden.hauk.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.dialog.Buttons
import info.varden.hauk.dialog.CustomDialogBuilder
import info.varden.hauk.dialog.DialogHost
import info.varden.hauk.dialog.DialogHostOwner
import info.varden.hauk.dialog.DialogService
import info.varden.hauk.dialog.HostedDialog
import info.varden.hauk.dialog.StopSharingConfirmationPrompt
import info.varden.hauk.http.AdoptSharePacket
import info.varden.hauk.http.NewLinkPacket
import info.varden.hauk.http.SessionInitiationPacket
import info.varden.hauk.manager.GNSSStatusUpdateListener
import info.varden.hauk.manager.PromptCallback
import info.varden.hauk.manager.SessionInitiationReason
import info.varden.hauk.manager.SessionInitiationResponseHandler
import info.varden.hauk.manager.SessionListener
import info.varden.hauk.manager.SessionManager
import info.varden.hauk.manager.ShareListener
import info.varden.hauk.struct.Session
import info.varden.hauk.struct.Share
import info.varden.hauk.struct.ShareMode
import info.varden.hauk.struct.Version
import info.varden.hauk.system.powersaving.DeviceChecker
import info.varden.hauk.system.preferences.PreferenceManager
import info.varden.hauk.system.preferences.ui.SettingsActivity
import info.varden.hauk.ui.theme.HaukTheme
import info.varden.hauk.utils.DeprecationMigrator
import info.varden.hauk.utils.Log
import info.varden.hauk.utils.TimeUtils

/**
 * The main activity for Hauk.
 *
 * @author Marius Lindvall
 */
class MainActivity : AppCompatActivity(), DialogHostOwner {

    override val dialogHost = DialogHost()

    /**
     * The state rendered by [MainScreen].
     */
    private val state = MainUiState()

    /**
     * A helper utility class for displaying dialog windows/message boxes.
     */
    private lateinit var dialogSvc: DialogService

    /**
     * A runnable task that resets the UI to a fresh state.
     */
    private val uiResetTask = Runnable { resetUI() }

    /**
     * A callback that is run when sharing is stopped to reset the UI to a fresh state.
     */
    private lateinit var uiStopTask: StopSharingUICallback

    /**
     * An instance that manages all sessions and shares.
     */
    private lateinit var manager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure that all deprecated preferences have been migrated before we continue.
        DeprecationMigrator(this).migrate()

        Log.i("Creating main activity") //NON-NLS
        setClassVariables()
        loadPreferences()

        setContent {
            HaukTheme {
                MainScreen(this.state, Actions())
                this.dialogHost.Render()
            }
        }

        this.manager.resumeShares { ctx, session, shares, response ->
            // Prompt the user to continue the session.
            DialogService(ctx).showDialog(
                R.string.resume_title,
                String.format(ctx.getString(R.string.resume_body), shares.size, session.expiryString),
                Buttons.Two.YES_NO,
                ResumeDialogBuilder(response),
            )
        }

        // Check for aggressive power saving devices and warn the user if applicable.
        DeviceChecker(this).performCheck()
    }

    override fun onDestroy() {
        this.uiStopTask.setActivityDestroyed()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        // Set app logo visibility.
        this.state.showLogo = !PreferenceManager(this).get(Constants.PREF_HIDE_LOGO)
    }

    /**
     * Initializes class-level variables for usage in this activity.
     */
    private fun setClassVariables() {
        Log.d("Setting class variables") //NON-NLS
        this.dialogSvc = DialogService(this)
        this.uiStopTask = StopSharingUICallback(this, this.uiResetTask)

        this.manager = object : SessionManager(this, this.uiStopTask) {
            override fun requestLocationPermission() {
                // Show a rationale first before requesting location permission, giving users the
                // chance to cancel the request if they so desire. Users are informed that they must
                // click the "start sharing" button again after they have granted the permission.
                this@MainActivity.dialogSvc.showDialog(R.string.req_perms_title, R.string.req_perms_message, PermissionRequestExecutionTask(), this@MainActivity.uiResetTask)
            }
        }

        this.manager.attachStatusListener(StatusListener())
        this.manager.attachShareListener(ShareListenerImpl())
        this.manager.attachSessionListener(SessionListenerImpl())
    }

    /**
     * Loads preferences from storage and applies them to the UI.
     */
    @SuppressLint("WrongConstant")
    private fun loadPreferences() {
        Log.i("Loading preferences...") //NON-NLS
        val prefs = PreferenceManager(this)
        this.state.duration = prefs.get(Constants.PREF_DURATION).toString()
        this.state.nickname = prefs.get(Constants.PREF_NICKNAME)
        this.state.durationUnit = prefs.get(Constants.PREF_DURATION_UNIT)
        this.state.allowAdoption = prefs.get(Constants.PREF_ALLOW_ADOPTION)

        // Set night mode preference.
        AppCompatDelegate.setDefaultNightMode(prefs.get(Constants.PREF_NIGHT_MODE).resolve())
        // Set app logo visibility.
        this.state.showLogo = !prefs.get(Constants.PREF_HIDE_LOGO)
    }

    /**
     * On-tap handler for the "start sharing" and "stop sharing" button.
     */
    private fun startSharing() {
        val prefs = PreferenceManager(this)

        // If there is an executable stop task, that means that sharing is already active. Shut down
        // the share by running the stop task instead of starting a new share.
        if (this.manager.isSessionActive) {
            Log.i("Sharing is being stopped from main activity") //NON-NLS
            stopSharing(prefs)
            return
        }

        // Disable the UI while we attempt to connect to the Hauk backend.
        this.state.busy = true
        this.state.inputsLocked = true

        var server = prefs.get(Constants.PREF_SERVER_ENCRYPTED).trim()
        val username = prefs.get(Constants.PREF_USERNAME_ENCRYPTED).trim()
        val password = prefs.get(Constants.PREF_PASSWORD_ENCRYPTED)
        val interval = prefs.get(Constants.PREF_INTERVAL)
        val minDistance = prefs.get(Constants.PREF_UPDATE_DISTANCE)
        val customID = prefs.get(Constants.PREF_CUSTOM_ID).trim()
        val useE2E = prefs.get(Constants.PREF_ENABLE_E2E)
        val e2ePass = if (useE2E) prefs.get(Constants.PREF_E2E_PASSWORD) else ""
        val nickname = this.state.nickname.trim()
        val mode = this.state.mode
        val groupPin = this.state.groupPin
        val allowAdoption = this.state.allowAdoption
        val durUnit = this.state.durationUnit

        server = if (server.endsWith("/")) server else "$server/"

        // Save connection preferences for next launch, so the user doesn't have to enter URL etc.
        // every time.
        Log.i("Updating connection preferences") //NON-NLS
        prefs.set(Constants.PREF_DURATION_UNIT, durUnit)
        prefs.set(Constants.PREF_NICKNAME, nickname)
        prefs.set(Constants.PREF_ALLOW_ADOPTION, allowAdoption)

        val duration: Int
        try {
            // Try to parse the duration.
            val scalar = this.state.duration.toInt()
            prefs.set(Constants.PREF_DURATION, scalar)

            // The backend takes duration in seconds, hence it must be converted.
            duration = TimeUtils.timeUnitsToSeconds(scalar, durUnit)
        } catch (ex: NumberFormatException) {
            Log.e("Illegal duration value", ex) //NON-NLS
            this.dialogSvc.showDialog(R.string.err_client, R.string.err_invalid_duration, this.uiResetTask)
            return
        } catch (ex: ArithmeticException) {
            Log.e("Illegal duration value", ex) //NON-NLS
            this.dialogSvc.showDialog(R.string.err_client, R.string.err_invalid_duration, this.uiResetTask)
            return
        }

        if (mode.isGroupType && nickname.isEmpty()) {
            Log.e("No nickname set!") //NON-NLS
            this.dialogSvc.showDialog(R.string.err_client, R.string.err_no_nickname, this.uiResetTask)
            return
        }

        if (server == "/") {
            // If the user hasn't set up a server yet, open the settings menu and prompt them to
            // configure the backend.
            this.uiResetTask.run()
            Toast.makeText(this, R.string.err_server_not_configured, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val initParams = SessionInitiationPacket.InitParameters(server, username, password, duration, interval, minDistance, customID, e2ePass)
        ProxyHostnameResolverImpl(this, this.manager, this.uiResetTask, prefs, SessionInitiationResponseHandlerImpl(), initParams, mode, allowAdoption, nickname, groupPin).resolve()
    }

    /**
     * Stops sharing. If the setting to prompt for confirmation is enabled, a dialog box is shown to
     * confirm that the share should be stopped.
     *
     * @param prefs A preference manager.
     */
    private fun stopSharing(prefs: PreferenceManager) {
        if (prefs.get(Constants.PREF_CONFIRM_STOP)) {
            this.dialogSvc.showDialog(R.string.dialog_confirm_stop_title, R.string.dialog_confirm_stop_body, Buttons.Three.YES_NO_REMEMBER, StopSharingConfirmationPrompt(prefs, this.manager))
        } else {
            this.manager.stopSharing()
        }
    }

    /**
     * Stops sharing a single link. If it is the last active link, the whole session is stopped
     * instead.
     */
    private fun stopSharing(share: Share) {
        Log.i("User requested to stop sharing %s", share) //NON-NLS
        val prefs = PreferenceManager(this)
        if (this.state.links.size == 1) {
            Log.i("Stopping session because there is only one share left") //NON-NLS
            stopSharing(prefs)
        } else if (prefs.get(Constants.PREF_CONFIRM_STOP)) {
            this.dialogSvc.showDialog(R.string.dialog_confirm_stop_title, R.string.dialog_confirm_stop_share, Buttons.Three.YES_NO_REMEMBER, StopSharingConfirmationPrompt(prefs, this.manager, share))
        } else {
            this.manager.stopSharing(share)
        }
    }

    /**
     * Opens the system share sheet for the given share's view link.
     */
    private fun shareLink(share: Share) {
        Log.i("User requested to share %s", share) //NON-NLS
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = Constants.INTENT_TYPE_COPY_LINK
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
        shareIntent.putExtra(Intent.EXTRA_TEXT, share.viewURL)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)))
    }

    /**
     * Opens a dialog that lets the user create an additional single-user link for the session.
     */
    private fun addLink(session: Session) {
        this.dialogHost.show(CreateLinkDialog(this.state.allowAdoption, ::explainAdoption) { allowAdoption ->
            Log.v("User initiated link creation, sending packet...") //NON-NLS

            // Show a progress dialog while creating the link. This could end up taking a while
            // (e.g. if the host is unreachable, it will eventually time out).
            val progress = this.dialogSvc.showProgress(R.string.progress_new_link_title, R.string.progress_new_link_body)
            object : NewLinkPacket(this, session, allowAdoption) {
                override fun onShareCreated(share: Share) {
                    Log.i("Share created, showing dialog and sending upstream") //NON-NLS
                    progress.dismiss()
                    this@MainActivity.manager.shareLocation(share, SessionInitiationReason.SHARE_ADDED)

                    // Tell the user that the link was added successfully.
                    this@MainActivity.dialogSvc.showDialog(R.string.link_added_title, R.string.link_added_body)
                }

                override fun onFailure(ex: Exception) {
                    Log.w("Share creation failed", ex) //NON-NLS
                    progress.dismiss()
                    object : DialogPacketFailureHandler(this@MainActivity.dialogSvc) {
                        override fun onBeforeShowFailureDialog() {
                        }
                    }.onFailure(ex)
                }
            }.send()
        })
    }

    /**
     * Opens a dialog that lets the host of a group share adopt another user's share into it.
     */
    private fun adoptShare(share: Share) {
        Log.v("Creating dialog for adoption") //NON-NLS
        this.dialogHost.show(AdoptDialog(share.session.serverURL) { adoptID, nick ->
            Log.v("User initiated adoption with nick=%s, id=%s", nick, adoptID) //NON-NLS

            // Show a progress dialog, since we are interacting with an external server, which can
            // take some time.
            val progress = this.dialogSvc.showProgress(R.string.progress_adopt_title, getString(R.string.progress_adopt_body, nick))
            object : AdoptSharePacket(this, share, adoptID, nick) {
                override fun onSuccessfulAdoption(nickname: String) {
                    Log.i("Adoption was successful for nick=%s", nickname) //NON-NLS
                    progress.dismiss()
                    this@MainActivity.dialogSvc.showDialog(R.string.adopted_title, getString(R.string.adopted_body, nickname))
                }

                override fun onFailure(ex: Exception) {
                    Log.w("Adoption failed", ex) //NON-NLS
                    progress.dismiss()
                    this@MainActivity.dialogSvc.showDialog(R.string.err_server, ex.message)
                }
            }.send()
        })
    }

    /**
     * Opens an explanation of share adoption.
     */
    private fun explainAdoption() {
        Log.i("Explaining share adoption upon user request") //NON-NLS
        this.dialogSvc.showDialog(R.string.explain_adopt_title, R.string.explain_adopt_body)
    }

    /**
     * Resets the user interface to its default settings, as if the app was just opened. Used to
     * reset the UI after errors and after sharing has expired.
     */
    private fun resetUI() {
        Log.i("Reset task called; resetting UI...") //NON-NLS
        this.state.sessionExpiry = null
        this.state.busy = false
        this.state.inputsLocked = false
        this.state.linkSession = null
        this.state.groupShare = null
        this.state.links.clear()
        Log.i("App state was reset") //NON-NLS
    }

    /**
     * Handlers for user interaction on the main screen.
     */
    private inner class Actions : MainActions {
        override fun onShareButtonClick() = startSharing()

        override fun onAddLinkClick() {
            this@MainActivity.state.linkSession?.let(::addLink)
        }

        override fun onAdoptClick() {
            this@MainActivity.state.groupShare?.let(::adoptShare)
        }

        override fun onExplainAdoptionClick() = explainAdoption()

        override fun onProjectSiteClick() {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.label_source_link).toUri()))
        }

        override fun onSettingsClick() {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        override fun onShareLinkClick(share: Share) = shareLink(share)

        override fun onStopLinkClick(share: Share) = stopSharing(share)
    }

    /**
     * An implementation of [SessionManager]'s session initiation response handler that shows a
     * progress dialog and shows alerts if applicable.
     */
    private inner class SessionInitiationResponseHandlerImpl :
        DialogPacketFailureHandler(DialogService(this@MainActivity), this@MainActivity.uiResetTask),
        SessionInitiationResponseHandler {

        private var progress: HostedDialog? = null

        override fun onInitiating() {
            // Show a progress dialog while doing initial handshake. This could end up taking a
            // while (e.g. if the host is unreachable, it will eventually time out), and having a
            // progress indicator makes for better UX since it visually shows that something is
            // actually happening in the background.
            this.progress = this@MainActivity.dialogSvc.showProgress(R.string.progress_connect_title, R.string.progress_connect_body)
        }

        override fun onSuccess() {
            this.progress?.dismiss()
        }

        override fun onBeforeShowFailureDialog() {
            this.progress?.dismiss()
        }

        override fun onShareModeForciblyDowngraded(downgradeTo: ShareMode, backendVersion: Version) {
            this@MainActivity.state.mode = downgradeTo
            this@MainActivity.dialogSvc.showDialog(R.string.err_outdated, String.format(getString(R.string.err_ver_group), Constants.VERSION_COMPAT_GROUP_SHARE, backendVersion))
        }

        override fun onE2EForciblyDisabled(backendVersion: Version) {
            this@MainActivity.dialogSvc.showDialog(R.string.err_outdated, String.format(getString(R.string.err_ver_e2e), Constants.VERSION_COMPAT_E2E_ENCRYPTION, backendVersion))
        }
    }

    /**
     * A dialog builder that hooks into [PromptCallback] for accepting or denying session
     * resumption.
     */
    private inner class ResumeDialogBuilder(private val response: PromptCallback) : CustomDialogBuilder {
        override fun onPositive() {
            this@MainActivity.state.inputsLocked = true
            this.response.accept()
        }

        override fun onNegative() {
            this.response.deny()
        }
    }

    /**
     * Function that runs if the user accepts the location request rationale via the OK button.
     */
    private inner class PermissionRequestExecutionTask : Runnable {
        override fun run() {
            Log.i("User accepted location permission rationale; showing permission request from system") //NON-NLS
            this@MainActivity.uiResetTask.run()
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_FINE_LOCATION)
        }
    }

    /**
     * Updates the location status on the UI whenever the GNSS status changes.
     */
    private inner class StatusListener : GNSSStatusUpdateListener {
        private fun update(status: LocationStatus) {
            this@MainActivity.state.status = status
            this@MainActivity.state.serverDisconnected = false
        }

        override fun onShutdown() = update(LocationStatus.INACTIVE)

        override fun onStarted() = update(LocationStatus.WAITING)

        override fun onGNSSConnectionLost() {
            // Indicate to the user that the GNSS connection was lost, and that we are now searching
            // for a location again.
            Log.i("GNSS location provider has stopped working; bound to coarse location provider") //NON-NLS
            update(LocationStatus.SEARCHING)
        }

        override fun onCoarseLocationReceived() {
            Log.i("Initial coarse location was received, awaiting high accuracy fix") //NON-NLS
            update(LocationStatus.COARSE)
        }

        override fun onAccurateLocationReceived() {
            Log.i("Initial high accuracy location was received, using GNSS location data for all future location updates") //NON-NLS
            update(LocationStatus.ACTIVE)
        }

        override fun onServerConnectionLost() {
            this@MainActivity.state.serverDisconnected = true
        }

        override fun onServerConnectionRestored() {
            this@MainActivity.state.serverDisconnected = false
        }
    }

    /**
     * Implementation of [SessionListener] that updates the UI and shows dialogs depending on the
     * results of session creation.
     */
    private inner class SessionListenerImpl : SessionListener {
        override fun onSessionCreated(session: Session, share: Share, reason: SessionInitiationReason) {
            val state = this@MainActivity.state

            // We now have a link to share, so we enable the additional link creation button if the
            // backend supports it.
            if (session.backendVersion.isAtLeast(Constants.VERSION_COMPAT_VIEW_ID)) {
                state.linkSession = session
            } else {
                Log.w("Backend is outdated and does not support adding additional links. Button will remain disabled.") //NON-NLS
            }

            // Now that sharing is active, the start button turns into a stop button with a
            // countdown.
            state.sessionExpiry = session.expiryTime
            state.inputsLocked = true
            state.busy = false

            // Service relaunches should be handled silently.
            if (reason == SessionInitiationReason.USER_STARTED) {
                this@MainActivity.dialogSvc.showDialog(R.string.ok_title, R.string.ok_message, Buttons.Two.OK_SHARE, object : CustomDialogBuilder {
                    override fun onPositive() {
                        // OK button
                    }

                    override fun onNegative() {
                        // Share button
                        shareLink(share)
                    }
                })
            }
        }

        override fun onSessionCreationFailedDueToPermissions() {
            this@MainActivity.dialogSvc.showDialog(R.string.err_client, R.string.err_missing_perms, this@MainActivity.uiResetTask)
        }
    }

    /**
     * Implementation of [ShareListener] that adds and removes shares from the UI when shares are
     * joined into or left.
     */
    private inner class ShareListenerImpl : ShareListener {
        override fun onShareJoined(share: Share) {
            runOnUiThread {
                this@MainActivity.state.links.add(share)
                if (share.shareMode == ShareMode.CREATE_GROUP) {
                    // Show the group PIN on the UI if a new group share was created.
                    Log.d("Showing group layout") //NON-NLS
                    this@MainActivity.state.groupShare = share
                }
            }
        }

        override fun onShareParted(share: Share) {
            runOnUiThread { this@MainActivity.state.links.remove(share) }
        }
    }

    private companion object {
        const val REQUEST_FINE_LOCATION = 123
    }
}
