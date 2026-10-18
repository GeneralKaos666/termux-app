package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /** Reused by {@link #drawUnderline} so a decorated underline does not allocate a path per run. */
    private final Path mUnderlinePath = new Path();
    /** Dash patterns for the dotted/dashed underline styles; the intervals derive from the font size. */
    private final DashPathEffect mDottedUnderlineEffect;
    private final DashPathEffect mDashedUnderlineEffect;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        // Dash intervals are fixed once the font size is known, so the effects can be shared by every run.
        float underlineStroke = Math.max(1f, mFontLineSpacing / 14f);
        mDottedUnderlineEffect = new DashPathEffect(new float[]{underlineStroke, underlineStroke * 1.5f}, 0);
        mDashedUnderlineEffect = new DashPathEffect(new float[]{underlineStroke * 3f, underlineStroke * 2f}, 0);

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, topRow, topRow + mEmulator.mRows);
    }

    /**
     * Render the terminal to a canvas at a specified row scroll and an optional rectangular selection, but only the
     * rows in the range {@code [firstScreenRow, lastScreenRowExclusive)}.
     * <p/>
     * The row range is expressed in external (screen) row numbers and must already be clamped inside the visible band
     * {@code [topRow, topRow + mEmulator.mRows)} by the caller, so that {@code firstScreenRow >= topRow} holds.
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             int firstScreenRow, int lastScreenRowExclusive) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        // The offset is advanced by one line spacing per row, so rows skipped at the start of the range must be
        // accounted for here to keep every row at its true pixel offset.
        float heightOffset = mFontLineSpacingAndAscent + (firstScreenRow - topRow) * mFontLineSpacing;
        for (int row = firstScreenRow; row < lastScreenRowExclusive; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    Bitmap bitmap = mEmulator.getScreen().getSixelBitmap(style);
                    if (bitmap != null) {
                        float left = column * mFontWidth;
                        float top = heightOffset - mFontLineSpacing;
                        Rect bitmapSrcRect = mEmulator.getScreen().getSixelRect(style);
                        RectF bitmapDestRect = new RectF(left, top, left + mFontWidth, top + mFontLineSpacing);
                        canvas.drawBitmap(bitmap, bitmapSrcRect, bitmapDestRect, null);
                    }
                    column += 1;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunStartColumn = column + 1;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    currentCharIndex += charsForCodePoint;
                    continue;
                }
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final int underlineStyle = TextStyle.decodeUnderlineStyle(textStyle);
        // The built-in paint underline is a plain solid line: use it for "single", and for callers that set
        // the underline bit without a style (WS1 keeps those as UNDERLINE_STYLE_NONE). The decorated styles
        // are stroked by drawUnderline() below, after the glyphs.
        final boolean paintUnderline = underline && (underlineStyle == TextStyle.UNDERLINE_STYLE_NONE
            || underlineStyle == TextStyle.UNDERLINE_STYLE_SINGLE
            || underlineStyle < TextStyle.UNDERLINE_STYLE_NONE
            || underlineStyle > TextStyle.UNDERLINE_STYLE_DASHED);
        final boolean customUnderline = underline && !paintUnderline;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }
        // Kept before the cursor block below can shorten "right" (bar cursor): an underline spans the run.
        final float runRight = right;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(paintUnderline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }

            if (customUnderline) {
                drawUnderline(canvas, left, runRight, y, foreColor, underlineStyle);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    /**
     * Strokes a decorated underline (double, curly, dotted or dashed) for one text run. The canvas already
     * carries the run's horizontal scale, so {@code left}/{@code right} are run coordinates.
     *
     * <p>Leaves {@link #mTextPaint} in the state the rest of the renderer assumes: the next run paints
     * backgrounds and cursor cells as filled rects with the same paint, so the fill style must come back.
     */
    private void drawUnderline(Canvas canvas, float left, float right, float y, int color, int underlineStyle) {
        float strokeWidth = Math.max(1f, mFontLineSpacing / 14f);
        float baseline = y - mFontLineSpacingAndAscent;
        // Roughly where the font draws its own (single) underline: a tenth of an ascent below the baseline.
        // Clamped to the cell, because the next row is drawn later and would paint its background over any
        // decoration that spilled past the cell's bottom edge.
        float decorationHeight = underlineStyle == TextStyle.UNDERLINE_STYLE_DOUBLE
            ? strokeWidth * 2f : strokeWidth * 1.5f;
        float underlineY = Math.min(baseline + mFontAscent * -0.12f, y - decorationHeight - 0.5f);

        mTextPaint.setUnderlineText(false);
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setTextSkewX(0.f);
        mTextPaint.setStrikeThruText(false);
        mTextPaint.setColor(color);
        mTextPaint.setStyle(Paint.Style.STROKE);
        mTextPaint.setStrokeWidth(strokeWidth);
        mTextPaint.setPathEffect(null);

        switch (underlineStyle) {
            case TextStyle.UNDERLINE_STYLE_DOUBLE:
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint);
                canvas.drawLine(left, underlineY + strokeWidth * 2f, right, underlineY + strokeWidth * 2f, mTextPaint);
                break;
            case TextStyle.UNDERLINE_STYLE_CURLY: {
                mUnderlinePath.reset();
                mUnderlinePath.moveTo(left, underlineY);
                float step = strokeWidth * 3f;
                boolean up = true;
                for (float x = left; x < right; x += step) {
                    float endX = Math.min(x + step, right);
                    mUnderlinePath.quadTo(Math.min(x + step / 2f, right),
                        underlineY + (up ? -step : step), endX, underlineY);
                    up = !up;
                }
                canvas.drawPath(mUnderlinePath, mTextPaint);
                break;
            }
            case TextStyle.UNDERLINE_STYLE_DOTTED:
                mTextPaint.setPathEffect(mDottedUnderlineEffect);
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint);
                break;
            case TextStyle.UNDERLINE_STYLE_DASHED:
                mTextPaint.setPathEffect(mDashedUnderlineEffect);
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint);
                break;
            default:
                break;
        }

        mTextPaint.setPathEffect(null);
        mTextPaint.setStyle(Paint.Style.FILL);
        mTextPaint.setStrokeWidth(0.f);
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
