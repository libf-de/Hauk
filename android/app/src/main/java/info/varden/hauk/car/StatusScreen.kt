package info.varden.hauk.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.service.SharingState
import info.varden.hauk.system.preferences.PreferenceManager
import info.varden.hauk.ui.formatDuration
import java.util.Locale

/**
 * The main car screen. Shows whether location is being shared and lets the driver start and stop
 * sharing and pick the share duration.
 */
internal class StatusScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver, SharingState.Listener {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Whether a share is being started and has not been published to [SharingState] yet.
     */
    private var starting = false

    /**
     * Ends [starting] if the share never shows up, e.g. because the location service failed.
     */
    private val startTimeout = Runnable {
        this.starting = false
        invalidate()
    }

    init {
        this.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        SharingState.addListener(this)
        invalidate()
    }

    override fun onStop(owner: LifecycleOwner) {
        SharingState.removeListener(this)
    }

    override fun onSharingStateChanged() {
        if (SharingState.current != null && this.starting) {
            this.starting = false
            this.handler.removeCallbacks(this.startTimeout)
        }
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val snapshot = SharingState.current
        when {
            snapshot != null -> {
                val session = snapshot.share.session
                pane.addRow(row(carContext.getString(snapshot.status.label), if (snapshot.serverDisconnected) carContext.getString(R.string.label_status_disconnected) else null))
                pane.addRow(row(carContext.getString(R.string.car_sharing_until, session.expiryString), null))
                pane.addRow(row(carContext.getString(R.string.car_link), snapshot.share.viewURL))
                pane.addAction(
                    primary(Action.Builder().setTitle(carContext.getString(R.string.action_stop)))
                        .setBackgroundColor(CarColor.RED)
                        .setOnClickListener(::onStopClick)
                        .build(),
                )
            }

            this.starting -> pane.setLoading(true)

            else -> {
                val prefs = PreferenceManager(carContext)
                val server = prefs.get(Constants.PREF_SERVER_ENCRYPTED).trim()
                pane.addRow(row(carContext.getString(R.string.label_status_none), null))
                pane.addRow(row(carContext.getString(R.string.car_duration), formatDuration(prefs.get(Constants.PREF_DURATION), prefs.get(Constants.PREF_DURATION_UNIT), Locale.getDefault())))
                pane.addRow(row(carContext.getString(R.string.car_server), server.ifEmpty { carContext.getString(R.string.car_server_none) }))
                pane.addAction(
                    primary(Action.Builder().setTitle(carContext.getString(R.string.btn_start)))
                        .setOnClickListener(::onStartClick)
                        .build(),
                )
                pane.addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.car_duration))
                        .setOnClickListener { screenManager.push(DurationScreen(carContext)) }
                        .build(),
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle(carContext.getString(R.string.app_name)).setStartHeaderAction(Action.APP_ICON).build())
            .build()
    }

    private fun row(title: String, text: String?): Row {
        val row = Row.Builder().setTitle(title)
        if (text != null) row.addText(text)
        return row.build()
    }

    /**
     * Highlights the main action of the screen on hosts that support it.
     */
    private fun primary(action: Action.Builder): Action.Builder {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) action.setFlags(Action.FLAG_PRIMARY)
        return action
    }

    private fun onStartClick() {
        if (this.starting || SharingState.current != null) return
        this.starting = true
        invalidate()

        CarShareStarter(carContext, object : CarShareStarter.Callback {
            override fun onStarted(warning: String?) {
                if (warning != null) CarToast.makeText(carContext, warning, CarToast.LENGTH_LONG).show()
                this@StatusScreen.handler.postDelayed(this@StatusScreen.startTimeout, START_TIMEOUT_MILLIS)
            }

            override fun onFailure(message: String) {
                this@StatusScreen.starting = false
                CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
                invalidate()
            }
        }).start()
    }

    private fun onStopClick() {
        if (PreferenceManager(carContext).get(Constants.PREF_CONFIRM_STOP)) {
            screenManager.push(ConfirmStopScreen(carContext))
        } else {
            stopSharing(carContext)
        }
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * Asks the driver to confirm stopping the share, if confirmation is enabled in settings.
 */
private class ConfirmStopScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.dialog_confirm_stop_body))
            .setHeader(Header.Builder().setTitle(carContext.getString(R.string.dialog_confirm_stop_title)).setStartHeaderAction(Action.BACK).build())
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.btn_stop_short))
                    .setBackgroundColor(CarColor.RED)
                    .setOnClickListener {
                        stopSharing(carContext)
                        screenManager.pop()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.btn_cancel))
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            )
            .build()
}

private fun stopSharing(carContext: CarContext) {
    if (SharingState.stopSharing()) {
        CarToast.makeText(carContext, R.string.ended_message, CarToast.LENGTH_SHORT).show()
    }
}
