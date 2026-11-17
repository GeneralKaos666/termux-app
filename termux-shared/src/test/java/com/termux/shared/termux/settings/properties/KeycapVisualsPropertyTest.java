package com.termux.shared.termux.settings.properties;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The keycap drawing flag is only reachable if the loader knows the key and its default. */
public class KeycapVisualsPropertyTest {

    @Test
    public void theKeyIsLoadedAndDefaultsToTrue() {
        String key = TermuxPropertyConstants.KEY_KEYCAP_VISUALS;
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key));
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key));
    }
}
