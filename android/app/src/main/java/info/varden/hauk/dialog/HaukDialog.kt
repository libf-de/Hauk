package info.varden.hauk.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Common layout for all dialogs in Hauk. Unless [dismissible] is set, the dialog can only be closed
 * using its buttons, matching the behavior of the app's previous non-cancelable alert dialogs.
 *
 * @param title   The title of the dialog.
 * @param icon    An optional hero icon displayed above the title.
 * @param buttons The buttons of the dialog, from left to right. The confirming action should be the
 *                last button.
 * @param content The body of the dialog. Scrolls if it does not fit on the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaukDialog(
    title: String,
    icon: ImageVector? = null,
    dismissible: Boolean = false,
    onDismissRequest: () -> Unit = {},
    buttons: @Composable FlowRowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Column(
                            Modifier.weight(1F, fill = false).verticalScroll(rememberScrollState()),
                            content = content,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    content = buttons,
                )
            }
        }
    }
}

/**
 * A button in a [MessageDialog].
 *
 * @param label   The text displayed on the button.
 * @param onClick A callback that is run after the dialog has been closed, or null.
 */
class DialogAction(val label: String, val onClick: Runnable?)

/**
 * A dialog that displays a message and a set of buttons, all of which close the dialog.
 *
 * @param actions The buttons of the dialog, from left to right.
 */
class MessageDialog(
    private val title: String,
    private val message: String,
    private val actions: List<DialogAction>,
) : HostedDialog() {
    @Composable
    override fun Content() {
        HaukDialog(
            title = this.title,
            buttons = {
                for (action in this@MessageDialog.actions) {
                    TextButton(onClick = {
                        dismiss()
                        action.onClick?.run()
                    }) {
                        Text(action.label)
                    }
                }
            },
        ) {
            Text(this@MessageDialog.message)
        }
    }
}

/**
 * A dialog with a loading indicator that is shown while waiting for a background task. It cannot
 * be closed by the user; call [dismiss] when the task completes.
 */
class LoadingDialog(private val title: String, private val message: String) : HostedDialog() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    override fun Content() {
        HaukDialog(title = this.title) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Text(this@LoadingDialog.message)
            }
        }
    }
}
