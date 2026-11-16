package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the selection handle tint decision. */
public class HandleTintTest {

    @Test
    public void keeps_accent_when_it_contrasts() {
        assertEquals(0xFFFF0000, HandleTint.handleTint(0xFF000000, 0xFFFF0000));
    }

    @Test
    public void flips_to_white_on_low_contrast_dark_background() {
        assertEquals(0xFFFFFFFF, HandleTint.handleTint(0xFF101010, 0xFF202020));
    }

    @Test
    public void flips_to_black_on_low_contrast_light_background() {
        assertEquals(0xFF000000, HandleTint.handleTint(0xFFFFFFFF, 0xFFEEEEEE));
    }

    @Test
    public void a_translucent_accent_is_judged_by_its_rgb() {
        // The same red as the first case, but with alpha 0x80: the contrast test must ignore alpha.
        assertEquals(0xFFFF0000, HandleTint.handleTint(0xFF000000, 0x80FF0000));
    }

    @Test
    public void white_is_chosen_on_a_mid_dark_background() {
        // #333 background is dark enough that white contrasts more than black.
        assertEquals(0xFFFFFFFF, HandleTint.handleTint(0xFF333333, 0xFF3A3A3A));
    }

    @Test
    public void black_is_chosen_on_a_mid_light_background() {
        assertEquals(0xFF000000, HandleTint.handleTint(0xFFCCCCCC, 0xFFC8C8C8));
    }
}
