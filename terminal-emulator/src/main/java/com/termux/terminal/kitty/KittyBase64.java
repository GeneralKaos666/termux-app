package com.termux.terminal.kitty;

public final class KittyBase64 {
    private static final char[] ALPHABET = new char[] {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
    };

    private static final byte[] DECODE_TABLE = new byte[128];

    static {
        for (int i = 0; i < DECODE_TABLE.length; i++) {
            DECODE_TABLE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE_TABLE[(int) ALPHABET[i]] = (byte) i;
        }
        DECODE_TABLE['='] = -2;
    }

    private KittyBase64() {
    }

    public static byte[] decode(String payload) {
        if (payload == null) {
            return null;
        }
        int len = payload.length();
        if (len == 0) {
            return new byte[0];
        }
        if (len % 4 != 0) {
            return null;
        }

        int padding = 0;
        for (int i = len - 1; i >= len - 2 && i >= 0; i--) {
            if (payload.charAt(i) == '=') {
                padding++;
            } else {
                break;
            }
        }
        if (padding > 2) {
            return null;
        }

        int outputLen = (len / 4) * 3 - padding;
        byte[] output = new byte[outputLen];
        int outIndex = 0;
        for (int i = 0; i < len; i += 4) {
            int block = 0;
            for (int j = 0; j < 4; j++) {
                char c = payload.charAt(i + j);
                if (c > 127) {
                    return null;
                }
                byte v = DECODE_TABLE[c];
                if (v == -1) {
                    return null;
                }
                if (v == -2) {
                    if (j == 0 || j == 1) {
                        return null;
                    }
                }
                if (v >= 0) {
                    block = (block << 6) | (v & 0x3F);
                } else {
                    block <<= 6;
                }
            }
            int toWrite = 3;
            if (payload.charAt(i + 2) == '=') {
                toWrite = 1;
            } else if (payload.charAt(i + 3) == '=') {
                toWrite = 2;
            }
            if (toWrite > 0) {
                output[outIndex++] = (byte) ((block >> 16) & 0xFF);
            }
            if (toWrite > 1) {
                output[outIndex++] = (byte) ((block >> 8) & 0xFF);
            }
            if (toWrite > 2) {
                output[outIndex++] = (byte) (block & 0xFF);
            }
        }
        if (outIndex != output.length) {
            return null;
        }
        return output;
    }
}
