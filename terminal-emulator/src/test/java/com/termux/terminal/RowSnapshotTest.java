package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RowSnapshotTest {

    @Test
    public void aSnapshotDoesNotFollowLaterWrites() {
        TerminalBuffer screen = new TerminalBuffer(10, 10, 10);
        screen.setChar(0, 0, 'a', TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD));

        TerminalBuffer.RowSnapshot snapshot = screen.snapshotRow(0);
        screen.setChar(0, 0, 'b', TextStyle.NORMAL);

        assertEquals('a', snapshot.text[0]);
        assertEquals(TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD), snapshot.styles[0]);
    }

    @Test
    public void anUntouchedRowSnapshotsAsBlankCells() {
        TerminalBuffer screen = new TerminalBuffer(10, 10, 10);

        TerminalBuffer.RowSnapshot snapshot = screen.snapshotRow(5);

        assertEquals(' ', snapshot.text[0]);
        assertEquals(TextStyle.NORMAL, snapshot.styles[0]);
        assertEquals(10, snapshot.spaceUsed);
        assertEquals(10, snapshot.linkIds.length);
        assertEquals(10, snapshot.underlineColors.length);
    }
}
