package info.varden.hauk.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.http.ConnectionParameters
import info.varden.hauk.http.SessionInitiationPacket
import info.varden.hauk.http.proxy.NameResolverTask
import info.varden.hauk.manager.SessionInitiationResponseHandler
import info.varden.hauk.manager.SessionListener
import info.varden.hauk.manager.SessionManager
import info.varden.hauk.manager.SessionInitiationReason
import info.varden.hauk.manager.StopSharingCallback
import info.varden.hauk.struct.AdoptabilityPreference
import info.varden.hauk.struct.Session
import info.varden.hauk.struct.Share
import info.varden.hauk.struct.ShareMode
import info.varden.hauk.struct.Version
import info.varden.hauk.system.LocationPermissionsNotGrantedException
import info.varden.hauk.system.LocationServicesDisabledException
import info.varden.hauk.system.preferences.PreferenceManager
import info.varden.hauk.utils.Log
import info.varden.hauk.utils.TimeUtils
import java.net.Proxy

/**
 * Starts a single-user share from Android Auto with the settings saved in the phone app. Once the
 * share runs, it is displayed and stopped through [info.varden.hauk.service.SharingState].
 */
internal class CarShareStarter(ctx: Context, private val callback: Callback) {
    interface Callback {
        /**
         * Called if the share has been created on the backend, with an optional warning for the
         * user.
         */
        fun onStarted(warning: String?)

        /**
         * Called if the share could not be started.
         */
        fun onFailure(message: String)
    }

    private val ctx = ctx.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        val prefs = PreferenceManager(this.ctx)

        var server = prefs.get(Constants.PREF_SERVER_ENCRYPTED).trim()
        if (server.isEmpty()) return fail(R.string.car_err_server_not_configured)
        server = if (server.endsWith("/")) server else "$server/"

        if (this.ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return fail(R.string.car_err_missing_perms)
        }

        val duration = try {
            TimeUtils.timeUnitsToSeconds(prefs.get(Constants.PREF_DURATION), prefs.get(Constants.PREF_DURATION_UNIT))
        } catch (ex: ArithmeticException) {
            Log.e("Illegal duration value", ex) //NON-NLS
            return fail(R.string.err_invalid_duration)
        }

        val e2ePass = if (prefs.get(Constants.PREF_ENABLE_E2E)) prefs.get(Constants.PREF_E2E_PASSWORD) else ""
        val initParams = SessionInitiationPacket.InitParameters(
            server,
            prefs.get(Constants.PREF_USERNAME_ENCRYPTED).trim(),
            prefs.get(Constants.PREF_PASSWORD_ENCRYPTED),
            duration,
            prefs.get(Constants.PREF_INTERVAL),
            prefs.get(Constants.PREF_UPDATE_DISTANCE),
            prefs.get(Constants.PREF_CUSTOM_ID).trim(),
            e2ePass,
        )
        val adoptability = if (prefs.get(Constants.PREF_ALLOW_ADOPTION)) AdoptabilityPreference.ALLOW_ADOPTION else AdoptabilityPreference.DISALLOW_ADOPTION

        Log.i("Starting share from Android Auto") //NON-NLS
        ProxyResolver(prefs, initParams, adoptability).resolve()
    }

    private fun fail(message: Int) = fail(this.ctx.getString(message))

    private fun fail(message: String) {
        this.mainHandler.post { this.callback.onFailure(message) }
    }

    /**
     * Resolves the proxy configured in settings, if any, and then starts the share.
     */
    private inner class ProxyResolver(
        private val prefs: PreferenceManager,
        private val initParams: SessionInitiationPacket.InitParameters,
        private val adoptability: AdoptabilityPreference,
    ) : NameResolverTask(prefs) {
        override fun onResolutionStarted(hostname: String) {
        }

        override fun onHostUnresolved(hostname: String) {
            fail(this@CarShareStarter.ctx.getString(R.string.err_proxy_host_resolution, hostname))
        }

        override fun onFailure(ex: Exception) {
            fail(this@CarShareStarter.ctx.getString(R.string.err_proxy_failure, ex.localizedMessage))
        }

        override fun onSuccess(proxy: Proxy?) {
            val timeout = this.prefs.get(Constants.PREF_CONNECTION_TIMEOUT) * TimeUtils.MILLIS_PER_SECOND.toInt()
            val tlsPolicy = this.prefs.get(Constants.PREF_CERTIFICATE_VALIDATION)
            this.initParams.setConnectionParameters(ConnectionParameters(proxy?.type(), proxy?.address(), timeout, tlsPolicy))

            // A new manager is used for every share, like for shares started by broadcasts.
            val manager = CarSessionManager(this@CarShareStarter.ctx)
            manager.attachSessionListener(SessionListenerImpl())
            try {
                manager.shareLocation(this.initParams, ResponseHandler(), this.adoptability)
            } catch (ex: LocationServicesDisabledException) {
                fail(R.string.err_location_disabled)
            } catch (ex: LocationPermissionsNotGrantedException) {
                fail(R.string.car_err_missing_perms)
            }
        }
    }

    private inner class ResponseHandler : SessionInitiationResponseHandler {
        private var warning: String? = null

        override fun onInitiating() {
        }

        override fun onSuccess() {
            val warning = this.warning
            this@CarShareStarter.mainHandler.post { this@CarShareStarter.callback.onStarted(warning) }
        }

        override fun onShareModeForciblyDowngraded(downgradeTo: ShareMode, backendVersion: Version) {
            // Shares started from the car are single-user shares, which every backend supports.
        }

        override fun onE2EForciblyDisabled(backendVersion: Version) {
            this.warning = this@CarShareStarter.ctx.getString(R.string.err_ver_e2e, Constants.VERSION_COMPAT_E2E_ENCRYPTION, backendVersion)
        }

        override fun onFailure(ex: Exception) {
            fail(ex.message ?: this@CarShareStarter.ctx.getString(R.string.err_connect))
        }
    }

    private inner class SessionListenerImpl : SessionListener {
        override fun onSessionCreated(session: Session, share: Share, reason: SessionInitiationReason) {
        }

        override fun onSessionCreationFailedDueToPermissions() {
            fail(R.string.car_err_missing_perms)
        }
    }

    /**
     * Session manager for shares started from Android Auto. Status changes and stopping are handled
     * through [info.varden.hauk.service.SharingState], so the callbacks do nothing.
     */
    private class CarSessionManager(ctx: Context) : SessionManager(ctx, NoOpStopCallback) {
        override fun requestLocationPermission() {
            // Permission can only be granted on the phone; the caller reports the missing
            // permission to the user.
        }
    }

    private object NoOpStopCallback : StopSharingCallback {
        override fun onSuccess() {
        }

        override fun onShareNull() {
        }

        override fun onFailure(ex: Exception) {
        }
    }
}
