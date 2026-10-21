package info.varden.hauk.system.preferences.ui

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.dialog.HaukDialog
import info.varden.hauk.navigation.ComapsNavigationSource
import info.varden.hauk.streams.RemoteCamStreamSource
import info.varden.hauk.system.preferences.IndexedEnum
import info.varden.hauk.system.preferences.indexresolver.NightModeStyle
import info.varden.hauk.utils.Log

/**
 * Screen that lets the user view and change the app's preferences.
 *
 * @param onNavigateUp Called when the user presses the back arrow in the app bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(store: SettingsStore, onNavigateUp: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Setting?>(null) }

    // Requests permission to read CoMaps navigation data when navigation sharing is enabled. The
    // preference is enabled right away and reverted if the permission is denied.
    val navPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Log.w("Permission to read CoMaps navigation data was denied") //NON-NLS
            store[Constants.PREF_SHARE_NAVIGATION] = false
            Toast.makeText(ctx, R.string.err_comaps_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    // Same for reading RemoteCam stream links when camera stream sharing is enabled.
    val streamsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Log.w("Permission to read RemoteCam stream links was denied") //NON-NLS
            store[Constants.PREF_SHARE_STREAMS] = false
            Toast.makeText(ctx, R.string.err_remotecam_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Ensures that one of the given installed apps grants access to its data, requesting the
     * permission of the first one if none do. Returns false if none of the apps are installed.
     */
    fun ensurePermission(installed: List<String>, permission: (String) -> String, launcher: (String) -> Unit, @StringRes notInstalled: Int): Boolean {
        if (installed.isEmpty()) {
            Toast.makeText(ctx, notInstalled, Toast.LENGTH_LONG).show()
            return false
        }
        val granted = installed.any { pkg -> ctx.checkSelfPermission(permission(pkg)) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            Log.i("Requesting permission %s", permission(installed[0])) //NON-NLS
            launcher(permission(installed[0]))
        }
        return true
    }

    fun onSwitchChanged(setting: Setting.Switch, value: Boolean) {
        if (value) {
            val available = when (setting.pref) {
                Constants.PREF_SHARE_NAVIGATION -> ensurePermission(
                    ComapsNavigationSource.getInstalledPackages(ctx),
                    ComapsNavigationSource::getPermission,
                    navPermissionLauncher::launch,
                    R.string.err_comaps_not_installed,
                )
                Constants.PREF_SHARE_STREAMS -> ensurePermission(
                    RemoteCamStreamSource.getInstalledPackages(ctx),
                    RemoteCamStreamSource::getPermission,
                    streamsPermissionLauncher::launch,
                    R.string.err_remotecam_not_installed,
                )
                else -> true
            }
            if (!available) return
        }
        store[setting.pref] = value
    }

    fun onClick(setting: Setting) {
        when (setting) {
            is Setting.Text, is Setting.Integer, is Setting.Decimal, is Setting.Choice<*> -> editing = setting
            is Setting.Switch -> onSwitchChanged(setting, !store[setting.pref])
            is Setting.Link, is Setting.Info -> Unit
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_activity_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_navigate_up))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            for (category in SETTINGS) {
                item(key = category.title) {
                    Text(
                        stringResource(category.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(category.settings, key = { _, setting -> setting.title }) { index, setting ->
                    SettingItem(
                        setting = setting,
                        store = store,
                        index = index,
                        count = category.settings.size,
                        onClick = { onClick(setting) },
                        onSwitchChanged = { onSwitchChanged(setting as Setting.Switch, it) },
                    )
                }
            }
        }
    }

    when (val setting = editing) {
        is Setting.Choice<*> -> ChoiceDialog(setting, store, onDismiss = { editing = null })
        is Setting.Text, is Setting.Integer, is Setting.Decimal -> TextEditDialog(setting, store, onDismiss = { editing = null })
        else -> Unit
    }
}

/**
 * A single row on the settings screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingItem(
    setting: Setting,
    store: SettingsStore,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onSwitchChanged: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val enabled = setting.isEnabled(store)
    val leading: @Composable () -> Unit = { Icon(setting.icon.vector(), contentDescription = null) }
    val title: @Composable () -> Unit = { Text(stringResource(setting.title)) }

    when (setting) {
        is Setting.Switch -> {
            val checked = store[setting.pref]
            SegmentedListItem(
                checked = checked,
                onCheckedChange = onSwitchChanged,
                shapes = shapes,
                enabled = enabled,
                leadingContent = leading,
                supportingContent = { Text(stringResource(if (checked) setting.summaryOn else setting.summaryOff)) },
                trailingContent = {
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        thumbContent = if (checked) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                },
                content = title,
            )
        }

        is Setting.Info -> SegmentedListItem(
            shapes = shapes,
            leadingContent = leading,
            supportingContent = { Text(setting.value) },
            content = title,
        )

        else -> {
            val summary = summaryOf(setting, store)
            val uriHandler = LocalUriHandler.current
            val uri = (setting as? Setting.Link)?.let { stringResource(it.uri) }
            SegmentedListItem(
                onClick = if (uri != null) ({ uriHandler.openUri(uri) }) else onClick,
                shapes = shapes,
                enabled = enabled,
                leadingContent = leading,
                supportingContent = summary?.let { { Text(it) } },
                content = title,
            )
        }
    }
}

/**
 * Returns the text displayed underneath the title of the given setting, if any.
 */
@Composable
private fun summaryOf(setting: Setting, store: SettingsStore): String? = when (setting) {
    is Setting.Text -> when {
        setting.sensitive -> stringResource(R.string.password_mask)
        else -> store[setting.pref].ifEmpty { stringResource(R.string.pref_not_set) }
    }
    is Setting.Integer -> store[setting.pref].toString()
    is Setting.Decimal -> store[setting.pref].toString()
    is Setting.Choice<*> -> stringArrayResource(setting.entries)[setting.getIndex(store)]
    is Setting.Link -> setting.summary?.let { stringResource(it) }
    is Setting.Switch, is Setting.Info -> null
}

/**
 * Dialog for editing text and number settings. The OK button is disabled while the entered value
 * is invalid.
 */
@Composable
private fun TextEditDialog(setting: Setting, store: SettingsStore, onDismiss: () -> Unit) {
    val initial = when (setting) {
        is Setting.Text -> store[setting.pref]
        is Setting.Integer -> store[setting.pref].toString()
        is Setting.Decimal -> store[setting.pref].toString()
        else -> throw IllegalArgumentException("Setting $setting is not editable as text") //NON-NLS
    }
    val state = rememberTextFieldState(initial)
    val text = state.text.toString()
    val valid = when (setting) {
        is Setting.Integer -> text.toIntOrNull()?.let { it in setting.range } ?: false
        is Setting.Decimal -> text.toFloatOrNull()?.let { it in setting.range } ?: false
        else -> true
    }

    fun save() {
        if (!valid) return
        when (setting) {
            is Setting.Text -> store[setting.pref] = text
            is Setting.Integer -> store[setting.pref] = text.toInt()
            is Setting.Decimal -> store[setting.pref] = text.toFloat()
        }
        onDismiss()
    }

    val keyboardOptions = KeyboardOptions(
        keyboardType = when (setting) {
            is Setting.Text -> setting.keyboardType
            is Setting.Integer -> KeyboardType.Number
            else -> KeyboardType.Decimal
        },
        imeAction = ImeAction.Done,
    )
    val placeholder: (@Composable () -> Unit)? = (setting as? Setting.Text)?.hint?.let { hint -> { Text(stringResource(hint)) } }
    val focusRequester = remember { FocusRequester() }
    val fieldModifier = Modifier.fillMaxWidth().focusRequester(focusRequester)

    HaukDialog(
        title = stringResource(setting.title),
        icon = setting.icon.vector(),
        dismissible = true,
        onDismissRequest = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            TextButton(onClick = ::save, enabled = valid) { Text(stringResource(R.string.btn_ok)) }
        },
    ) {
        if (setting is Setting.Text && setting.sensitive) {
            OutlinedSecureTextField(
                state = state,
                isError = !valid,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = { save() },
                modifier = fieldModifier,
            )
        } else {
            OutlinedTextField(
                state = state,
                placeholder = placeholder,
                isError = !valid,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = { save() },
                modifier = fieldModifier,
            )
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Dialog for choosing the value of a list setting.
 */
@Composable
private fun ChoiceDialog(setting: Setting.Choice<*>, store: SettingsStore, onDismiss: () -> Unit) {
    val entries = stringArrayResource(setting.entries)
    val selected = setting.getIndex(store)

    HaukDialog(
        title = stringResource(setting.title),
        icon = setting.icon.vector(),
        dismissible = true,
        onDismissRequest = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    ) {
        Column {
            entries.forEachIndexed { index, label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .selectable(selected = index == selected, role = Role.RadioButton) {
                            setting.setIndex(store, index)
                            if (setting.pref == Constants.PREF_NIGHT_MODE) applyNightMode(index)
                            onDismiss()
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = index == selected, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * Applies the night mode style with the given index to the app.
 */
@SuppressLint("WrongConstant")
private fun applyNightMode(index: Int) {
    val mode = IndexedEnum.fromIndex(NightModeStyle::class.java, index).resolve()
    Log.i("Setting night mode %s", mode) //NON-NLS
    AppCompatDelegate.setDefaultNightMode(mode)
}
