package com.termux.terminal.kitty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class KittyGraphicsCommand {
    public final char action; // t,T,p,q,d
    public final int quiet; // 0,1,2
    public final int format; // 24,32,100
    public final char transmission; // d (only direct in subset)
    public final int width;
    public final int height;
    public final int imageId;
    public final int placementId;
    public final int offsetX;
    public final int offsetY;
    public final int columns;
    public final int rows;
    public final int sourceX;
    public final int sourceY;
    public final int sourceWidth;
    public final int sourceHeight;
    public final int cursorPolicy; // 0,1
    public final int moreChunks; // 0,1
    public final char deleteSpec; // a,A,i,c,C
    public final String payload; // base64 chunk payload
    public final String errorCode; // null if ok
    public final String errorMessage; // null if ok

    private KittyGraphicsCommand(char action, int quiet, int format, char transmission,
                                 int width, int height, int imageId, int placementId,
                                 int offsetX, int offsetY, int columns, int rows,
                                 int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                 int cursorPolicy, int moreChunks, char deleteSpec,
                                 String payload, String errorCode, String errorMessage) {
        this.action = action;
        this.quiet = quiet;
        this.format = format;
        this.transmission = transmission;
        this.width = width;
        this.height = height;
        this.imageId = imageId;
        this.placementId = placementId;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.columns = columns;
        this.rows = rows;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.cursorPolicy = cursorPolicy;
        this.moreChunks = moreChunks;
        this.deleteSpec = deleteSpec;
        this.payload = payload;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    static KittyGraphicsCommand error(String code, String message) {
        return new KittyGraphicsCommand((char) 0, 0, 0, 'd', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (char) 0, null, code, message);
    }

    static KittyGraphicsCommand ok(Map<String, String> keys, String payload) {
        return new KittyGraphicsCommand(
                getChar(keys, "a", 't'),
                getInt(keys, "q", 0),
                getInt(keys, "f", 32),
                getChar(keys, "t", 'd'),
                getInt(keys, "s", 0),
                getInt(keys, "v", 0),
                getInt(keys, "i", 0),
                getInt(keys, "p", 0),
                getInt(keys, "X", 0),
                getInt(keys, "Y", 0),
                getInt(keys, "c", 0),
                getInt(keys, "r", 0),
                getInt(keys, "x", 0),
                getInt(keys, "y", 0),
                getInt(keys, "w", 0),
                getInt(keys, "h", 0),
                getInt(keys, "C", 0),
                getInt(keys, "m", 0),
                getChar(keys, "d", 'a'),
                payload,
                null,
                null);
    }

    public boolean isOk() {
        return errorCode == null;
    }

    public long expectedPayloadBytes() {
        if (!isOk()) {
            return -1L;
        }
        int w = width;
        int h = height;
        if (w <= 0 || h <= 0) {
            return -1L;
        }
        if (format == 24) {
            return (long) w * (long) h * 3L;
        } else if (format == 32) {
            return (long) w * (long) h * 4L;
        } else if (format == 100) {
            return -1L;
        } else {
            return -1L;
        }
    }

    private static int getInt(Map<String, String> keys, String key, int def) {
        String v = keys.get(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static char getChar(Map<String, String> keys, String key, char def) {
        String v = keys.get(key);
        if (v == null || v.isEmpty()) return def;
        return v.charAt(0);
    }
}
