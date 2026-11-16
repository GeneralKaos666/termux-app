package com.termux.shared.termux.extrakeys;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the extra-keys popup affordance decision. */
public class ExtraKeysAffordanceTest {

    @Test
    public void chevron_only_when_popup_exists() {
        ExtraKeyButton withPopup = new ExtraKeyButton(":", new ExtraKeyButton("!", null));
        ExtraKeyButton withoutPopup = new ExtraKeyButton("ESC", null);
        assertEquals(true, ExtraKeysAffordance.showsPopupChevron(withPopup));
        assertEquals(false, ExtraKeysAffordance.showsPopupChevron(withoutPopup));
        assertEquals(false, ExtraKeysAffordance.showsPopupChevron(null));
    }
}
