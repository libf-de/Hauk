package info.varden.hauk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.lerp
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.struct.Share
import info.varden.hauk.struct.ShareMode
import info.varden.hauk.ui.theme.HaukIcons
import info.varden.hauk.utils.TimeUtils
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Handlers for user interaction on the main screen.
 */
interface MainActions {
    fun onShareButtonClick()
    fun onAddLinkClick()
    fun onAdoptClick()
    fun onExplainAdoptionClick()
    fun onProjectSiteClick()
    fun onSettingsClick()
    fun onShareLinkClick(share: Share)
    fun onStopLinkClick(share: Share)
}

/**
 * The main screen of Hauk, from which location shares are started and managed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    // With the logo shown, the app bar starts out tall and shrinks the logo into the collapsed bar
    // as the content scrolls. Without it, a regular app bar with the app name is used.
    val logoScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehavior = if (state.showLogo) logoScrollBehavior else pinnedScrollBehavior
    val settingsButton: @Composable RowScope.() -> Unit = {
        IconButton(onClick = actions::onSettingsClick) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_settings))
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.showLogo) {
                LogoTopAppBar(scrollBehavior, actions::onProjectSiteClick, settingsButton)
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = settingsButton,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(actions::onProjectSiteClick)
                SessionCard(state, actions)
                StatusCard(state)
                ShareButton(state, actions::onShareButtonClick)
                AnimatedVisibility(visible = state.groupShare != null) {
                    state.groupShare?.let { GroupPinCard(it, actions::onAdoptClick) }
                }
                FilledTonalButton(
                    onClick = actions::onAddLinkClick,
                    shapes = ButtonDefaults.shapes(),
                    enabled = state.linkSession != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HaukIcons.AddLink, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.btn_link))
                }
                LinkList(state.links, actions)
            }
        }
    }
}

/**
 * A top app bar that displays a large Hauk logo when expanded. As the content scrolls up, the bar
 * collapses and the logo shrinks into its start, where an app bar title would normally be.
 *
 * @param onLogoClick Called when the logo is tapped.
 * @param actions     The action buttons displayed at the end of the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogoTopAppBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onLogoClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val state = scrollBehavior.state
    val collapseRange = with(LocalDensity.current) { (LOGO_BAR_EXPANDED_HEIGHT - LOGO_BAR_COLLAPSED_HEIGHT).toPx() }
    SideEffect {
        if (state.heightOffsetLimit != -collapseRange) state.heightOffsetLimit = -collapseRange
    }

    // Change the container color as the bar collapses, like the standard two-row app bars do.
    val colors = TopAppBarDefaults.topAppBarColors()
    val containerColor = lerp(colors.containerColor, colors.scrolledContainerColor, FastOutLinearInEasing.transform(state.collapsedFraction))

    Surface(color = containerColor) {
        Layout(
            content = {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = stringResource(R.string.img_alt_logo),
                    modifier = Modifier
                        .layoutId(LAYOUT_ID_LOGO)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onLogoClick),
                )
                CompositionLocalProvider(LocalContentColor provides colors.actionIconContentColor) {
                    Row(
                        modifier = Modifier.layoutId(LAYOUT_ID_ACTIONS),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            },
            modifier = Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets).clipToBounds(),
        ) { measurables, constraints ->
            // State is read during layout so that scrolling only causes a relayout.
            val fraction = state.collapsedFraction
            val collapsedHeight = LOGO_BAR_COLLAPSED_HEIGHT.roundToPx()
            val height = (LOGO_BAR_EXPANDED_HEIGHT.toPx() + state.heightOffset).roundToInt()

            val logoHeight = lerp(LOGO_EXPANDED_HEIGHT.toPx(), LOGO_COLLAPSED_HEIGHT.toPx(), fraction).roundToInt()
            val logo = measurables.first { it.layoutId == LAYOUT_ID_LOGO }
                .measure(Constraints(maxWidth = constraints.maxWidth, minHeight = logoHeight, maxHeight = logoHeight))
            val actionRow = measurables.first { it.layoutId == LAYOUT_ID_ACTIONS }
                .measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = collapsedHeight))

            layout(constraints.maxWidth, height) {
                // Expanded: centered below the action row. Collapsed: vertically centered at the start.
                val expandedX = (constraints.maxWidth - logo.width) / 2
                val expandedY = collapsedHeight - LOGO_EXPANDED_OVERLAP.roundToPx()
                val collapsedX = LOGO_COLLAPSED_START.roundToPx()
                val collapsedY = (collapsedHeight - logo.height) / 2
                logo.place(lerp(expandedX, collapsedX, fraction), lerp(expandedY, collapsedY, fraction))

                actionRow.place(
                    constraints.maxWidth - actionRow.width - ACTIONS_END_PADDING.roundToPx(),
                    (collapsedHeight - actionRow.height) / 2,
                )
            }
        }
    }
}

/**
 * The tagline and project link displayed at the top of the screen.
 */
@Composable
private fun Header(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.label_heading),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.label_source_link),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Inputs for the duration and mode of the share.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionCard(state: MainUiState, actions: MainActions) {
    val enabled = !state.inputsLocked
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DurationField(
                duration = state.duration,
                unit = state.durationUnit,
                onChange = { duration, unit ->
                    state.duration = duration
                    state.durationUnit = unit
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            DropdownSelector(
                label = stringResource(R.string.label_mode).asLabel(),
                options = stringArrayResource(R.array.sel_mode_opts).asList(),
                icons = ShareMode.entries.map { it.icon },
                selected = state.mode.index,
                onSelect = { state.mode = ShareMode.fromMode(it) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.mode == ShareMode.CREATE_ALONE) {
                AdoptionSwitch(
                    checked = state.allowAdoption,
                    onCheckedChange = { state.allowAdoption = it },
                    onExplain = actions::onExplainAdoptionClick,
                    enabled = enabled,
                )
            }
            if (state.mode.isGroupType) {
                OutlinedTextField(
                    value = state.nickname,
                    onValueChange = { state.nickname = it },
                    label = { Text(stringResource(R.string.label_nickname).asLabel()) },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.mode == ShareMode.JOIN_GROUP) {
                OutlinedTextField(
                    value = state.groupPin,
                    onValueChange = { value -> state.groupPin = value.filter(Char::isDigit).take(GROUP_PIN_LENGTH) },
                    label = { Text(stringResource(R.string.label_pin).asLabel()) },
                    leadingIcon = { Icon(HaukIcons.Pin, contentDescription = null) },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * A read-only text field that opens a menu for choosing one of the given options.
 *
 * @param icons Icons for each option, shown in the menu and in front of the selected option.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DropdownSelector(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    icons: List<ImageVector>? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it && enabled },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = options[selected],
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = label?.let { { Text(it) } },
            leadingIcon = icons?.let { { Icon(it[selected], contentDescription = null) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                SelectableDropdownMenuItem(
                    selected = index == selected,
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    text = { Text(option) },
                    shapes = MenuDefaults.itemShape(index, options.size),
                    leadingIcon = icons?.let { { Icon(it[index], contentDescription = null) } },
                )
            }
        }
    }
}

/**
 * A switch for whether other users may adopt the share, with a button that explains adoption.
 */
@Composable
internal fun AdoptionSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onExplain: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1F)) {
            Text(
                stringResource(R.string.label_allow_adopt).asLabel(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.chk_allow_adopt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onExplain) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = stringResource(R.string.label_explain_adopt).trim('(', ')', '（', '）', ' '),
            )
        }
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
    }
}

/**
 * Displays the current location and connection status.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(state: MainUiState) {
    val colors = MaterialTheme.colorScheme
    val waiting = !state.serverDisconnected && state.status in WAITING_STATES
    val (container, content, icon) = when {
        state.serverDisconnected -> Triple(colors.errorContainer, colors.onErrorContainer, HaukIcons.CloudOff)
        state.status == LocationStatus.ACTIVE -> Triple(colors.primaryContainer, colors.onPrimaryContainer, HaukIcons.GpsFixed)
        state.status == LocationStatus.INACTIVE -> Triple(colors.surfaceContainerHighest, colors.onSurfaceVariant, HaukIcons.LocationOff)
        else -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, HaukIcons.GpsNotFixed)
    }
    val text = if (state.serverDisconnected) R.string.label_status_disconnected else state.status.label

    val containerColor by animateColorAsState(container, MaterialTheme.motionScheme.defaultEffectsSpec())
    val contentColor by animateColorAsState(content, MaterialTheme.motionScheme.defaultEffectsSpec())

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (waiting) {
                    LoadingIndicator(color = contentColor, modifier = Modifier.size(48.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .background(contentColor.copy(alpha = 0.12F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.label_status).asLabel(), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The button that starts and stops sharing. Displays a countdown while sharing is active.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShareButton(state: MainUiState, onClick: () -> Unit) {
    val expiry = state.sessionExpiry
    val height = ButtonDefaults.MediumContainerHeight
    val colors = if (expiry != null) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = !state.busy,
        colors = colors,
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Icon(
            if (expiry != null) HaukIcons.Stop else HaukIcons.NearMe,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        val label = if (expiry != null) {
            stringResource(R.string.btn_stop, TimeUtils.secondsToTime(rememberSecondsUntil(expiry)))
        } else {
            stringResource(R.string.btn_start)
        }
        Text(label, style = ButtonDefaults.textStyleFor(height))
    }
}

/**
 * Returns the number of seconds remaining until the given time, updated every second.
 *
 * @param time A time in milliseconds since the epoch.
 */
@Composable
private fun rememberSecondsUntil(time: Long): Long {
    fun remaining() = ((time - System.currentTimeMillis()) / TimeUtils.MILLIS_PER_SECOND).coerceAtLeast(0L)

    var seconds by remember(time) { mutableLongStateOf(remaining()) }
    LaunchedEffect(time) {
        while (seconds > 0L) {
            delay(TimeUtils.MILLIS_PER_SECOND - System.currentTimeMillis() % TimeUtils.MILLIS_PER_SECOND)
            seconds = remaining()
        }
    }
    return seconds
}

/**
 * Displays the PIN of a group share hosted by the user, and lets them adopt other shares into it.
 */
@Composable
private fun GroupPinCard(share: Share, onAdopt: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.label_pin_head).asLabel(), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    share.joinCode,
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                )
            }
            Text(
                stringResource(R.string.label_pin_help),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdopt, shapes = ButtonDefaults.shapes()) {
                Icon(HaukIcons.CallMerge, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.btn_adopt))
            }
        }
    }
}

/**
 * The list of currently active sharing links.
 */
@Composable
private fun LinkList(links: List<Share>, actions: MainActions) {
    Text(
        stringResource(if (links.isEmpty()) R.string.label_heading_no_links else R.string.label_heading_links),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        links.forEachIndexed { index, share ->
            key(share) {
                LinkItem(share, index, links.size, actions)
            }
        }
    }
}

/**
 * A single sharing link, with buttons to share and stop it.
 */
@Composable
private fun LinkItem(share: Share, index: Int, count: Int, actions: MainActions) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(HaukIcons.Link, contentDescription = null) },
        supportingContent = { Text(stringResource(share.shareMode.descriptorResource)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (share.session.backendVersion.isAtLeast(Constants.VERSION_COMPAT_VIEW_ID)) {
                    IconButton(onClick = { actions.onStopLinkClick(share) }) {
                        Icon(
                            HaukIcons.Stop,
                            contentDescription = stringResource(R.string.btn_stop_short),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                FilledTonalIconButton(onClick = { actions.onShareLinkClick(share) }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.btn_share_short))
                }
            }
        },
    ) {
        Text(share.id, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The icon representing a sharing mode.
 */
private val ShareMode.icon: ImageVector
    get() = when (this) {
        ShareMode.CREATE_ALONE -> Icons.Rounded.Person
        ShareMode.CREATE_GROUP -> HaukIcons.GroupAdd
        ShareMode.JOIN_GROUP -> HaukIcons.Group
    }

private val WAITING_STATES = setOf(LocationStatus.WAITING, LocationStatus.SEARCHING, LocationStatus.COARSE)

private const val GROUP_PIN_LENGTH = 6

private const val LAYOUT_ID_LOGO = "logo"
private const val LAYOUT_ID_ACTIONS = "actions"
private val LOGO_BAR_COLLAPSED_HEIGHT = TopAppBarDefaults.TopAppBarExpandedHeight
private val LOGO_BAR_EXPANDED_HEIGHT = 184.dp
private val LOGO_EXPANDED_HEIGHT = 120.dp
private val LOGO_COLLAPSED_HEIGHT = 32.dp

/**
 * How far the expanded logo extends upwards into the row containing the action buttons.
 */
private val LOGO_EXPANDED_OVERLAP = 16.dp
private val LOGO_COLLAPSED_START = 16.dp
private val ACTIONS_END_PADDING = 4.dp
