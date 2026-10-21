package info.varden.hauk.dialog;

/**
 * A handler for the button events of dialogs shown using the DialogService.
 *
 * @author Marius Lindvall
 */
public interface CustomDialogBuilder {
    /**
     * Fires when the positive button is clicked in the dialog.
     */
    void onPositive();

    /**
     * Fires when the negative button is clicked in the dialog.
     */
    void onNegative();

    interface Three extends CustomDialogBuilder {
        /**
         * Fires when the neutral button is clicked in the dialog.
         */
        void onNeutral();
    }
}
