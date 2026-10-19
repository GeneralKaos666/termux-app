package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import com.termux.terminal.kitty.KittyGraphicsImageDecoder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.nio.charset.StandardCharsets;

/**
 * End-to-end tests for the kitty graphics APC path: chunking, decoding, placement, replies and
 * deletion. A fake decoder is injected so the tests do not depend on a real image payload.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalEmulatorKittyTest {

    private RecordingOutput output;
    private TerminalEmulator emulator;

    @Before
    public void setUp() {
        output = new RecordingOutput();
        emulator = new TerminalEmulator(output, 10, 4, 10, 20, 4, null);
    }

    @Test
    public void queryRepliesOk() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=q,i=31,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals("\033_Gi=31;OK\033\\", output.text());
    }

    @Test
    public void queryDoesNotStore() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=q,i=31,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals(0, emulator.getScreen().getImagePlacements(31).length);
        assertFalse(hasBitmapStyle());
    }

    @Test
    public void queryRepliesErrorWhenDecodeFails() {
        emulator.setKittyGraphicsImageDecoder(failingDecoder());
        feed("\033_Ga=q,i=31,s=1,v=1,f=32;AAAAAA==\033\\");
        assertTrue(output.text().startsWith("\033_Gi=31;EINVAL"));
        assertEquals(0, emulator.getScreen().getImagePlacements(31).length);
    }

    @Test
    public void storeThenPlaceDrawsPlaceholders() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=t,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        assertFalse("Store alone must not draw", hasBitmapStyle());
        feed("\033_Ga=T,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        assertTrue(emulator.getScreen().getImagePlacements(7).length > 0);
        assertTrue(hasBitmapStyle());
    }

    @Test
    public void placeStoredImageDrawsPlaceholders() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=t,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        feed("\033_Ga=p,i=7\033\\");
        assertTrue(emulator.getScreen().getImagePlacements(7).length > 0);
        assertTrue(hasBitmapStyle());
    }

    @Test
    public void placeUnknownIdRepliesEnoent() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=p,i=42\033\\");
        assertTrue(output.text().startsWith("\033_Gi=42;ENOENT"));
        assertFalse(hasBitmapStyle());
    }

    @Test
    public void quietSuppressesOkButNotErrors() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=q,i=31,q=1,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals("", output.text());

        emulator.setKittyGraphicsImageDecoder(failingDecoder());
        feed("\033_Ga=q,i=32,q=1,s=1,v=1,f=32;AAAAAA==\033\\");
        assertTrue(output.text().startsWith("\033_Gi=32;EINVAL"));

        output.clear();
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=q,i=33,q=2,s=1,v=1,f=32;AAAAAA==\033\\");
        emulator.setKittyGraphicsImageDecoder(failingDecoder());
        feed("\033_Ga=q,i=34,q=2,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals("", output.text());
    }

    @Test
    public void oversizeDeclaredSizeRepliesEnospcBeforeDecoding() {
        final int[] calls = {0};
        emulator.setKittyGraphicsImageDecoder(new KittyGraphicsImageDecoder() {
            @Override
            public Bitmap decode(int format, int width, int height, byte[] data) {
                calls[0]++;
                return tinyBitmap();
            }
        });
        feed("\033_Ga=T,i=71,s=100000,v=100000,f=32;AAAAAA==\033\\");
        assertTrue(output.text().startsWith("\033_Gi=71;ENOSPC"));
        assertEquals(0, calls[0]);
    }

    @Test
    public void wrongPayloadLengthRepliesEinvalBeforeDecoding() {
        final int[] calls = {0};
        emulator.setKittyGraphicsImageDecoder(new KittyGraphicsImageDecoder() {
            @Override
            public Bitmap decode(int format, int width, int height, byte[] data) {
                calls[0]++;
                return tinyBitmap();
            }
        });
        // s=2,v=2,f=32 expects 16 bytes; the payload decodes to 4.
        feed("\033_Ga=T,i=72,s=2,v=2,f=32;AAAAAA==\033\\");
        assertTrue(output.text().startsWith("\033_Gi=72;EINVAL"));
        assertEquals(0, calls[0]);
    }

    @Test
    public void chunkedTransferStoresAtTheFinalChunk() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=8,s=1,v=1,f=32,m=1;AAAA\033\\");
        assertEquals("No reply until the transfer completes", "", output.text());
        feed("\033_Gm=0;AA==\033\\");
        assertEquals("\033_Gi=8;OK\033\\", output.text());
        assertEquals(1, emulator.getScreen().getImagePlacements(8).length);
        assertTrue(hasBitmapStyle());
    }

    @Test
    public void chunkInterruptedByUnrelatedOutputLeavesNoState() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=9,s=1,v=1,f=32,m=1;AAAA\033\\");
        feed("hello");
        feed("\033_Ga=q,i=9,s=1,v=1,f=32;AAAAAA==\033\\");
        assertFalse(hasBitmapStyle());
        assertEquals(0, emulator.getScreen().getImagePlacements(9).length);
        assertTrue(rowText(0).startsWith("hello"));
    }

    @Test
    public void deleteByImageIdClearsPlaceholdersAndRepliesOk() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        assertTrue(hasBitmapStyle());

        output.clear();
        feed("\033_Ga=d,d=i,i=7\033\\");
        assertEquals("\033_Gi=7;OK\033\\", output.text());
        assertEquals(0, emulator.getScreen().getImagePlacements(7).length);
        assertFalse(hasBitmapStyle());
    }

    @Test
    public void deleteMissingImageRepliesEnoent() {
        feed("\033_Ga=d,d=i,i=99\033\\");
        assertTrue(output.text().startsWith("\033_Gi=99;ENOENT"));
    }

    @Test
    public void cursorMovesBelowThePlacementByDefault() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=5,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals(1, emulator.getCursorRow());
        assertEquals(0, emulator.getCursorCol());
    }

    @Test
    public void cursorIsUnchangedWhenCIsOne() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=5,C=1,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals(0, emulator.getCursorRow());
        assertEquals(0, emulator.getCursorCol());
    }

    @Test
    public void retransmitSameIdReplacesPreviousPlacement() {
        emulator.setKittyGraphicsImageDecoder(decoderReturning(tinyBitmap()));
        feed("\033_Ga=T,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        feed("\033_Ga=T,i=7,s=1,v=1,f=32;AAAAAA==\033\\");
        assertEquals(1, emulator.getScreen().getImagePlacements(7).length);
    }

    @Test
    public void nonKittyApcIsIgnored() {
        // Matches ApcTest: the APC content contains an escaped ESC and non printable bytes.
        feed("hi\033_some\023\033_\\apc#end\033\\ok");
        assertTrue(rowText(0).startsWith("hiok"));
    }

    // --- helpers -----------------------------------------------------------

    private void feed(String sequence) {
        byte[] bytes = sequence.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private boolean hasBitmapStyle() {
        TerminalBuffer screen = emulator.getScreen();
        for (int row = 0; row < screen.mScreenRows; row++) {
            for (int col = 0; col < screen.mColumns; col++) {
                if (TextStyle.isTerminalBitmap(screen.getStyleAt(row, col))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String rowText(int row) {
        return emulator.getScreen().getSelectedText(0, row, emulator.getScreen().mColumns - 1, row);
    }

    private static Bitmap tinyBitmap() {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    }

    private static KittyGraphicsImageDecoder decoderReturning(final Bitmap bitmap) {
        return new KittyGraphicsImageDecoder() {
            @Override
            public Bitmap decode(int format, int width, int height, byte[] data) {
                return bitmap;
            }
        };
    }

    private static KittyGraphicsImageDecoder failingDecoder() {
        return new KittyGraphicsImageDecoder() {
            @Override
            public Bitmap decode(int format, int width, int height, byte[] data) {
                return null;
            }
        };
    }

    private static final class RecordingOutput extends TerminalOutput {
        private final StringBuilder builder = new StringBuilder();

        @Override
        public void write(byte[] data, int offset, int count) {
            builder.append(new String(data, offset, count, StandardCharsets.UTF_8));
        }

        String text() {
            return builder.toString();
        }

        void clear() {
            builder.setLength(0);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
        }

        @Override
        public void onCopyTextToClipboard(String text) {
        }

        @Override
        public void onPasteTextFromClipboard() {
        }

        @Override
        public void onBell() {
        }

        @Override
        public void onColorsChanged() {
        }
    }

}
