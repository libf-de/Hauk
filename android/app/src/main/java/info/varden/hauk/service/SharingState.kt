package info.varden.hauk.service

import android.os.Handler
import android.os.Looper
import info.varden.hauk.manager.StopSharingTask
import info.varden.hauk.struct.Share
import info.varden.hauk.ui.LocationStatus
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide state of the running location share, published by [LocationPushService]. This lets
 * UIs that did not start the share themselves, such as Android Auto, display and stop it.
 */
object SharingState {
    /**
     * Listener that is called on the main thread whenever the sharing state changes.
     */
    fun interface Listener {
        fun onSharingStateChanged()
    }

    /**
     * An immutable snapshot of the running share.
     */
    class Snapshot(
        val share: Share,
        val status: LocationStatus,
        val serverDisconnected: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    /**
     * The service instance that published the current state. Updates from other instances are
     * ignored, since they are left over from a service that has been relaunched.
     */
    private var owner: Any? = null
    private var stopTask: StopSharingTask? = null

    /**
     * The running share, or null if location sharing is inactive.
     */
    @JvmStatic
    @Volatile
    var current: Snapshot? = null
        private set

    @JvmStatic
    fun addListener(listener: Listener) {
        this.listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        this.listeners.remove(listener)
    }

    /**
     * Stops the running share the same way the notification's "Stop sharing" button does.
     *
     * @return false if no share is running.
     */
    @JvmStatic
    fun stopSharing(): Boolean {
        val task = synchronized(this) { this.stopTask } ?: return false
        task.run()
        return true
    }

    /**
     * Publishes a share that has started.
     *
     * @param owner    The service that runs the share.
     * @param share    The share represented by the service.
     * @param stopTask The task that stops the share.
     * @return A handler that should receive GNSS and connection status updates for the share.
     */
    @JvmStatic
    fun publish(owner: Any, share: Share, stopTask: StopSharingTask): GNSSActiveHandler {
        synchronized(this) {
            this.owner = owner
            this.stopTask = stopTask
            this.current = Snapshot(share, LocationStatus.WAITING, false)
        }
        notifyListeners()
        return StatusUpdater(owner)
    }

    /**
     * Clears the published share when the given service stops.
     */
    @JvmStatic
    fun clear(owner: Any) {
        synchronized(this) {
            if (this.owner !== owner) return
            this.owner = null
            this.stopTask = null
            this.current = null
        }
        notifyListeners()
    }

    private fun update(owner: Any, transform: (Snapshot) -> Snapshot) {
        synchronized(this) {
            if (this.owner !== owner) return
            val snapshot = this.current ?: return
            this.current = transform(snapshot)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        this.mainHandler.post {
            for (listener in this.listeners) listener.onSharingStateChanged()
        }
    }

    /**
     * Forwards status updates of a share to the published state.
     */
    private class StatusUpdater(private val owner: Any) : GNSSActiveHandler {
        private fun setStatus(status: LocationStatus) =
            update(this.owner) { Snapshot(it.share, status, it.serverDisconnected) }

        private fun setDisconnected(disconnected: Boolean) =
            update(this.owner) { Snapshot(it.share, it.status, disconnected) }

        override fun onCoarseRebound() = setStatus(LocationStatus.SEARCHING)

        override fun onCoarseLocationReceived() = setStatus(LocationStatus.COARSE)

        override fun onAccurateLocationReceived() = setStatus(LocationStatus.ACTIVE)

        override fun onServerConnectionLost() = setDisconnected(true)

        override fun onServerConnectionRestored() = setDisconnected(false)

        override fun onShareListReceived(linkFormat: String, shareIDs: Array<String>) {
        }
    }
}
