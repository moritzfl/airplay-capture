package de.moritzf.airplaycapture.ui;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;

/** Shared behaviour of the confirmation dialogs. */
final class Dialogs {

    private Dialogs() {}

    /**
     * Opens a confirmation for something that cannot be undone with the keyboard focus on the
     * safe choice, so a stray Enter cancels instead of deleting.
     */
    static void openDestructive(ConfirmDialog dialog) {
        dialog.open();
        dialog
            .getElement()
            .executeJs(
                "setTimeout(() => this.querySelector('[slot=\"cancel-button\"]')?.focus(), 60)"
            );
    }
}
