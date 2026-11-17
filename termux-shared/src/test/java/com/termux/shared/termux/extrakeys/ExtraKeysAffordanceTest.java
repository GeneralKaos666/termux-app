package com.termux.shared.termux.extrakeys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void shortPopupDisplaysAreUsedAsTheBadge() {
        ExtraKeyButton one = new ExtraKeyButton(";", new ExtraKeyButton("~", null));
        ExtraKeyButton three = new ExtraKeyButton(":", new ExtraKeyButton("ESC", null));
        assertEquals("~", ExtraKeysAffordance.popupBadge(one, 3));
        assertEquals("ESC", ExtraKeysAffordance.popupBadge(three, 3));
    }

    @Test
    public void longPopupDisplaysFallBackToTheChevron() {
        ExtraKeyButton four = new ExtraKeyButton(":", new ExtraKeyButton("HOME", null));
        ExtraKeyButton macro = new ExtraKeyButton(":", new ExtraKeyButton("CTRL C", null));
        assertNull(ExtraKeysAffordance.popupBadge(four, 3));
        assertNull(ExtraKeysAffordance.popupBadge(macro, 3));
    }

    @Test
    public void missingPopupOrBlankDisplayHasNoBadge() {
        assertNull(ExtraKeysAffordance.popupBadge(null, 3));
        assertNull(ExtraKeysAffordance.popupBadge(new ExtraKeyButton("ESC", null), 3));
        assertNull(ExtraKeysAffordance.popupBadge(
            new ExtraKeyButton(":", new ExtraKeyButton("  ", null)), 3));
    }

    @Test
    public void badgeLimitCountsCodePointsNotCharacters() {
        ExtraKeyButton emoji = new ExtraKeyButton(":", new ExtraKeyButton("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", null));
        assertEquals("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", ExtraKeysAffordance.popupBadge(emoji, 3));
    }
}
