package com.termux.terminal;

/**
 * Parses Sixel Device Control String data ({@code DCS q s..s ST} and {@code DCS P1;P2;P3; q s..s
 * ST}), turning sixel commands (color introducers, raster attributes, repeat introducers and data
 * characters) into calls on the owning {@link TerminalBuffer}. Extracted from
 * {@link TerminalEmulator} to keep the emulator focused on escape-state dispatch.
 */
final class SixelParser {

    /** The initial (and floor) capacity of the sixel args buffer, in characters. */
    static final int SIXEL_ARGS__INITIAL_CAPACITY = 256;

    private SixelParser() {
    }

    /**
     * The outcome of {@link #process}: whether the DCS data was valid, and, if a raster-attributes
     * command announced an image size, the new args-buffer capacity to adopt (otherwise -1).
     */
    static final class Result {
        final boolean valid;
        final int newArgsCapacity;

        Result(boolean valid, int newArgsCapacity) {
            this.valid = valid;
            this.newArgsCapacity = newArgsCapacity;
        }
    }

    /**
     * The outcome of {@link #parseSetupArgs}: the three optional setup parameters when the string is
     * a sixel introducer, plus whether the string definitively is not sixel (so the caller can stop
     * checking).
     */
    static final class Setup {
        final int[] args; // non-null iff this is a sixel introducer
        final boolean definitivelyNotSixel;

        Setup(int[] args, boolean definitivelyNotSixel) {
            this.args = args;
            this.definitivelyNotSixel = definitivelyNotSixel;
        }
    }

    /**
     * Parse the optional {@code P1;P2;P3;} parameters of a Sixel DCS introducer ({@code DCS q} or
     * {@code DCS P1;P2;P3; q}). Returns a {@link Setup} whose {@code args} are non-null for the
     * accepted introducers, or null {@code args} with {@code definitivelyNotSixel} set when more than
     * three parameters precede the data (i.e. this is some other DCS command).
     */
    static Setup parseSetupArgs(String dcs, int index) {
        int[] args = {/* P1 */ 0, /* P2 */ 0, /* P3 */ 0};

        if (dcs.charAt(index) == 'q') return new Setup(args, false);

        char ch;
        int arg = 0; boolean incArg = false;
        while (index < dcs.length()) {
            ch = dcs.charAt(index);
            if (ch >= '0' && ch <= '9') {
                if (incArg) { arg++; incArg = false; }
                args[arg] = args[arg] * 10 + ch - '0';
                if (args[arg] < 0) { // Overflow.
                    break;
                }
                index++;
            } else if (ch == ';') {
                index++;
                if (arg == 2) {
                    if (index < dcs.length()) {
                        if (dcs.charAt(index) == 'q') {
                            return new Setup(args, false);
                        } else {
                            // More than three setup args: some other command, so no need to check again.
                            return new Setup(null, true);
                        }
                    }
                    break;
                }
                incArg = true;
            } else if (ch == 'q') {
                // Optional parameters not all passed, or a parameter did not end with `;` but is followed by `q`.
                return new Setup(args, false);
            } else {
                break;
            }
        }

        return new Setup(null, false);
    }

    static Result process(String dcs, TerminalBuffer screen, TerminalSessionClient client,
                          String logTag, int terminalControlArgsMaxLength) {
        int index = 0;

        char ch;
        int repeat = 1;
        int color;
        boolean isValidDcs = true;
        int newArgsCapacity = -1;

        // FIXME: Use `StringUtils.truncateLogStringWithSnippet()` when its added for logging `dcs` input
        //  in errors as it may be truncated from end before index if error length is greater than
        //  `Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD`.
        while (index < dcs.length()) {
            ch = dcs.charAt(index);

            if (
                // Sixel data characters in the range of `?` (0x3F) to `~` (0x7E).
                // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.2.1
                (ch >= '?' && ch <= '~')
                // Graphics Carriage Return `$`.
                // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.4
                || ch == '$'
                // Graphics New Line `-`.
                // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.5
                || ch == '-'
            ) {
                screen.sixelReadData(ch, repeat);
                index++;
                repeat = 1;
            }
            // Color Introducer `#`
            // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.3
            else if (ch == '#') {
                index++; // Consume '#'.

                color = 0;
                while (index < dcs.length()) {
                    ch = dcs.charAt(index);
                    if (ch >= '0' && ch <= '9') {
                        color = color * 10 + ch - '0';
                        if (color < 0) { // Overflow.
                            Logger.logError(client, logTag, "The sixel color command Pc value overflow at index " + index + " of sixel input: " + dcs);
                            isValidDcs = false;
                            break;
                        }
                        index++;
                    } else {
                        break;
                    }
                }

                if (color > 255) {
                    Logger.logError(client, logTag, "The sixel color command Pc value " + color + " is not between 0-255 at index " + index + " of sixel input: " + dcs);
                    isValidDcs = false;
                    break;
                }

                if (!isValidDcs) {
                    break;
                }

                // Basic Colors `# Pc`
                // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.3.1
                if (index == dcs.length() || dcs.charAt(index) != ';') {
                    screen.sixelSetColor(color);
                }
                // HLS or RGB Colors `# Pc; Pu; Px; Py; Pz`
                // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.3.2
                else if (dcs.charAt(index) == ';') {
                    index++; // Consume ';'.

                    int[] args = {0, 0, 0, 0};
                    int arg = 0; boolean incArg = false;
                    while (index < dcs.length()) {
                        ch = dcs.charAt(index);
                        if (ch >= '0' && ch <= '9') {
                            if (incArg) { arg++; incArg = false; }
                            args[arg] = args[arg] * 10 + ch - '0';
                            if (args[arg] < 0) { // Overflow.
                                String argName = "";
                                switch (arg) { case 0: argName = "Pu"; break; case 1: argName = "pX"; break; case 2: argName = "pY"; break; case 3: argName = "pZ"; break; }
                                Logger.logError(client, logTag, "The sixel non-basic color command " + argName + " value overflow at index " + index + " of sixel input: " + dcs);
                                isValidDcs = false;
                                break;
                            }
                        } else if (ch == ';') {
                            if (arg == 3) { // Pz must not end with a ';'.
                                Logger.logError(client, logTag, "The sixel non-basic color command Pz value " + args[3] + " must not end with a semicolon ';' at index " + index + " of sixel input: " + dcs);
                                isValidDcs = false;
                                break;
                            }

                            incArg = true;
                        } else {
                            break;
                        }
                        index++;
                    }

                    if (!isValidDcs) {
                        break;
                    }

                    for (int i = 0; i < args.length; i++) {
                        if (i == 0) { // Pu must equal 1 or 2.
                            if ((args[i] != 1 && args[i] != 2)) {
                                Logger.logError(client, logTag, "The sixel non-basic color command Pu value " + args[i] + " is not 1 or 2 at index " + index + " of sixel input: " + dcs);
                                isValidDcs = false;
                                break;
                            }
                        } else {
                            int limit = 100;
                            if (args[0] == 1 && i == 1) limit = 360;
                            if (args[i] < 0 || args[i] > limit) {
                                String argName = "";
                                switch (i) { case 1: argName = "pX"; break; case 2: argName = "pY"; break; case 3: argName = "pZ"; break; }
                                Logger.logError(client, logTag, "The sixel non-basic color command " + argName + " value " + args[i] + " is not between 0-" + limit + " at index " + index + " of sixel input: " + dcs);
                                isValidDcs = false;
                                break;
                            }
                        }
                    }

                    if (isValidDcs && arg == 3) { // If complete spec is received and is valid.
                        if (args[0] == 2) { // Only RGB is supported.
                            screen.sixelSetRGBColor(color, args[1], args[2], args[3]);
                        } else if (args[0] == 1) { // HLS is not supported.
                            Logger.logError(client, logTag, "The sixel non-basic color command Pu value " + args[0] + " is not supported at index " + index + " of sixel input: " + dcs);
                            screen.sixelIgnore();
                            break;
                        }
                    } else {
                        if (isValidDcs)
                            Logger.logError(client, logTag, "The sixel non-basic color command expected 4 arguments at index " + index + " of sixel input: " + dcs);
                        isValidDcs = false;
                        break;
                    }
                }
            }
            // Graphics Repeat Introducer `! Pn character`.
            // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.1
            else if (ch == '!') {
                index++; // Consume '!'.

                repeat = 0;
                while (index < dcs.length()) {
                    ch = dcs.charAt(index);
                    if (ch >= '0' && ch <= '9') {
                        repeat = repeat * 10 + ch - '0';
                        if (repeat < 0) { // Overflow.
                            Logger.logError(client, logTag, "The sixel repeat command Pn value overflow at index " + index + " of sixel input: " + dcs);
                            isValidDcs = false;
                            break;
                        }
                        index++;
                    } else {
                        break;
                    }
                }

                if (repeat > TerminalSixel.SIXEL__MAX_REPEAT) {
                    Logger.logError(client, logTag, "The sixel repeat command Pn value " + repeat + " is greater than max repeat value " +
                        TerminalSixel.SIXEL__MAX_REPEAT + " at index " + index + " of sixel input: " + dcs);
                    screen.sixelIgnore();
                    break;
                }

                if (!isValidDcs) {
                    break;
                }
            }
            // Raster Attributes `" Pan; Pad; Ph; Pv`
            // - https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.2
            else if (ch == '"') {
                index++; // Consume '"'.

                int[] args = {0, 0, 0, 0};
                int arg = 0; boolean incArg = false;
                while (index < dcs.length()) {
                    ch = dcs.charAt(index);
                    if (ch >= '0' && ch <= '9') {
                        if (incArg) { arg++; incArg = false; }
                        args[arg] = args[arg] * 10 + ch - '0';
                        if (args[arg] < 0) { // Overflow.
                            String argName = "";
                            switch (arg) { case 0: argName = "Pan"; break; case 1: argName = "Pad"; break; case 2: argName = "pH"; break; case 3: argName = "pV"; break; }
                            Logger.logError(client, logTag, "The sixel raster command " + argName + " value overflow at index " + index + " of sixel input: " + dcs);
                            isValidDcs = false;
                            break;
                        }
                    } else if (ch == ';') {
                        if (arg == 3) { // Pv must not end with a ';'.
                            Logger.logError(client, logTag, "The sixel raster command Pv value " + args[3] + " must not end with a semicolon ';' at index " + index + " of sixel input: " + dcs);
                            isValidDcs = false;
                            break;
                        }
                        incArg = true;
                    } else {
                        break;
                    }
                    index++;
                }

                if (isValidDcs && arg == 3) { // If complete spec is received and is valid.
                    // Raster pixel aspect ratio is not supported currently.
                    // Raster "rough" horizontal and vertical size of image may be sent at start of
                    // sixel data string, like done by `img2sixel`, so increase sixel commands args
                    // buffer capacity (`mTerminalControlArgs`) and resize sixel bitmap in
                    // `TerminalSixel` at start, instead of having to keep resizing buffer/bitmap
                    // as more sixel data is received, which has a performance hit due to
                    // memory reallocations and copying.
                    int sixelWidth = args[2]; // `Ph`
                    int sixelHeight = args[3]; // `Pv`
                    if (sixelWidth > 0 && sixelHeight > 0) {
                        // 2% extra for sixel commands/parameters in addition to image data.
                        int sixelArgsExpectedLength = (int) (sixelWidth * sixelHeight * 1.02);
                        // If sixel commands are too long, they are divided into parts, and if a
                        // new command starts near `mTerminalControlArgsMaxLength / 2`, it could
                        // contain image data for 1 pixel line of image width, so add that.
                        int sixelArgsPartsExpectedLength = (int) ((((double) terminalControlArgsMaxLength / 2) + sixelWidth) * 1.02);
                        int sixelArgsExpectedCapacity = Math.min(sixelArgsPartsExpectedLength, sixelArgsExpectedLength);
                        if (sixelArgsExpectedCapacity > SIXEL_ARGS__INITIAL_CAPACITY) {
                            newArgsCapacity = sixelArgsExpectedCapacity;
                        }

                        screen.sixelResize(sixelWidth, sixelHeight);
                    }
                } else {
                    if (isValidDcs)
                        Logger.logError(client, logTag, "The sixel raster command expected 4 arguments at index " + index + " of sixel input: " + dcs);
                    isValidDcs = false;
                    break;
                }
            }
            else if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\f' || ch == '\r') {
                index++;
            } else {
                // Invalid character.
                Logger.logError(client, logTag, "Invalid character '" + ch + "' (" + (byte) ch + ") at index " + index + " of sixel input: " + dcs);
                isValidDcs = false;
                break;
            }
        }

        return new Result(isValidDcs, newArgsCapacity);
    }
}
