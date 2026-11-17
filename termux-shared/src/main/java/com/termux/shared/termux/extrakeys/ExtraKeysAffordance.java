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

    /**
     * The popup's display text, when it is short enough to fit in the key's corner badge.
     *
     * @param button The key whose popup is wanted.
     * @param maxCodePoints The most code points the badge can show, counted so an emoji is one
     *                      character rather than two.
     * @return the trimmed display text, or {@code null} when there is no popup, the display is
     *         blank, or it does not fit (the caller then falls back to the chevron).
     */
    @Nullable
    public static String popupBadge(@Nullable ExtraKeyButton button, int maxCodePoints) {
        if (button == null) return null;
        ExtraKeyButton popup = button.getPopup();
        if (popup == null) return null;
        String display = popup.getDisplay();
        if (display == null) return null;
        String trimmed = display.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) return null;
        return trimmed;
    }
}
