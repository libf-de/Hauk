package info.varden.hauk.dialog;

import android.content.Context;

import java.util.Arrays;
import java.util.Collections;

import info.varden.hauk.R;
import info.varden.hauk.utils.Log;

/**
 * A helper class for creating dialogs on the main activity. The dialogs are rendered by the
 * {@link DialogHost} of the activity that the given context belongs to.
 *
 * @author Marius Lindvall
 */
@SuppressWarnings({"WeakerAccess", "ClassNamePrefixedWithPackageName"})
public final class DialogService {
    /**
     * Android application context.
     */
    private final Context ctx;

    /**
     * The host that renders the dialogs.
     */
    private final DialogHost host;

    public DialogService(Context ctx) {
        this.ctx = ctx;
        this.host = DialogHost.from(ctx);
    }

    /**
     * Shows a dialog box with an OK button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string resource representing the body of the dialog box.
     */
    public void showDialog(int title, int message) {
        showDialog(title, this.ctx.getString(message));
    }

    /**
     * Shows a dialog box with an OK button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     */
    public void showDialog(int title, String message) {
        showDialog(title, message, null);
    }

    /**
     * Shows a dialog box with an OK button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string resource representing the body of the dialog box.
     * @param onOK    A callback that is run when the user clicks the OK button.
     */
    public void showDialog(int title, int message, Runnable onOK) {
        showDialog(title, this.ctx.getString(message), onOK);
    }

    /**
     * Shows a dialog box with an OK button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param onOK    A callback that is run when the user clicks the OK button.
     */
    public void showDialog(int title, String message, Runnable onOK) {
        showDialog(this.ctx.getString(title), message, onOK);
    }

    /**
     * Shows a dialog box with an OK button.
     *
     * @param title   A string representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param onOK    A callback that is run when the user clicks the OK button.
     */
    private void showDialog(String title, String message, Runnable onOK) {
        Log.v("Showing dialog with title=%s, message=%s, run=%s", title, message, onOK); //NON-NLS
        this.host.show(new MessageDialog(title, message, Collections.singletonList(
                new DialogAction(this.ctx.getString(R.string.btn_ok), onOK)
        )));
    }

    /**
     * Shows a dialog box with OK and Cancel buttons.
     *
     * @param title    A string resource representing the title of the dialog box.
     * @param message  A string resource representing the body of the dialog box.
     * @param onOK     A callback that is run when the user clicks the OK button.
     * @param onCancel A callback that is run when the user clicks the cancel button.
     */
    public void showDialog(int title, int message, Runnable onOK, Runnable onCancel) {
        showDialog(title, this.ctx.getString(message), onOK, onCancel);
    }

    /**
     * Shows a dialog box with OK and Cancel buttons.
     *
     * @param title    A string resource representing the title of the dialog box.
     * @param message  A string representing the body of the dialog box.
     * @param onOK     A callback that is run when the user clicks the OK button.
     * @param onCancel A callback that is run when the user clicks the cancel button.
     */
    public void showDialog(int title, String message, Runnable onOK, Runnable onCancel) {
        showDialog(this.ctx.getString(title), message, onOK, onCancel);
    }

    /**
     * Shows a dialog box with OK and Cancel buttons.
     *
     * @param title    A string representing the title of the dialog box.
     * @param message  A string representing the body of the dialog box.
     * @param onOK     A callback that is run when the user clicks the OK button.
     * @param onCancel A callback that is run when the user clicks the cancel button.
     */
    private void showDialog(String title, String message, Runnable onOK, Runnable onCancel) {
        Log.v("Showing dialog with title=%s, message=%s, run=%s, onCancel=%s", title, message, onOK, onCancel); //NON-NLS
        this.host.show(new MessageDialog(title, message, Arrays.asList(
                new DialogAction(this.ctx.getString(R.string.btn_cancel), onCancel),
                new DialogAction(this.ctx.getString(R.string.btn_ok), onOK)
        )));
    }

    /**
     * Shows a dialog box with a positive and a negative button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string resource representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    public void showDialog(int title, int message, Buttons.Two buttons, CustomDialogBuilder builder) {
        showDialog(title, this.ctx.getString(message), buttons, builder);
    }

    /**
     * Shows a dialog box with a positive and a negative button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    public void showDialog(int title, String message, Buttons.Two buttons, CustomDialogBuilder builder) {
        showDialog(this.ctx.getString(title), message, buttons, builder);
    }

    /**
     * Shows a dialog box with a positive and a negative button.
     *
     * @param title   A string representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    private void showDialog(String title, String message, Buttons.Two buttons, CustomDialogBuilder builder) {
        Log.d("Showing two-button dialog with title=%s, message=%s, builder=%s", title, message, builder); //NON-NLS
        this.host.show(new MessageDialog(title, message, Arrays.asList(
                new DialogAction(this.ctx.getString(buttons.getNegativeButton()), new NegativeClickListener(builder)),
                new DialogAction(this.ctx.getString(buttons.getPositiveButton()), new PositiveClickListener(builder))
        )));
    }

    /**
     * Shows a dialog box with a positive, a neutral and a negative button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string resource representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    public void showDialog(int title, int message, Buttons.Three buttons, CustomDialogBuilder.Three builder) {
        showDialog(title, this.ctx.getString(message), buttons, builder);
    }

    /**
     * Shows a dialog box with a positive, a neutral and a negative button.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    public void showDialog(int title, String message, Buttons.Three buttons, CustomDialogBuilder.Three builder) {
        showDialog(this.ctx.getString(title), message, buttons, builder);
    }

    /**
     * Shows a dialog box with a positive, a neutral and a negative button.
     *
     * @param title   A string representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @param buttons The buttons to display on the dialog.
     * @param builder A dialog builder that handles the dialog buttons.
     */
    private void showDialog(String title, String message, Buttons.Three buttons, CustomDialogBuilder.Three builder) {
        Log.d("Showing three-button dialog with title=%s, message=%s, builder=%s", title, message, builder); //NON-NLS
        this.host.show(new MessageDialog(title, message, Arrays.asList(
                new DialogAction(this.ctx.getString(buttons.getNeutralButton()), new NeutralClickListener(builder)),
                new DialogAction(this.ctx.getString(buttons.getNegativeButton()), new NegativeClickListener(builder)),
                new DialogAction(this.ctx.getString(buttons.getPositiveButton()), new PositiveClickListener(builder))
        )));
    }

    /**
     * Shows a non-cancelable dialog with a loading indicator.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string resource representing the body of the dialog box.
     * @return The dialog, which must be dismissed when the operation completes.
     */
    public HostedDialog showProgress(int title, int message) {
        return showProgress(title, this.ctx.getString(message));
    }

    /**
     * Shows a non-cancelable dialog with a loading indicator.
     *
     * @param title   A string resource representing the title of the dialog box.
     * @param message A string representing the body of the dialog box.
     * @return The dialog, which must be dismissed when the operation completes.
     */
    public HostedDialog showProgress(int title, String message) {
        Log.v("Showing progress dialog with title=%s, message=%s", title, message); //NON-NLS
        return this.host.show(new LoadingDialog(this.ctx.getString(title), message));
    }

    /**
     * A click listener for a dialog button that calls the positive action handler of the given
     * {@link CustomDialogBuilder} when the button is clicked.
     */
    private static final class PositiveClickListener implements Runnable {
        private final CustomDialogBuilder builder;

        private PositiveClickListener(CustomDialogBuilder builder) {
            this.builder = builder;
        }

        @Override
        public void run() {
            Log.d("Closing dialog (positive)"); //NON-NLS
            this.builder.onPositive();
        }
    }

    /**
     * A click listener for a dialog button that calls the neutral action handler of the given
     * {@link CustomDialogBuilder} when the button is clicked.
     */
    private static final class NeutralClickListener implements Runnable {
        private final CustomDialogBuilder.Three builder;

        private NeutralClickListener(CustomDialogBuilder.Three builder) {
            this.builder = builder;
        }

        @Override
        public void run() {
            Log.d("Closing dialog (neutral)"); //NON-NLS
            this.builder.onNeutral();
        }
    }

    /**
     * A click listener for a dialog button that calls the negative action handler of the given
     * {@link CustomDialogBuilder} when the button is clicked.
     */
    private static final class NegativeClickListener implements Runnable {
        private final CustomDialogBuilder builder;

        private NegativeClickListener(CustomDialogBuilder builder) {
            this.builder = builder;
        }

        @Override
        public void run() {
            Log.d("Closing dialog (negative)"); //NON-NLS
            this.builder.onNegative();
        }
    }
}
