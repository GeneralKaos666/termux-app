package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tests for the {@link ITermImage} OSC 1337 image state machine: the argument parser, the
 * singlepart/multipart read flow and the base64 decode step. The pixel-producing side (bitmap
 * placement) is exercised by {@link TerminalEmulatorKittyTest} and the buffer-level placement
 * tests, so this class focuses on the untested state machine and parsing logic.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ITermImageTest {

    private static final int COLS = 10;
    private static final int ROWS = 4;
    private static final int CELL_WIDTH_PX = 10;
    private static final int CELL_HEIGHT_PX = 20;

    private TerminalEmulator newEmulator() {
        return new TerminalEmulator(new NoopOutput(), COLS, ROWS, CELL_WIDTH_PX, CELL_HEIGHT_PX, ROWS, null);
    }

    // --- singlepart lifecycle -------------------------------------------------

    @Test
    public void singlepartHappyPath() {
        ITermImage image = new ITermImage(null, /* multiPart */ false);
        StringBuilder oscArgs = new StringBuilder("inline=1;width=100px:QUJD");

        int index = image.readArguments(newEmulator(), oscArgs, 0);
        assertEquals(20, index);
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getCurrentState());
        assertTrue(image.isInline());
        assertEquals(100, image.getWidth());

        assertTrue(image.readImage(oscArgs, index));
        assertEquals(ITermImage.ImageState.IMAGE_READ, image.getCurrentState());

        assertTrue(image.decodeImage());
        assertEquals(ITermImage.ImageState.IMAGE_DECODED, image.getCurrentState());
        assertArrayEquals(new byte[] {'A', 'B', 'C'}, image.getDecodedImage());
    }

    @Test
    public void singlepartRequiresColonAndData() {
        ITermImage image = new ITermImage(null, /* multiPart */ false);
        image.setState(ITermImage.ImageState.ARGUMENTS_READ);

        // No colon: readImage fails and leaves the image in FAILED.
        assertFalse(image.readImage(new StringBuilder("QUJD"), 0));
        assertEquals(ITermImage.ImageState.FAILED, image.getCurrentState());
    }

    // --- argument parsing -----------------------------------------------------

    @Test
    public void readArgumentsParsesInlineAndPreserveAspectRatio() {
        ITermImage image = new ITermImage(null, /* multiPart */ false);
        // Defaults.
        assertFalse(image.isInline());
        assertTrue(image.shouldPreserveAspectRatio());

        image.readArguments(newEmulator(), new StringBuilder("inline=1;preserveAspectRatio=0:QUJD"), 0);
        assertTrue(image.isInline());
        assertFalse(image.shouldPreserveAspectRatio());
    }

    @Test
    public void readArgumentsParsesWidthAndHeightUnits() {
        // Cell-based width: factor = cellWidthPixels (10). height: factor = cellHeightPixels (20).
        ITermImage cells = new ITermImage(null, false);
        cells.readArguments(newEmulator(), new StringBuilder("width=10;height=4:QUJD"), 0);
        assertEquals(100, cells.getWidth());
        assertEquals(80, cells.getHeight());

        // Pixel-based: factor = 1.
        ITermImage px = new ITermImage(null, false);
        px.readArguments(newEmulator(), new StringBuilder("width=100px;height=50px:QUJD"), 0);
        assertEquals(100, px.getWidth());
        assertEquals(50, px.getHeight());

        // Percent-based: factor = 0.01 * cellPixels * screen extent (10 * 10 = 1.0 width, 20 * 4 = 0.8 height).
        ITermImage pct = new ITermImage(null, false);
        pct.readArguments(newEmulator(), new StringBuilder("width=50%;height=50%:QUJD"), 0);
        assertEquals(50, pct.getWidth());
        assertEquals(40, pct.getHeight());
    }

    @Test
    public void readArgumentsIgnoresUnsupportedKeys() {
        ITermImage image = new ITermImage(null, false);
        int index = image.readArguments(newEmulator(), new StringBuilder("name=foo;size=123:QUJD"), 0);
        assertEquals(17, index);
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getCurrentState());
        assertEquals(-1, image.getWidth());
        assertEquals(-1, image.getHeight());
    }

    @Test
    public void readArgumentsFailsWhenKeyHasNoEquals() {
        ITermImage image = new ITermImage(null, false);
        assertEquals(-1, image.readArguments(newEmulator(), new StringBuilder("noEqualsHere"), 0));
        assertEquals(ITermImage.ImageState.FAILED, image.getCurrentState());
    }

    // --- multipart lifecycle --------------------------------------------------

    @Test
    public void multipartAccumulatesPartsAndEnds() {
        ITermImage image = new ITermImage(null, /* multiPart */ true);

        // Multipart arguments may end without a ';'.
        image.readArguments(newEmulator(), new StringBuilder("inline=1"), 0);
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getCurrentState());
        assertTrue(image.isInline());

        // A FilePart appends encoded data and stays in IMAGE_READING.
        assertTrue(image.readImage(new StringBuilder("QU"), 0));
        assertTrue(image.readImage(new StringBuilder("JD"), 0));
        assertEquals(ITermImage.ImageState.IMAGE_READING, image.getCurrentState());

        assertTrue(image.setMultiPartImageRead());
        assertTrue(image.decodeImage());
        assertEquals(ITermImage.ImageState.IMAGE_DECODED, image.getCurrentState());
        assertArrayEquals(new byte[] {'A', 'B', 'C'}, image.getDecodedImage());
    }

    @Test
    public void setMultiPartImageReadRequiresPart() {
        ITermImage image = new ITermImage(null, /* multiPart */ true);
        // Still ARGUMENTS_READ: no FilePart preceded the FileEnd.
        assertFalse(image.setMultiPartImageRead());
    }

    @Test
    public void setMultiPartImageReadRejectedForSinglepart() {
        ITermImage image = new ITermImage(null, /* multiPart */ false);
        image.setState(ITermImage.ImageState.IMAGE_READING);
        assertFalse(image.setMultiPartImageRead());
    }

    // --- state machine ---------------------------------------------------------

    @Test
    public void stateCannotTransitionBackwards() {
        ITermImage image = new ITermImage(null, false);
        assertTrue(image.setState(ITermImage.ImageState.ARGUMENTS_READ));
        assertFalse(image.setState(ITermImage.ImageState.INIT));
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getCurrentState());
    }

    @Test
    public void stateCannotLeaveDecoded() {
        ITermImage image = new ITermImage(null, false);
        assertTrue(image.setState(ITermImage.ImageState.ARGUMENTS_READ));
        assertTrue(image.setState(ITermImage.ImageState.IMAGE_READING));
        assertTrue(image.setState(ITermImage.ImageState.IMAGE_READ));
        assertTrue(image.setState(ITermImage.ImageState.IMAGE_DECODED));

        assertFalse(image.setState(ITermImage.ImageState.FAILED));
        assertEquals(ITermImage.ImageState.IMAGE_DECODED, image.getCurrentState());
    }

    @Test
    public void failedPreservesPreviousValidState() {
        ITermImage image = new ITermImage(null, false);
        assertTrue(image.setState(ITermImage.ImageState.ARGUMENTS_READ));
        assertEquals(ITermImage.ImageState.INIT, image.getPreviousState());

        assertTrue(image.setStateFailed("boom"));
        assertEquals(ITermImage.ImageState.FAILED, image.getCurrentState());
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getPreviousState());

        // Setting FAILED again does not overwrite the last valid state.
        assertTrue(image.setStateFailed("boom again"));
        assertEquals(ITermImage.ImageState.ARGUMENTS_READ, image.getPreviousState());
    }

    // --- decoding --------------------------------------------------------------

    @Test
    public void decodeEmptyImageFails() {
        ITermImage image = new ITermImage(null, false);
        image.setState(ITermImage.ImageState.IMAGE_READ);
        assertFalse(image.decodeImage());
        assertEquals(ITermImage.ImageState.FAILED, image.getCurrentState());
    }

    @Test
    public void decodeTooShortImageFails() {
        ITermImage image = new ITermImage(null, false);
        image.setState(ITermImage.ImageState.IMAGE_READ);
        image.mEncodedImage.append("QQ=="); // decodes to a single byte, below the 2-byte minimum.
        assertFalse(image.decodeImage());
        assertEquals(ITermImage.ImageState.FAILED, image.getCurrentState());
    }

    @Test
    public void decodeRequiresReadState() {
        ITermImage image = new ITermImage(null, false);
        // Still INIT: decodeImage must reject the out-of-order call.
        assertFalse(image.decodeImage());
    }

    // --- helper ----------------------------------------------------------------

    private static final class NoopOutput extends TerminalOutput {
        @Override
        public void write(byte[] data, int offset, int count) {
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
