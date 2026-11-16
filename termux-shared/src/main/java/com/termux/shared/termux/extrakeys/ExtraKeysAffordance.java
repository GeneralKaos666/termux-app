package com.termux.shared.termux.extrakeys;

import androidx.annotation.Nullable;

/**
 * Decides the small affordances drawn on an extra key.
 *
 * Kept as plain logic with no Android types so it is unit-testable without a device: the view
 * supplies the button info and asks whether the popup chevron applies.
 */
public final class ExtraKeysAffordance {

    private ExtraKeysAffordance() {}

    /**
     * Whether a key shows the popup chevron. Only a key whose swipe-up popup exists can open one, so
     * the chevron is drawn exactly for those keys and never for the rest.
     */
    public static boolean showsPopupChevron(@Nullable ExtraKeyButton button) {
        return button != null && button.getPopup() != null;
    }
}
