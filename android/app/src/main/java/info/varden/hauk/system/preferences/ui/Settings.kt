package info.varden.hauk.system.preferences.ui

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import info.varden.hauk.BuildConfig
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.http.security.CertificateValidationPolicy
import info.varden.hauk.system.preferences.IndexedEnum
import info.varden.hauk.system.preferences.Preference
import info.varden.hauk.system.preferences.PreferenceManager
import info.varden.hauk.system.preferences.indexresolver.NightModeStyle
import info.varden.hauk.system.preferences.indexresolver.ProxyTypeResolver
import info.varden.hauk.ui.theme.HaukIcons

/**
 * Observable access to the preferences shown in [SettingsScreen]. Values are read from and written
 * to [PreferenceManager], so they are validated and encrypted as required by [Preference].
 */
class SettingsStore(ctx: Context) {
    private val prefs = PreferenceManager(ctx)

    /**
     * Values that have been changed while the settings screen is open, keyed by preference key.
     */
    private val changes = mutableStateMapOf<String, Any?>()

    operator fun <T> get(pref: Preference<T>): T {
        @Suppress("UNCHECKED_CAST")
        return if (this.changes.containsKey(pref.key)) this.changes[pref.key] as T else this.prefs.get(pref)
    }

    operator fun <T> set(pref: Preference<T>, value: T) {
        this.prefs.set(pref, value)
        this.changes[pref.key] = value
    }
}

/**
 * The icon of a setting. Either a Material icon or one of the app's vector drawables.
 */
sealed interface SettingIcon {
    @Composable
    fun vector(): ImageVector

    class Vector(private val icon: ImageVector) : SettingIcon {
        @Composable
        override fun vector() = this.icon
    }

    class Drawable(@param:DrawableRes private val res: Int) : SettingIcon {
        @Composable
        override fun vector() = ImageVector.vectorResource(this.res)
    }
}

/**
 * A single entry on the settings screen.
 */
sealed class Setting(@param:StringRes val title: Int, val icon: SettingIcon) {

    /**
     * Whether the setting can currently be changed. Used for settings that depend on others.
     */
    open fun isEnabled(store: SettingsStore) = true

    /**
     * A setting that is edited in a text input dialog.
     *
     * @param sensitive Whether the value is secret, in which case it is masked when shown.
     */
    class Text(
        val pref: Preference<String>,
        title: Int,
        icon: SettingIcon,
        val keyboardType: KeyboardType = KeyboardType.Text,
        @param:StringRes val hint: Int? = null,
        val sensitive: Boolean = false,
        private val dependency: ((SettingsStore) -> Boolean)? = null,
    ) : Setting(title, icon) {
        override fun isEnabled(store: SettingsStore) = this.dependency?.invoke(store) ?: true
    }

    /**
     * An integer setting that is edited in a text input dialog. The value must be in [range].
     */
    class Integer(
        val pref: Preference<Int>,
        title: Int,
        icon: SettingIcon,
        val range: IntRange,
        private val dependency: ((SettingsStore) -> Boolean)? = null,
    ) : Setting(title, icon) {
        override fun isEnabled(store: SettingsStore) = this.dependency?.invoke(store) ?: true
    }

    /**
     * A decimal setting that is edited in a text input dialog. The value must be in [range].
     */
    class Decimal(
        val pref: Preference<Float>,
        title: Int,
        icon: SettingIcon,
        val range: ClosedFloatingPointRange<Float>,
    ) : Setting(title, icon)

    /**
     * A setting that is toggled on or off.
     */
    class Switch(
        val pref: Preference<Boolean>,
        title: Int,
        icon: SettingIcon,
        @param:StringRes val summaryOn: Int,
        @param:StringRes val summaryOff: Int,
    ) : Setting(title, icon)

    /**
     * A setting whose value is chosen from a list. The entries in [entries] must be ordered by the
     * index of the corresponding [IndexedEnum] member.
     */
    class Choice<T : IndexedEnum<T>>(
        val pref: Preference<T>,
        private val type: Class<T>,
        title: Int,
        icon: SettingIcon,
        @param:ArrayRes val entries: Int,
    ) : Setting(title, icon) {
        fun getIndex(store: SettingsStore) = store[this.pref].index

        fun setIndex(store: SettingsStore, index: Int) {
            store[this.pref] = IndexedEnum.fromIndex(this.type, index)
        }
    }

    /**
     * A setting that displays a fixed value.
     */
    class Info(title: Int, icon: SettingIcon, val value: String) : Setting(title, icon)

    /**
     * An entry that opens a link in the browser.
     */
    class Link(
        title: Int,
        icon: SettingIcon,
        @param:StringRes val uri: Int,
        @param:StringRes val summary: Int? = null,
    ) : Setting(title, icon)
}

/**
 * A titled group of settings.
 */
class SettingCategory(@param:StringRes val title: Int, val settings: List<Setting>)

private fun isProxyConfigurable(store: SettingsStore): Boolean {
    val type = store[Constants.PREF_PROXY_TYPE]
    return type != ProxyTypeResolver.SYSTEM_DEFAULT && type != ProxyTypeResolver.DIRECT
}

/**
 * All settings displayed on the settings screen.
 */
val SETTINGS = listOf(
    SettingCategory(
        R.string.prefs_header_connection,
        listOf(
            Setting.Text(Constants.PREF_SERVER_ENCRYPTED, R.string.pref_cryptServer_title, SettingIcon.Drawable(R.drawable.ic_server), KeyboardType.Uri, R.string.pref_cryptServer_hint),
            Setting.Text(Constants.PREF_USERNAME_ENCRYPTED, R.string.pref_cryptUsername_title, SettingIcon.Drawable(R.drawable.ic_person), hint = R.string.pref_cryptUsername_hint),
            Setting.Text(Constants.PREF_PASSWORD_ENCRYPTED, R.string.pref_cryptPassword_title, SettingIcon.Drawable(R.drawable.ic_lock_open), KeyboardType.Password, sensitive = true),
            Setting.Text(Constants.PREF_CUSTOM_ID, R.string.pref_requestLink_title, SettingIcon.Vector(HaukIcons.Link), hint = R.string.pref_requestLink_hint),
            Setting.Integer(Constants.PREF_INTERVAL, R.string.pref_interval_title, SettingIcon.Drawable(R.drawable.ic_timer), 1..Int.MAX_VALUE),
            Setting.Decimal(Constants.PREF_UPDATE_DISTANCE, R.string.pref_minUpdateDistance_title, SettingIcon.Drawable(R.drawable.ic_directions_walk), 0.0F..Float.MAX_VALUE),
            Setting.Switch(Constants.PREF_ENABLE_E2E, R.string.pref_enableE2E_title, SettingIcon.Vector(HaukIcons.EnhancedEncryption), R.string.pref_enableE2E_on, R.string.pref_enableE2E_off),
            Setting.Text(Constants.PREF_E2E_PASSWORD, R.string.pref_e2ePassword_title, SettingIcon.Vector(HaukIcons.Key), KeyboardType.Password, sensitive = true) { it[Constants.PREF_ENABLE_E2E] },
            Setting.Choice(Constants.PREF_PROXY_TYPE, ProxyTypeResolver::class.java, R.string.pref_proxyType_title, SettingIcon.Drawable(R.drawable.ic_proxy), R.array.proxy_types),
            Setting.Text(Constants.PREF_PROXY_HOST, R.string.pref_proxyHost_title, SettingIcon.Vector(HaukIcons.Dns), KeyboardType.Uri, dependency = ::isProxyConfigurable),
            Setting.Integer(Constants.PREF_PROXY_PORT, R.string.pref_proxyPort_title, SettingIcon.Vector(HaukIcons.SettingsEthernet), Constants.PORT_MIN..Constants.PORT_MAX, ::isProxyConfigurable),
            Setting.Integer(Constants.PREF_CONNECTION_TIMEOUT, R.string.pref_connectTimeout_title, SettingIcon.Drawable(R.drawable.ic_no_connection), 1..Int.MAX_VALUE),
            Setting.Integer(Constants.PREF_NO_GNSS_FALLBACK, R.string.pref_noGnssFallback_title, SettingIcon.Drawable(R.drawable.ic_location_disabled), 0..Int.MAX_VALUE),
            Setting.Choice(Constants.PREF_CERTIFICATE_VALIDATION, CertificateValidationPolicy::class.java, R.string.pref_tlsCertValidation_title, SettingIcon.Drawable(R.drawable.ic_security), R.array.tls_validation_types),
        ),
    ),
    SettingCategory(
        R.string.prefs_header_navigation,
        listOf(
            Setting.Switch(Constants.PREF_SHARE_NAVIGATION, R.string.pref_shareNavigation_title, SettingIcon.Drawable(R.drawable.ic_navigation), R.string.pref_shareNavigation_on, R.string.pref_shareNavigation_off),
        ),
    ),
    SettingCategory(
        R.string.prefs_header_cameras,
        listOf(
            Setting.Switch(Constants.PREF_SHARE_STREAMS, R.string.pref_shareStreams_title, SettingIcon.Vector(HaukIcons.Videocam), R.string.pref_shareStreams_on, R.string.pref_shareStreams_off),
        ),
    ),
    SettingCategory(
        R.string.prefs_header_appearance,
        listOf(
            Setting.Choice(Constants.PREF_NIGHT_MODE, NightModeStyle::class.java, R.string.prefs_nightMode_title, SettingIcon.Drawable(R.drawable.ic_brightness_3), R.array.night_mode_modes),
            Setting.Switch(Constants.PREF_CONFIRM_STOP, R.string.pref_confirmStop_title, SettingIcon.Vector(HaukIcons.TaskAlt), R.string.pref_confirmStop_on, R.string.pref_confirmStop_off),
            Setting.Switch(Constants.PREF_HIDE_LOGO, R.string.prefs_hideLogo_title, SettingIcon.Drawable(R.drawable.ic_image), R.string.pref_hideLogo_on, R.string.pref_hideLogo_off),
        ),
    ),
    SettingCategory(
        R.string.prefs_header_about,
        listOf(
            Setting.Info(R.string.about_version_title, SettingIcon.Vector(Icons.Rounded.Info), BuildConfig.VERSION_NAME),
            Setting.Link(R.string.about_source_code_title, SettingIcon.Drawable(R.drawable.ic_code), R.string.label_source_link, R.string.label_source_link),
            Setting.Link(R.string.about_report_issue_title, SettingIcon.Drawable(R.drawable.ic_bug_report), R.string.link_issue_tracker),
        ),
    ),
)
