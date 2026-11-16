package com.termux.shared.termux.settings.properties;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the bell behaviour property values. */
public class BellBehaviourPropertyTest {

    @Test
    public void visual_value_is_accepted() {
        assertEquals(
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VISUAL,
            TermuxSharedProperties.getBellBehaviourInternalPropertyValueFromValue(
                TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_VISUAL));
        assertEquals(
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VISUAL,
            TermuxSharedProperties.getBellBehaviourInternalPropertyValueFromValue("VISUAL"));
    }

    @Test
    public void existing_values_keep_their_numbers() {
        assertEquals(1, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE);
        assertEquals(2, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP);
        assertEquals(3, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE);
        assertEquals(4, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VISUAL);
    }

    @Test
    public void unknown_value_falls_back_to_the_default() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR,
            TermuxSharedProperties.getBellBehaviourInternalPropertyValueFromValue("nonsense"));
    }
}
