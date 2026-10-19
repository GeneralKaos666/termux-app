package com.termux.terminal.kitty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class KittyGraphicsParser {
    private static final String OK = "OK";

    private KittyGraphicsParser() {
    }

    public static KittyGraphicsCommand parse(String buffer, String payload) {
        if (buffer == null || buffer.isEmpty()) {
            return KittyGraphicsCommand.error("EINVAL", "Empty command");
        }
        if (!buffer.startsWith("G")) {
            return KittyGraphicsCommand.error("EINVAL", "Expected G prefix");
        }
        String afterG = buffer.substring(1);
        String control = "";
        String dataPayload = payload;
        int semi = afterG.indexOf(';');
        if (semi >= 0) {
            control = afterG.substring(0, semi);
            String p = afterG.substring(semi + 1);
            if (dataPayload == null || dataPayload.isEmpty()) {
                dataPayload = p;
            } else {
                dataPayload = dataPayload + p;
            }
        } else {
            control = afterG;
        }
        Map<String, String> keys = parseKeys(control);
        // Check for unsupported keys in subset: I is rejected; t != d rejected; animation/other ignored explicitly by subset
        if (keys.containsKey("I")) {
            return KittyGraphicsCommand.error("EINVAL", "Image number (I) not supported in subset");
        }
        if (keys.containsKey("t")) {
            String t = keys.get("t");
            if (t == null || t.isEmpty() || t.charAt(0) != 'd') {
                return KittyGraphicsCommand.error("EINVAL", "Only direct transmission (t=d) supported");
            }
        }
        if (keys.containsKey("i") && keys.containsKey("I")) {
            return KittyGraphicsCommand.error("EINVAL", "Cannot specify both I and i");
        }
        Map<String, String> effKeys = keys;
        if (!effKeys.containsKey("a")) {
            String first = control.isEmpty() ? "" : control.substring(0, 1);
            if ("t".equals(first) || "T".equals(first) || "p".equals(first)
                    || "q".equals(first) || "d".equals(first)) {
                effKeys = new HashMap<String, String>(keys);
                effKeys.put("a", first);
            }
        }
        KittyGraphicsCommand cmd = KittyGraphicsCommand.ok(effKeys, dataPayload);
        if (!cmd.isOk()) {
            return cmd;
        }
        // Validate action
        char action = cmd.action;
        if (action != 't' && action != 'T' && action != 'p' && action != 'q' && action != 'd') {
            return KittyGraphicsCommand.error("EINVAL", "Unsupported action");
        }
        // Validate delete spec subset
        if (action == 'd') {
            char ds = cmd.deleteSpec;
            if (ds != 'a' && ds != 'A' && ds != 'i' && ds != 'c' && ds != 'C') {
                return KittyGraphicsCommand.error("EINVAL", "Unsupported delete specifier");
            }
        }
        if (action == 'q') {
            if (cmd.imageId == 0) {
                return KittyGraphicsCommand.error("EINVAL", "Query requires image id");
            }
        }
        if (cmd.moreChunks < 0 || cmd.moreChunks > 1) {
            return KittyGraphicsCommand.error("EINVAL", "Invalid chunk flag");
        }
        if (cmd.quiet < 0 || cmd.quiet > 2) {
            return KittyGraphicsCommand.error("EINVAL", "Invalid quiet level");
        }
        return cmd;
    }

    /**
     * Rebuilds a command from an earlier chunk with the full accumulated payload. Used by
     * {@link KittyGraphicsTransfer} once the terminating chunk has arrived.
     */
    public static KittyGraphicsCommand cloneWithPayload(KittyGraphicsCommand src, String payload) {
        if (src == null) {
            return null;
        }
        return new KittyGraphicsCommand(
                src.action,
                src.quiet,
                src.format,
                src.transmission,
                src.width,
                src.height,
                src.imageId,
                src.placementId,
                src.offsetX,
                src.offsetY,
                src.columns,
                src.rows,
                src.sourceX,
                src.sourceY,
                src.sourceWidth,
                src.sourceHeight,
                src.cursorPolicy,
                0,
                src.deleteSpec,
                payload,
                null,
                null,
                src.keys);
    }

    private static Map<String, String> parseKeys(String control) {
        if (control == null || control.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> keys = new HashMap<String, String>(8);
        int start = 0;
        for (int i = 0; i < control.length(); i++) {
            char c = control.charAt(i);
            if (c == ',') {
                parseKeyValue(control, start, i, keys);
                start = i + 1;
            }
        }
        parseKeyValue(control, start, control.length(), keys);
        return keys;
    }

    private static void parseKeyValue(String control, int start, int end, Map<String, String> keys) {
        if (start >= end) return;
        int eq = control.indexOf('=', start);
        if (eq < 0 || eq >= end) {
            return;
        }
        String key = control.substring(start, eq).trim();
        String value = control.substring(eq + 1, end).trim();
        if (!key.isEmpty()) {
            keys.put(key, value);
        }
    }

    public static String formatReply(int imageId, int placementId, boolean ok, String code, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("\033_G");
        if (imageId != 0) {
            sb.append("i=").append(imageId);
        }
        if (placementId != 0) {
            sb.append(",p=").append(placementId);
        }
        sb.append(";");
        if (ok) {
            sb.append(OK);
        } else {
            String c = code == null ? "EINVAL" : code;
            String m = message == null ? "error" : message;
            sb.append(c).append(":").append(m);
        }
        sb.append("\033\\");
        return sb.toString();
    }
}
