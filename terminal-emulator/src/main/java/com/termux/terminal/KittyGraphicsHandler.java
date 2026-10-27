package com.termux.terminal;

import android.graphics.Bitmap;

import com.termux.terminal.kitty.AndroidKittyGraphicsImageDecoder;
import com.termux.terminal.kitty.KittyBase64;
import com.termux.terminal.kitty.KittyGraphicsCommand;
import com.termux.terminal.kitty.KittyGraphicsImageDecoder;
import com.termux.terminal.kitty.KittyGraphicsParser;
import com.termux.terminal.kitty.KittyGraphicsTransfer;
import com.termux.terminal.kitty.KittyImageSizing;

import java.util.HashMap;

/**
 * Handles kitty graphics APC commands ({@code ESC _ G ... ESC \}): chunked transfer reassembly,
 * payload decode, the image store, placement and the reply protocol. Extracted from
 * {@link TerminalEmulator} so the emulator only owns the APC escape-state dispatch.
 */
final class KittyGraphicsHandler {

    private final TerminalEmulator mEmulator;
    private final KittyGraphicsTransfer mTransfer = new KittyGraphicsTransfer();
    private KittyGraphicsImageDecoder mDecoder = new AndroidKittyGraphicsImageDecoder();
    private final HashMap<Integer, Bitmap> mImages = new HashMap<>();

    KittyGraphicsHandler(TerminalEmulator emulator) {
        mEmulator = emulator;
    }

    void setImageDecoder(KittyGraphicsImageDecoder decoder) {
        mDecoder = decoder;
    }

    void reset() {
        mTransfer.reset();
        mImages.clear();
    }

    /**
     * Handle an APC content string. Anything that is not a kitty graphics command (a {@code G}
     * prefix) is ignored, exactly as before.
     */
    void handle(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        if (!content.startsWith("G")) {
            // Not a kitty graphics command; ignore.
            return;
        }

        KittyGraphicsCommand parsed = KittyGraphicsParser.parse(content, null);
        KittyGraphicsTransfer.Result result = mTransfer.accept(parsed);
        if (!result.complete) {
            // A parse/continuation error terminates any in-flight transfer.
            if (result.errorCode != null) {
                replyError(parsed, result.errorCode,
                    result.errorMessage != null ? result.errorMessage : parsed.errorMessage);
            }
            return;
        }
        handleCommand(result.command);
    }

    private void handleCommand(KittyGraphicsCommand cmd) {
        if (cmd == null) {
            return;
        }
        switch (cmd.action) {
            case 'q':
                doQuery(cmd);
                break;
            case 't':
                doStore(cmd, false);
                break;
            case 'T':
                doStore(cmd, true);
                break;
            case 'p':
                doPlace(cmd);
                break;
            case 'd':
                doDelete(cmd);
                break;
            default:
                replyError(cmd, "EINVAL", "Unsupported action");
                break;
        }
    }

    /**
     * {@code a=q}: report whether the payload would decode, without storing or displaying it.
     */
    private void doQuery(KittyGraphicsCommand cmd) {
        byte[] data = decodePayload(cmd);
        if (data == null) {
            return;
        }
        Bitmap bitmap = mDecoder.decode(cmd.format, cmd.width, cmd.height, data);
        if (bitmap == null) {
            replyError(cmd, "EINVAL", "failed to decode image");
            return;
        }
        replyOk(cmd);
    }

    private void doStore(KittyGraphicsCommand cmd, boolean place) {
        if (cmd.imageId == 0) {
            replyError(cmd, "EINVAL", "An image id is required");
            return;
        }
        byte[] data = decodePayload(cmd);
        if (data == null) {
            return;
        }
        Bitmap bitmap = mDecoder.decode(cmd.format, cmd.width, cmd.height, data);
        if (bitmap == null) {
            replyError(cmd, "EINVAL", "failed to decode image");
            return;
        }
        if (isBitmapTooLarge(bitmap.getWidth(), bitmap.getHeight())) {
            replyError(cmd, "ENOSPC", "decoded image too large");
            return;
        }
        // Retransmitting an id replaces the previous image and its placements.
        mEmulator.getScreen().deleteTerminalBitmaps(cmd.imageId, 0, 'i', mEmulator.getCursorCol(), mEmulator.getCursorRow());
        mImages.put(cmd.imageId, bitmap);
        if (place && !placeBitmap(cmd, bitmap)) {
            replyError(cmd, "ENOSPC", "cannot place image");
            return;
        }
        replyOk(cmd);
    }

    private void doPlace(KittyGraphicsCommand cmd) {
        if (cmd.imageId == 0) {
            replyError(cmd, "EINVAL", "image id required");
            return;
        }
        Bitmap bitmap = mImages.get(cmd.imageId);
        if (bitmap == null) {
            replyError(cmd, "ENOENT", "image not found");
            return;
        }
        if (!placeBitmap(cmd, bitmap)) {
            replyError(cmd, "ENOSPC", "cannot place image");
            return;
        }
        replyOk(cmd);
    }

    private void doDelete(KittyGraphicsCommand cmd) {
        char spec = cmd.deleteSpec;
        int imageId = cmd.imageId;
        boolean matched;
        if (spec == 'a' || spec == 'A') {
            mEmulator.getScreen().deleteTerminalBitmaps(0, 0, spec, mEmulator.getCursorCol(), mEmulator.getCursorRow());
            mImages.clear();
            matched = true;
        } else {
            boolean hadImage = imageId != 0 && mImages.containsKey(imageId);
            boolean hadPlacement = imageId != 0
                && mEmulator.getScreen().getImagePlacements(imageId).length > 0;
            mEmulator.getScreen().deleteTerminalBitmaps(imageId, cmd.placementId, spec, mEmulator.getCursorCol(), mEmulator.getCursorRow());
            if (spec == 'i' && imageId != 0) {
                mImages.remove(imageId);
            }
            matched = hadImage || hadPlacement;
        }
        if (matched) {
            replyOk(cmd);
        } else {
            replyError(cmd, "ENOENT", "image not found");
        }
    }

    /**
     * Decodes the base64 payload for {@code cmd}, enforcing the declared size limits. On failure a
     * reply is already emitted and {@code null} is returned.
     */
    private byte[] decodePayload(KittyGraphicsCommand cmd) {
        long expected = cmd.expectedPayloadBytes();
        if (expected > TerminalBitmap.MAX_BITMAP_SIZE
                || (cmd.width > 0 && cmd.height > 0
                    && isBitmapTooLarge(cmd.width, cmd.height))) {
            replyError(cmd, "ENOSPC", "declared image too large");
            return null;
        }
        byte[] data = KittyBase64.decode(cmd.payload);
        if (data == null) {
            replyError(cmd, "EINVAL", "invalid base64 payload");
            return null;
        }
        if (expected >= 0 && data.length != expected) {
            replyError(cmd, "EINVAL", "payload length does not match s/v");
            return null;
        }
        return data;
    }

    private boolean isBitmapTooLarge(int width, int height) {
        return (long) width * (long) height * 4L > TerminalBitmap.MAX_BITMAP_SIZE;
    }

    /**
     * Scales {@code bitmap} to the size requested by {@code cmd} and stamps it at the cursor.
     * Applies the kitty cursor policy ({@code C}) on success.
     *
     * @return {@code true} when a placement was registered.
     */
    private boolean placeBitmap(KittyGraphicsCommand cmd, Bitmap bitmap) {
        int[] target = KittyImageSizing.targetPixels(cmd, mEmulator.getCellWidthPixels(), mEmulator.getCellHeightPixels(),
            bitmap.getWidth(), bitmap.getHeight());
        if (target[0] <= 0 || target[1] <= 0 || isBitmapTooLarge(target[0], target[1])) {
            return false;
        }
        Bitmap toPlace = bitmap;
        if (target[0] != bitmap.getWidth() || target[1] != bitmap.getHeight()) {
            try {
                toPlace = Bitmap.createScaledBitmap(bitmap, target[0], target[1], true);
            } catch (OutOfMemoryError e) {
                System.gc();
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        int startRow = mEmulator.getCursorRow();
        int[] placed = mEmulator.getScreen().placeTerminalBitmap(toPlace, cmd.imageId, mEmulator.getCursorCol(), startRow,
            mEmulator.getCellWidthPixels(), mEmulator.getCellHeightPixels(), cmd.offsetX, cmd.offsetY);
        if (placed == null || placed[0] < 0) {
            return false;
        }
        if (cmd.cursorPolicy == 0) {
            int coveredRows = (toPlace.getHeight() + mEmulator.getCellHeightPixels() - 1) / mEmulator.getCellHeightPixels();
            // `placed[1]` is the number of lines the buffer scrolled while stamping; the row below
            // the placement is therefore `coveredRows - scrolledLines` linefeeds from the start.
            int advance = coveredRows - placed[1];
            mEmulator.setCursorCol(0);
            for (int i = 0; i < advance; i++) {
                mEmulator.doLinefeed();
            }
        }
        return true;
    }

    private void replyOk(KittyGraphicsCommand cmd) {
        if (cmd == null || cmd.imageId == 0 || cmd.quiet != 0 || mEmulator.mSession == null) {
            return;
        }
        mEmulator.mSession.write(KittyGraphicsParser.formatReply(cmd.imageId, cmd.placementId, true, null, null));
    }

    private void replyError(KittyGraphicsCommand cmd, String code, String message) {
        // A malformed command that never carried an image id has no-one to answer.
        if (cmd == null || cmd.imageId == 0 || cmd.quiet == 2 || mEmulator.mSession == null) {
            return;
        }
        mEmulator.mSession.write(KittyGraphicsParser.formatReply(cmd.imageId, cmd.placementId, false, code, message));
    }
}
