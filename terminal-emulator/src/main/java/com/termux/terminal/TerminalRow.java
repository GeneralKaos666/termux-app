package com.termux.terminal;

import java.util.Arrays;

/**
 * A row in a terminal, composed of a fixed number of cells.
 * <p>
 * The text in the row is stored in a char[] array, {@link #mText}, for quick access during rendering.
 */
public final class TerminalRow {

    private static final float SPARE_CAPACITY_FACTOR = 1.5f;

    /**
     * Max combining characters that can exist in a column, that are separate from the base character
     * itself. Any additional combining characters will be ignored and not added to the column.
     *
     * There does not seem to be limit in unicode standard for max number of combination characters
     * that can be combined but such characters are primarily under 10.
     *
     * "Section 3.6 Combination" of unicode standard contains combining characters info.
     * - https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf
     * - https://en.wikipedia.org/wiki/Combining_character#Unicode_ranges
     * - https://stackoverflow.com/questions/71237212/what-is-the-maximum-number-of-unicode-combined-characters-that-may-be-needed-to
     *
     * UAX15-D3 Stream-Safe Text Format limits to max 30 combining characters.
     * > The value of 30 is chosen to be significantly beyond what is required for any linguistic or technical usage.
     * > While it would have been feasible to chose a smaller number, this value provides a very wide margin,
     * > yet is well within the buffer size limits of practical implementations.
     * - https://unicode.org/reports/tr15/#Stream_Safe_Text_Format
     * - https://stackoverflow.com/a/11983435/14686958
     *
     * We choose the value 15 because it should be enough for terminal based applications and keep
     * the memory usage low for a terminal row, won't affect performance or cause terminal to
     * lag or hang, and will keep malicious applications from causing harm. The value can be
     * increased if ever needed for legitimate applications.
     */
    private static final int MAX_COMBINING_CHARACTERS_PER_COLUMN = 15;

    /** The number of columns in this terminal row. */
    private final int mColumns;
    /** The text filling this terminal row. */
    public char[] mText;
    /** The number of java chars used in {@link #mText}. */
    private int mSpaceUsed;
    /** If this row has been line wrapped due to text output at the end of line. */
    boolean mLineWrap;
    /** The style bits of each cell in the row. See {@link TextStyle}. */
    final long[] mStyle;
    /**
     * The hyperlink id of each cell in the row, or {@code null} until the first non-zero id is
     * stored. A {@code 0} means "no hyperlink". See {@link TerminalBuffer#registerLink(String)}.
     */
    int[] mLinkIds;
    /** If this row might contain chars with width != 1, used for deactivating fast path */
    boolean mHasNonOneWidthOrSurrogateChars;
    /** If this row has a {@link TerminalBitmap}. Used for performance only. */
    public boolean mHasTerminalBitmap;
    /**
     * If the contents of this row have changed since the last time {@link #clearDirty()} was
     * called. Used by renderers to repaint only the rows that changed. A fresh row is not dirty.
     */
    boolean mDirty;

    /** Construct a blank row (containing only whitespace, ' ') with a specified style. */
    public TerminalRow(int columns, long style) {
        mColumns = columns;
        mText = new char[(int) (SPARE_CAPACITY_FACTOR * columns)];
        mStyle = new long[columns];
        clear(style);
    }

    /** NOTE: The sourceX2 is exclusive. */
    public void copyInterval(TerminalRow line, int sourceX1, int sourceX2, int destinationX) {
        mHasNonOneWidthOrSurrogateChars |= line.mHasNonOneWidthOrSurrogateChars;
        final int x1 = line.findStartOfColumn(sourceX1);
        final int x2 = line.findStartOfColumn(sourceX2);
        boolean startingFromSecondHalfOfWideChar = (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1));
        final char[] sourceChars = (this == line) ? Arrays.copyOf(line.mText, line.mText.length) : line.mText;
        // Copy whole grapheme clusters, so that a cluster's width is counted once instead of once per
        // code point. Every code point of a cluster is written at the cluster's destination column;
        // setChar() appends the continuations to the cell it created for the base.
        int destinationColumn = destinationX;
        int sourceColumn = sourceX1;
        for (int i = x1; i < x2; ) {
            final int clusterEnd = GraphemeClusters.clusterEnd(sourceChars, i, line.mSpaceUsed);
            final long style = line.getStyle(sourceColumn);
            final int linkId = line.getLinkId(sourceColumn);
            int clusterWidth = GraphemeClusters.clusterWidth(sourceChars, i, line.mSpaceUsed);
            if (startingFromSecondHalfOfWideChar) {
                // Just treat copying second half of wide char as copying whitespace.
                setChar(destinationColumn, ' ', style, linkId);
                startingFromSecondHalfOfWideChar = false;
                clusterWidth = 1;
            } else {
                for (int j = i; j < clusterEnd; ) {
                    final int codePoint = codePointAt(sourceChars, j);
                    setChar(destinationColumn, codePoint, style, linkId);
                    j += Character.charCount(codePoint);
                }
            }
            destinationColumn += clusterWidth;
            sourceColumn += clusterWidth;
            i = clusterEnd;
        }
    }

    public int getSpaceUsed() {
        return mSpaceUsed;
    }

    /** Note that the column may end of second half of wide character. */
    public int findStartOfColumn(int column) {
        if (column == mColumns) return getSpaceUsed();

        int currentColumn = 0;
        int currentCharIndex = 0;
        while (currentCharIndex < mSpaceUsed) {
            final int clusterEnd = GraphemeClusters.clusterEnd(mText, currentCharIndex, mSpaceUsed);
            final int clusterWidth = GraphemeClusters.clusterWidth(mText, currentCharIndex, mSpaceUsed);
            if (clusterWidth > 0) {
                currentColumn += clusterWidth;
                if (currentColumn > column) return currentCharIndex;
            }
            currentCharIndex = clusterEnd;
            if (currentColumn == column) return currentCharIndex;
        }
        return currentCharIndex;
    }

    /**
     * @return whether a cluster that occupies two columns starts exactly at {@code column}. The
     * second half of a wide cell therefore returns {@code false}.
     */
    private boolean wideDisplayCharacterStartingAt(int column) {
        int currentColumn = 0;
        int currentCharIndex = 0;
        while (currentCharIndex < mSpaceUsed) {
            final int clusterEnd = GraphemeClusters.clusterEnd(mText, currentCharIndex, mSpaceUsed);
            final int clusterWidth = GraphemeClusters.clusterWidth(mText, currentCharIndex, mSpaceUsed);
            if (clusterWidth > 0) {
                if (currentColumn == column) return clusterWidth == 2;
                currentColumn += clusterWidth;
                if (currentColumn > column) return false;
            }
            currentCharIndex = clusterEnd;
        }
        return false;
    }

    /**
     * @param column a column in this row.
     * @return the last code point of the grapheme cluster occupying {@code column}, or {@code -1}
     * when the column is out of range or holds a lone blank cell. A continuation code point is
     * appended to the cluster it continues, so this is what a following code point must be tested
     * against to decide whether it continues the same cell.
     */
    public int getLastCodePointOfColumn(int column) {
        if (column < 0 || column >= mColumns) return -1;
        final int clusterStart = findStartOfColumn(column);
        if (clusterStart >= mSpaceUsed) return -1;
        final int clusterEnd = GraphemeClusters.clusterEnd(mText, clusterStart, mSpaceUsed);
        int lastCodePoint = -1;
        for (int i = clusterStart; i < clusterEnd; ) {
            lastCodePoint = codePointAt(mText, i);
            i += Character.charCount(lastCodePoint);
        }
        // A lone blank is not a cluster that can be continued by a following code point.
        return (lastCodePoint == ' ') ? -1 : lastCodePoint;
    }

    private static int codePointAt(char[] text, int index) {
        final char c = text[index];
        return Character.isHighSurrogate(c) ? Character.toCodePoint(c, text[index + 1]) : c;
    }

    /** @return the number of code points in {@code text} between {@code start} and {@code end}. */
    private static int codePointCount(char[] text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; ) {
            i += Character.isHighSurrogate(text[i]) ? 2 : 1;
            count++;
        }
        return count;
    }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
        clearLinkIds();
        mSpaceUsed = mColumns;
        mHasNonOneWidthOrSurrogateChars = false;
        mHasTerminalBitmap = false;
    }

    // https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
    public void setChar(int columnToSet, int codePoint, long style) {
        setChar(columnToSet, codePoint, style, 0);
    }

    // https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
    public void setChar(int columnToSet, int codePoint, long style, int linkId) {
        if (columnToSet  < 0 || columnToSet >= mStyle.length)
            throw new IllegalArgumentException("TerminalRow.setChar(): columnToSet=" + columnToSet + ", codePoint=" + codePoint + ", style=" + style);

        mDirty = true;
        mStyle[columnToSet] = style;

        if (!mHasTerminalBitmap && TextStyle.isTerminalBitmap(style)) {
            mHasTerminalBitmap = true;
        }

        final int baseWidth = GraphemeClusters.baseWidth(codePoint);
        // A code point continues the cell it is written into when it is a known grapheme
        // continuation (a ZWJ join, a flag half, a skin tone modifier or a variation selector) or
        // has zero width. The same test is used by TerminalBuffer.getWidthForWrite() for the
        // cursor, so the cells and the cursor cannot disagree about whether a code point advances.
        final int precedingCodePoint = getLastCodePointOfColumn(columnToSet);
        final boolean newIsContinuation = precedingCodePoint >= 0 && GraphemeClusters.isContinuation(precedingCodePoint, codePoint);
        final boolean newIsCombining = baseWidth <= 0 || newIsContinuation;
        // Only a genuine write stamps the link. A continuation is appended to an existing cell
        // and must keep the link that cell was created with.
        if (!newIsCombining && baseWidth > 0) setLinkId(columnToSet, linkId);

        // Fast path when we don't have any chars with width != 1
        if (!mHasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || baseWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true;
            } else {
                mText[columnToSet] = (char) codePoint;
                return;
            }
        }

        boolean wasExtraColForWideChar = (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1);

        if (newIsCombining) {
            // A continuation is appended to the cluster it continues, which starts at the previous
            // column when the cursor stands on the second half of a wide cell.
            if (wasExtraColForWideChar) columnToSet--;
        } else {
            // Check if we are overwriting the second half of a wide character starting at the previous column:
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' ', style, 0);
            // Check if we are overwriting the first half of a wide character starting at the next column:
            boolean overwritingWideCharInNextColumn = baseWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1);
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' ', style, 0);
        }

        char[] text = mText;
        final int oldStartOfColumnIndex = findStartOfColumn(columnToSet);
        final int oldCodePointDisplayWidth = GraphemeClusters.clusterWidth(text, oldStartOfColumnIndex, mSpaceUsed);

        // Get the number of elements in the mText array this column uses now
        int oldCharactersUsedForColumn;
        if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            int oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth);
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex;
        } else {
            // Last character.
            oldCharactersUsedForColumn = mSpaceUsed - oldStartOfColumnIndex;
        }

        // If MAX_COMBINING_CHARACTERS_PER_COLUMN continuations already exist in the column, then
        // ignore adding additional ones. Both zero width characters and the non-zero width
        // continuations (flag halves, skin tone modifiers, ZWJ joined emoji) count, so a malicious
        // stream cannot grow one cell without bound.
        if (newIsCombining) {
            int continuationCodePointsCount = codePointCount(mText, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn) - 1;
            if (continuationCodePointsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return;
        }

        // Find how many chars this column will need
        int newCharactersUsedForColumn = Character.charCount(codePoint);
        if (newIsCombining) {
            // Continuations are added to the contents of the column instead of overwriting them, so
            // that they modify the existing contents.
            // FIXME: Unassigned characters also get width=0.
            newCharactersUsedForColumn += oldCharactersUsedForColumn;
        }

        int oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn;
        int newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn;

        final int javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn;
        if (javaCharDifference > 0) {
            // Shift the rest of the line right.
            int oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex;
            if (mSpaceUsed + javaCharDifference > text.length) {
                // We need to grow the array
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, oldNextColumnIndex);
                System.arraycopy(text, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn);
                mText = text = newText;
            } else {
                System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, oldCharactersAfterColumn);
            }
        } else if (javaCharDifference < 0) {
            // Shift the rest of the line left.
            System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - oldNextColumnIndex);
        }
        mSpaceUsed += javaCharDifference;

        // Store char. A continuation is stored at the end of the existing contents so that it modifies them:
        //noinspection ResultOfMethodCallIgnored - since we already now how many java chars is used.
        Character.toChars(codePoint, text, oldStartOfColumnIndex + (newIsCombining ? oldCharactersUsedForColumn : 0));

        if (!newIsCombining && oldCodePointDisplayWidth == 2 && baseWidth == 1) {
            // Replace second half of wide char with a space. Which mean that we actually add a ' ' java character.
            if (mSpaceUsed + 1 > text.length) {
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, newNextColumnIndex);
                System.arraycopy(text, newNextColumnIndex, newText, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
                mText = text = newText;
            } else {
                System.arraycopy(text, newNextColumnIndex, text, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
            }
            text[newNextColumnIndex] = ' ';

            ++mSpaceUsed;
        } else if (!newIsCombining && oldCodePointDisplayWidth == 1 && baseWidth == 2) {
            if (columnToSet == mColumns - 1) {
                throw new IllegalArgumentException("Cannot put wide character in last column");
            } else if (columnToSet == mColumns - 2) {
                // Truncate the line to the second part of this wide char:
                mSpaceUsed = newNextColumnIndex;
            } else {
                // Overwrite the contents of the next column, which mean we actually remove java characters. Due to the
                // check at the beginning of this method we know that we are not overwriting a wide char.
                int newNextNextColumnIndex = newNextColumnIndex + (Character.isHighSurrogate(mText[newNextColumnIndex]) ? 2 : 1);
                int nextLen = newNextNextColumnIndex - newNextColumnIndex;

                // Shift the array leftwards.
                System.arraycopy(text, newNextNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - newNextNextColumnIndex);
                mSpaceUsed -= nextLen;
            }
        }
    }

    boolean isBlank() {
        for (int charIndex = 0, charLen = getSpaceUsed(); charIndex < charLen; charIndex++)
            if (mText[charIndex] != ' ') return false;
        return true;
    }

    public final long getStyle(int column) {
        return mStyle[column];
    }

    /** @return the hyperlink id of the cell, or {@code 0} if it has no link. */
    public int getLinkId(int column) {
        return (mLinkIds == null) ? 0 : mLinkIds[column];
    }

    /**
     * Sets the hyperlink id of a cell. A {@code 0} on a row that has never stored a link is a no-op,
     * so the {@link #mLinkIds} array stays {@code null} until a non-zero id is stored.
     */
    void setLinkId(int column, int linkId) {
        if (column < 0 || column >= mStyle.length)
            throw new IllegalArgumentException("TerminalRow.setLinkId(): column=" + column + ", linkId=" + linkId);
        if (mLinkIds == null) {
            if (linkId == 0) return;
            mLinkIds = new int[mColumns];
        }
        mLinkIds[column] = linkId;
    }

    /** Clears every hyperlink id in the row, keeping the backing array if it was allocated. */
    void clearLinkIds() {
        if (mLinkIds != null) Arrays.fill(mLinkIds, 0);
    }

    /** @return the {@link TextStyle#UNDERLINE_STYLE_NONE} or other underline style of the cell. */
    public int getUnderlineStyle(int column) {
        return TextStyle.decodeUnderlineStyle(mStyle[column]);
    }

    /** @return whether the contents of this row have changed since the last {@link #clearDirty()}. */
    public boolean isDirty() {
        return mDirty;
    }

    /** Marks this row as changed so that a renderer will repaint it. */
    public void markDirty() {
        mDirty = true;
    }

    /** Clears the dirty flag of this row, without modifying its contents. */
    public void clearDirty() {
        mDirty = false;
    }

}
