package info.varden.hauk.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import info.varden.hauk.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Entry point for Android Auto. Shows the status of the location share on the car screen and lets
 * the driver start and stop it.
 */
class HaukCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // The service can start location sharing, so only trusted hosts such as Android Auto may
        // bind to it. Debug builds also accept other hosts for testing.
        return if (this.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(): Session = object : Session() {
        init {
            this.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    activeSessions.incrementAndGet()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    activeSessions.decrementAndGet()
                }
            })
        }

        override fun onCreateScreen(intent: Intent): Screen = StatusScreen(this.carContext)
    }

    companion object {
        private val activeSessions = AtomicInteger()

        /**
         * Returns whether Hauk is currently shown on a car screen.
         */
        @JvmStatic
        fun isCarSessionActive(): Boolean = activeSessions.get() > 0
    }
}
