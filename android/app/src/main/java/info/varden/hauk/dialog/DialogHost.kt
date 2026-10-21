package info.varden.hauk.dialog

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf

/**
 * Implemented by activities that can display dialogs through a [DialogHost].
 */
interface DialogHostOwner {
    val dialogHost: DialogHost
}

/**
 * Keeps track of the dialogs that are shown on an activity and renders them with Compose. Dialogs
 * can be shown and dismissed from any thread.
 *
 * Only the most recently shown dialog is rendered. When it is dismissed, the dialog that was shown
 * before it reappears, mirroring how stacked dialog windows behave.
 */
class DialogHost {
    private val handler = Handler(Looper.getMainLooper())
    private val dialogs = mutableStateListOf<HostedDialog>()

    /**
     * Shows the given dialog on top of any other dialogs.
     *
     * @return The dialog that was passed, so that it can be dismissed later.
     */
    fun <T : HostedDialog> show(dialog: T): T {
        dialog.host = this
        runOnMain { if (!dialog.isDismissed) dialogs.add(dialog) }
        return dialog
    }

    internal fun remove(dialog: HostedDialog) {
        runOnMain { dialogs.remove(dialog) }
    }

    private fun runOnMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) task() else handler.post(task)
    }

    /**
     * Renders the topmost dialog. Must be called from within the activity's composition.
     */
    @Composable
    fun Render() {
        val top = dialogs.lastOrNull() ?: return
        key(top) { top.Content() }
    }

    companion object {
        /**
         * Finds the dialog host of the activity that the given context belongs to.
         *
         * @throws IllegalStateException if the context does not belong to a [DialogHostOwner].
         */
        @JvmStatic
        fun from(ctx: Context): DialogHost {
            var current: Context? = ctx
            while (current != null) {
                if (current is DialogHostOwner) return current.dialogHost
                current = (current as? ContextWrapper)?.baseContext
            }
            throw IllegalStateException("Context $ctx is not able to host dialogs") //NON-NLS
        }
    }
}

/**
 * A dialog that can be shown using a [DialogHost]. Any state that should survive while another
 * dialog is shown on top of this one must be stored in the dialog object itself rather than in the
 * composition.
 */
abstract class HostedDialog {
    @Volatile
    internal var host: DialogHost? = null

    @Volatile
    internal var isDismissed = false
        private set

    /**
     * Closes this dialog.
     */
    fun dismiss() {
        this.isDismissed = true
        this.host?.remove(this)
    }

    @Composable
    abstract fun Content()
}
