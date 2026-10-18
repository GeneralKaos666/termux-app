package com.termux.app.terminal;

import android.content.Intent;

import com.termux.app.TermuxActivity;
import com.termux.view.TerminalViewClient;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

/**
 * Verifies the app-side OSC 8 dispatch: a sanitized hyperlink URI is opened as an
 * {@link Intent#ACTION_VIEW} intent. The URI is produced by {@code TerminalView} (see
 * {@code TerminalViewLinkHitTest} and {@code LinkUrisTest}), so this only covers the hand-off.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TermuxTerminalViewClientLinkTest {

    @Test
    public void testOnLinkClickedOpensViewIntent() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        TerminalViewClient client = new TermuxTerminalViewClient(activity, null);

        client.onLinkClicked("https://example.com/path");

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("onLinkClicked must start an activity", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertNotNull(started.getData());
        assertEquals("https://example.com/path", started.getData().toString());
    }
}
