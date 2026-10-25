package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.util.Properties;

/** Tests for {@link TerminalColorScheme}, including the cursor-for-background brightness logic. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalColorSchemeTest {

    @Test
    public void defaultSchemeUsesThemeColors() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        assertEquals(0xffffffff, scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]);
        assertEquals(0xff000000, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(0xffffffff, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
    }

    @Test
    public void updateWithParsesNamedColors() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties props = new Properties();
        props.setProperty("foreground", "#ff0000");
        props.setProperty("background", "#00ff00");
        props.setProperty("cursor", "#0000ff");
        scheme.updateWith(props);
        assertEquals(0xffff0000, scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]);
        assertEquals(0xff00ff00, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(0xff0000ff, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
    }

    @Test
    public void updateWithParsesIndexedColors() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties props = new Properties();
        props.setProperty("color5", "#123456");
        scheme.updateWith(props);
        assertEquals(0xff123456, scheme.mDefaultColors[5]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void updateWithRejectsUnknownKey() {
        Properties props = new Properties();
        props.setProperty("bogus", "#ff0000");
        new TerminalColorScheme().updateWith(props);
    }

    @Test(expected = IllegalArgumentException.class)
    public void updateWithRejectsInvalidColor() {
        Properties props = new Properties();
        props.setProperty("foreground", "notacolor");
        new TerminalColorScheme().updateWith(props);
    }

    @Test
    public void updateWithPicksDarkCursorForLightBackground() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties props = new Properties();
        props.setProperty("background", "#ffffff"); // Brightness 255 >= 130.
        scheme.updateWith(props);
        assertEquals(0xff000000, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
    }

    @Test
    public void updateWithPicksLightCursorForDarkBackground() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties props = new Properties();
        props.setProperty("background", "#333333"); // Brightness 51, so 0 < 51 < 130.
        scheme.updateWith(props);
        assertEquals(0xffffffff, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
    }

    @Test
    public void perceivedBrightness() {
        assertEquals(0, TerminalColors.getPerceivedBrightnessOfColor(0xff000000));
        assertEquals(255, TerminalColors.getPerceivedBrightnessOfColor(0xffffffff));
        assertEquals(125, TerminalColors.getPerceivedBrightnessOfColor(0xffff0000));
        assertEquals(211, TerminalColors.getPerceivedBrightnessOfColor(0xff00ff00));
        assertEquals(66, TerminalColors.getPerceivedBrightnessOfColor(0xff0000ff));
    }
}
