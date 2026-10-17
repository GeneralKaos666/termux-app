package com.termux.terminal;

/**
 * Grapheme cluster awareness for the terminal cell model.
 *
 * A cell holds a base code point followed by the code points that continue it (combining
 * characters, variation selectors, skin tone modifiers, or the members of an emoji ZWJ sequence).
 * The cluster occupies as many columns as its <em>base</em> code point, which is what makes a ZWJ
 * emoji family, a flag or a skin toned emoji a single terminal cell instead of the naive sum of its
 * code points' widths.
 *
 * A code point is a <em>continuation</em> of the preceding one when:
 * <ul>
 *     <li>the preceding code point is {@link #ZWJ} (and this one is not a blank cell base); or</li>
 *     <li>the preceding code point is a regional indicator and this one is too (a flag); or</li>
 *     <li>this code point is a skin tone modifier; or</li>
 *     <li>this code point is a variation selector; or</li>
 *     <li>this code point has a display width of zero by itself.</li>
 * </ul>
 *
 * {@link TerminalBuffer#getWidthForWrite(int, int, int)} and {@link TerminalRow#setChar} must use
 * {@link #isContinuation(int, int)} for the same input so that the cursor and the stored cells can
 * never disagree about whether a code point advances the cursor.
 */
final class GraphemeClusters {

    /** Zero width joiner, glues emoji together into one cluster. */
    static final int ZWJ = 0x200D;
    /** Variation selector-16 (emoji presentation), keeps a cluster in one cell. */
    static final int VS16 = 0xFE0F;
    /** Variation selector-15 (text presentation). */
    static final int VS15 = 0xFE0E;

    private GraphemeClusters() {
    }

    /** @return whether the code point is a regional indicator (the two halves of a flag). */
    static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    /** @return whether the code point is an emoji skin tone modifier. */
    static boolean isSkinToneModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    /**
     * @return whether the code point is a variation selector. U+FE0E/U+FE0F are already zero width
     * in {@link WcWidth}, but the supplementary variation selectors U+E0100..U+E01EF are not, so
     * they must be recognised here to keep them from taking a column.
     */
    static boolean isVariationSelector(int codePoint) {
        return codePoint == VS15 || codePoint == VS16 || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    /**
     * The display width of the cell a cluster starting with this code point occupies. A lone
     * regional indicator is two columns wide, like the flag it can become.
     */
    static int baseWidth(int codePoint) {
        return isRegionalIndicator(codePoint) ? 2 : WcWidth.width(codePoint);
    }

    /**
     * @return whether {@code codePoint} continues the cluster of {@code precedingCodePoint} instead
     * of starting a new cell.
     * <p>
     * A space is excluded after {@link #ZWJ}: the unused tail of a row is space filled, so a space
     * is the filler that follows the last code point of a cluster and can never continue an emoji
     * sequence. Without the exclusion a ZWJ terminated cluster would absorb the next cell's blank
     * base and corrupt the cluster walk.
     * <p>
     * Known limitation: after a ZWJ, a <em>non</em> space base code point in the same row is still
     * treated as a continuation (the accepted lookback heuristic). In practice a stream such as
     * {@code MAN ZWJ} followed by a separate {@code 'a'} cell renders as one cluster
     * {@code MAN ZWJ a} in a single cell, instead of the joiner being ignored.
     */
    static boolean isContinuation(int precedingCodePoint, int codePoint) {
        if (precedingCodePoint == ZWJ) return codePoint != ' ';
        if (isRegionalIndicator(precedingCodePoint) && isRegionalIndicator(codePoint)) return true;
        if (isSkinToneModifier(codePoint)) return true;
        if (isVariationSelector(codePoint)) return true;
        // Combining characters and the other zero width code points (including ZWJ) modify the
        // preceding cell instead of taking a column of their own.
        return WcWidth.width(codePoint) <= 0;
    }

    /**
     * @return the index just after the cluster starting at {@code index}, which is always greater
     * than {@code index}. The base code point at {@code index} and every continuation code point
     * following it are consumed in one step.
     */
    static int clusterEnd(char[] text, int index, int spaceUsed) {
        int end = index + charCountAt(text, index);
        if (end >= spaceUsed) return end;
        int precedingCodePoint = codePointAt(text, index);
        while (end < spaceUsed) {
            int codePoint = codePointAt(text, end);
            if (!isContinuation(precedingCodePoint, codePoint)) break;
            precedingCodePoint = codePoint;
            end += Character.charCount(codePoint);
        }
        return end;
    }

    /**
     * @return the display width of the cluster starting at {@code index}: the width of its base code
     * point.
     */
    static int clusterWidth(char[] text, int index, int spaceUsed) {
        if (index < 0 || index >= spaceUsed) return 0;
        return baseWidth(codePointAt(text, index));
    }

    private static int charCountAt(char[] text, int index) {
        char c = text[index];
        return Character.isHighSurrogate(c) ? 2 : 1;
    }

    private static int codePointAt(char[] text, int index) {
        char c = text[index];
        return Character.isHighSurrogate(c) ? Character.toCodePoint(c, text[index + 1]) : c;
    }

}
