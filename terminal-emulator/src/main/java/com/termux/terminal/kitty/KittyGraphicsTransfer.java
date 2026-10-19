package com.termux.terminal.kitty;

public final class KittyGraphicsTransfer {
    public static final class Result {
        public final boolean complete;
        public final KittyGraphicsCommand command;
        public final String errorCode;
        public final String errorMessage;

        private Result(boolean complete, KittyGraphicsCommand command, String errorCode, String errorMessage) {
            this.complete = complete;
            this.command = command;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static Result pending() {
            return new Result(false, null, null, null);
        }

        public static Result complete(KittyGraphicsCommand command) {
            return new Result(true, command, null, null);
        }

        public static Result error(String code, String message) {
            return new Result(false, null, code, message);
        }
    }

    private StringBuilder payloadBuilder;
    private KittyGraphicsCommand firstCommand;
    private boolean active;

    public KittyGraphicsTransfer() {
        reset();
    }

    public void reset() {
        payloadBuilder = new StringBuilder();
        firstCommand = null;
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public Result accept(KittyGraphicsCommand cmd) {
        if (cmd == null) {
            return Result.error("EINVAL", "null command");
        }
        if (!cmd.isOk()) {
            reset();
            return Result.error(cmd.errorCode, cmd.errorMessage);
        }
        if (!active) {
            if (cmd.moreChunks == 1) {
                active = true;
                firstCommand = cmd;
                payloadBuilder.setLength(0);
                if (cmd.payload != null) {
                    payloadBuilder.append(cmd.payload);
                }
                return Result.pending();
            }
            reset();
            return Result.complete(cmd);
        }
        if (cmd.moreChunks != 0) {
            reset();
            return Result.error("EINVAL", "only m=0 allowed after start");
        }
        if (!isOnlyMeta(cmd)) {
            reset();
            return Result.error("EINVAL", "chunk may only carry m/q");
        }
        if (cmd.payload != null) {
            payloadBuilder.append(cmd.payload);
        }
        String merged = payloadBuilder.toString();
        long maxChars = expectedPayloadChars(firstCommand.expectedPayloadBytes());
        if (maxChars >= 0 && merged.length() > maxChars) {
            reset();
            return Result.error("EINVAL", "payload too large");
        }
        KittyGraphicsCommand completeCmd = KittyGraphicsParser.cloneWithPayload(firstCommand, merged);
        reset();
        return Result.complete(completeCmd);
    }

    /**
     * Upper bound on the number of base64 characters needed for {@code bytes} of binary payload,
     * or {@code -1} when the expected size is unknown (PNG, missing dimensions).
     */
    private static long expectedPayloadChars(long bytes) {
        if (bytes < 0) {
            return -1;
        }
        return ((bytes + 2) / 3) * 4;
    }

    /**
     * A continuation chunk may only carry {@code m} (end of transfer) and {@code q} (quiet level);
     * every other key would redefine the image being transferred.
     */
    private boolean isOnlyMeta(KittyGraphicsCommand cmd) {
        if (cmd.keys == null || cmd.keys.isEmpty()) {
            return true;
        }
        for (String k : cmd.keys) {
            if (!"m".equals(k) && !"q".equals(k)) {
                return false;
            }
        }
        return true;
    }
}
