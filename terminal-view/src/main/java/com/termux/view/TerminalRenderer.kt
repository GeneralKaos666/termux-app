package com.termux.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import com.termux.terminal.GraphemeClusters
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle

/**
 * Renderer of a [TerminalEmulator] into a [Canvas].
 *
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
class TerminalRenderer(
    @JvmField val mTextSize: Int,
    @JvmField val mTypeface: Typeface?,
    ligatures: Boolean,
    private val mTextPaint: Paint,
    runAdvances: RunAdvancesSource?,
) {

    /** The shaped-advance provider; `null` when the platform cannot report them (API < 29). */
    private val mRunAdvances: RunAdvancesSource?

    /** Grow-only scratch for shaped advances; only the run just measured is ever read back. */
    private var mRunAdvancesBuffer = FloatArray(0)

    /** Cursor fade/envelope opacity for the current frame, 0..1. */
    private var mCursorAlpha = 1f

    /** Halo radius drawn around the cursor rect, in pixels; zero means no halo. */
    private var mGlowRadius = 0f

    /** Eased cursor position, in cells, for the current frame; negative disables the eased drawing. */
    private var mEasedCursorRow = -1f
    private var mEasedCursorCol = -1f

    /** Whether the cursor is drawn at its eased position instead of snapping to its cell. */
    private var mEaseActive = false

    /** The width of a single mono spaced character obtained by [Paint.measureText] on a single 'X'. */
    @JvmField
    val mFontWidth: Float

    /** The [Paint.getFontSpacing]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    @JvmField
    val mFontLineSpacing: Int

    /** The [Paint.ascent]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private val mFontAscent: Int

    /** The [mFontLineSpacing] + [mFontAscent]. */
    @JvmField
    val mFontLineSpacingAndAscent: Int

    private val asciiMeasures = FloatArray(127)

    /** Reused by [drawUnderline] so a decorated underline does not allocate a path per run. */
    private val mUnderlinePath = Path()

    /** Dash patterns for the dotted/dashed underline styles; the intervals derive from the font size. */
    private val mDottedUnderlineEffect: DashPathEffect
    private val mDashedUnderlineEffect: DashPathEffect

    constructor(textSize: Int, typeface: Typeface?) : this(textSize, typeface, true, Paint(), null)

    /** Whether the paint asks the font for ligatures is a construction setting: it does not affect metrics. */
    constructor(textSize: Int, typeface: Typeface?, ligatures: Boolean) :
        this(textSize, typeface, ligatures, Paint(), null)

    init {
        mRunAdvances = runAdvances ?: RunAdvancesSource.forPaint(mTextPaint)

        mTextPaint.typeface = mTypeface
        mTextPaint.isAntiAlias = true
        mTextPaint.textSize = mTextSize.toFloat()
        mTextPaint.fontFeatureSettings = if (ligatures) LIGATURES_ON else LIGATURES_OFF

        mFontLineSpacing = Math.ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = Math.ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent
        mFontWidth = mTextPaint.measureText("X")

        // Dash intervals are fixed once the font size is known, so the effects can be shared by every run.
        val underlineStroke = Math.max(1f, mFontLineSpacing / 14f)
        mDottedUnderlineEffect = DashPathEffect(floatArrayOf(underlineStroke, underlineStroke * 1.5f), 0f)
        mDashedUnderlineEffect = DashPathEffect(floatArrayOf(underlineStroke * 3f, underlineStroke * 2f), 0f)

        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int,
    ) {
        render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, topRow, topRow + mEmulator.mRows)
    }

    /**
     * Render the terminal to a canvas at a specified row scroll and an optional rectangular selection, but only the
     * rows in the range `[firstScreenRow, lastScreenRowExclusive)`.
     *
     * The row range is expressed in external (screen) row numbers and must already be clamped inside the visible band
     * `[topRow, topRow + mEmulator.mRows)` by the caller, so that `firstScreenRow >= topRow` holds.
     */
    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int,
        firstScreenRow: Int, lastScreenRowExclusive: Int,
    ) {
        render(
            mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2,
            firstScreenRow, lastScreenRowExclusive, 1f, 0f, -1f, -1f,
        )
    }

    /**
     * Render like the row-range overload, additionally applying the cursor's visual effects:
     * `cursorAlpha` fades the cursor rect, `glowRadius` adds a halo of the same colour, and
     * `easedCursorRow`/`easedCursorCol` move a bar or underline cursor smoothly towards its cell
     * (a block cursor always snaps, because its cell inversion is baked into the text run). A negative eased
     * row or column disables the easing, and an alpha of zero hides the cursor and its text inversion.
     */
    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int,
        firstScreenRow: Int, lastScreenRowExclusive: Int,
        cursorAlpha: Float, glowRadius: Float, easedCursorRow: Float, easedCursorCol: Float,
    ) {
        val reverseVideo = mEmulator.isReverseVideo
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.cursorCol
        val cursorRow = mEmulator.cursorRow
        val cursorVisible = mEmulator.shouldCursorBeVisible()
        val screen = mEmulator.screen
        val palette = mEmulator.mColors.mCurrentColors
        val cursorShape = mEmulator.cursorStyle

        mCursorAlpha = cursorAlpha
        mGlowRadius = glowRadius
        mEasedCursorRow = easedCursorRow
        mEasedCursorCol = easedCursorCol
        mEaseActive = easedCursorRow >= 0f && easedCursorCol >= 0f &&
            (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR ||
                cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE)

        if (reverseVideo) {
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC)
        }

        // The halo goes down before the rows, so the glyphs of every cell are painted on top of it and stay
        // crisp. It is skipped when the row actually drawn is outside the band being drawn.
        val haloRow = if (mEaseActive && easedCursorRow >= 0f) easedCursorRow else cursorRow.toFloat()
        val haloCol = if (mEaseActive && easedCursorCol >= 0f) easedCursorCol else cursorCol.toFloat()
        if (cursorVisible && mCursorAlpha > 0f && mGlowRadius > 0f &&
            haloRow >= firstScreenRow - 1 && haloRow <= lastScreenRowExclusive
        ) {
            drawCursorHalo(
                canvas, haloCol, haloRow, topRow, cursorShape,
                palette[TextStyle.COLOR_INDEX_CURSOR],
            )
        }

        // The offset is advanced by one line spacing per row, so rows skipped at the start of the range must be
        // accounted for here to keep every row at its true pixel offset.
        var heightOffset = (mFontLineSpacingAndAscent + (firstScreenRow - topRow) * mFontLineSpacing).toFloat()
        for (row in firstScreenRow until lastScreenRowExclusive) {
            heightOffset += mFontLineSpacing

            val cursorX = if (row == cursorRow && cursorVisible && mCursorAlpha > 0f) cursorCol else -1
            var selx1 = -1
            var selx2 = -1
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1
                selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
            }

            // Taken under the buffer lock: the emulator writes rows from its own thread, and a live
            // read could pair a new char with an old style (or the reverse) mid-cell.
            val rowSnapshot = screen.snapshotRow(row)
            val line = rowSnapshot.text
            val charsUsedInLine = rowSnapshot.spaceUsed

            var lastRunStyle = 0L
            var lastRunLinkId = 0
            var lastRunUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
            var lastRunInsideCursor = false
            var lastRunInsideSelection = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0

            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighsurrogate = Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighsurrogate) 2 else 1
                val codePoint =
                    if (charIsHighsurrogate) Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) else charAtIndex.code
                val style = rowSnapshot.styles[column]
                val linkId = rowSnapshot.linkIds[column]
                val underlineColor = rowSnapshot.underlineColors[column]
                if (TextStyle.isTerminalBitmap(style)) {
                    // A bitmap cell is not text: close the pending run at this column, so the cells
                    // before it are drawn and the run after it starts at the next column.
                    if (lastRunStartColumn >= 0 && column != lastRunStartColumn) {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
                        var invertCursorTextColor = false
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true
                        }
                        drawTextRun(
                            canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || lastRunInsideSelection,
                            invertCursorTextColor, lastRunLinkId, lastRunUnderlineColor,
                        )
                    }
                    val bitmap = mEmulator.screen.getSixelBitmap(style)
                    if (bitmap != null) {
                        val left = column * mFontWidth
                        val top = heightOffset - mFontLineSpacing
                        val bitmapSrcRect = mEmulator.screen.getSixelRect(style)
                        val bitmapDestRect = RectF(left, top, left + mFontWidth, top + mFontLineSpacing)
                        canvas.drawBitmap(bitmap, bitmapSrcRect, bitmapDestRect, null)
                    }
                    column += 1
                    currentCharIndex += charsForCodePoint
                    lastRunStyle = 0
                    lastRunLinkId = 0
                    lastRunUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
                    lastRunInsideCursor = false
                    lastRunFontWidthMismatch = false
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    continue
                }
                // The emulator stores a whole grapheme cluster in the cell of its base code point, so advance by
                // the cluster: a ZWJ sequence, a flag or a skin toned emoji occupies its base code point's width in
                // total, and its continuation code points take none.
                val clusterEnd = GraphemeClusters.clusterEnd(line, currentCharIndex, charsUsedInLine)
                val clusterChars = clusterEnd - currentCharIndex
                val clusterColumns = GraphemeClusters.clusterWidth(line, currentCharIndex, charsUsedInLine)
                val insideCursor = cursorX == column || (clusterColumns == 2 && cursorX == column + 1)
                val insideSelection = column in selx1..selx2

                // Check if the measured text width for this cell's cluster is not the same as that expected by its
                // column count. This could happen for some fonts which are not truly monospace, or for more exotic
                // characters such as smileys which android font renders as wide.
                // If this is detected, we draw the cluster scaled to match its cells.
                val measuredCodePointWidth =
                    if (charsForCodePoint == clusterChars && codePoint < asciiMeasures.size) {
                        asciiMeasures[codePoint]
                    } else {
                        mTextPaint.measureText(line, currentCharIndex, clusterChars)
                    }
                val fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - clusterColumns) > 0.01

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch || linkId != lastRunLinkId || underlineColor != lastRunUnderlineColor) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
                        var invertCursorTextColor = false
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true
                        }
                        drawTextRun(
                            canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || lastRunInsideSelection,
                            invertCursorTextColor, lastRunLinkId, lastRunUnderlineColor,
                        )
                    }
                    lastRunStyle = style
                    lastRunLinkId = linkId
                    lastRunUnderlineColor = underlineColor
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                column += clusterColumns
                currentCharIndex = clusterEnd
            }

            val columnWidthSinceLastRun = columns - lastRunStartColumn
            val charsSinceLastRun = currentCharIndex - lastRunStartIndex
            val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
            var invertCursorTextColor = false
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true
            }
            drawTextRun(
                canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                cursorColor, cursorShape, lastRunStyle, reverseVideo || lastRunInsideSelection,
                invertCursorTextColor, lastRunLinkId, lastRunUnderlineColor,
            )
        }

        // An eased bar/underline cursor is drawn over the finished rows so it can sit between two cells.
        if (mEaseActive && cursorVisible && mCursorAlpha > 0f && mEasedCursorRow >= 0f) {
            val easedLeft = mEasedCursorCol * mFontWidth
            val easedY = mFontLineSpacingAndAscent + (mEasedCursorRow - topRow + 1) * mFontLineSpacing
            drawCursorRect(
                canvas, easedLeft, easedLeft + mFontWidth, easedY, cursorShape,
                palette[TextStyle.COLOR_INDEX_CURSOR],
            )
        }
    }

    private fun drawTextRun(
        canvas: Canvas, text: CharArray, palette: IntArray, y: Float, startColumn: Int, runWidthColumns: Int,
        startCharIndex: Int, runWidthChars: Int, cursor: Int, cursorStyle: Int,
        textStyle: Long, reverseVideo: Boolean, invertCursorTextColor: Boolean,
        linkId: Int, underlineColor: Int,
    ) {
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        val underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        val underlineStyle = TextStyle.decodeUnderlineStyle(textStyle)
        val coloredUnderline = underlineColor != TextStyle.COLOR_INDEX_FOREGROUND
        // A hyperlink cell with no SGR underline of its own is shown as a single underline in its
        // foreground color; an SGR underline already set is kept as is, so a link never doubles it.
        // The built-in paint underline is a plain solid line: use it for "single", and for callers that set
        // the underline bit without a style (WS1 keeps those as UNDERLINE_STYLE_NONE). The decorated styles
        // are stroked by drawUnderline() below, after the glyphs (and so is any colored underline).
        val linkUnderline = linkId != 0 && !underline
        val singleUnderline = underline &&
            (underlineStyle == TextStyle.UNDERLINE_STYLE_NONE ||
                underlineStyle == TextStyle.UNDERLINE_STYLE_SINGLE ||
                underlineStyle < TextStyle.UNDERLINE_STYLE_NONE ||
                underlineStyle > TextStyle.UNDERLINE_STYLE_DASHED)
        val paintUnderline = (singleUnderline || linkUnderline) && !coloredUnderline
        val customUnderline = (underline || linkUnderline) && !paintUnderline
        val italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0
        val strikeThrough = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        val dim = effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0

        // Let bold have bright colors if applicable (one of the first 8):
        foreColor = resolveColor(foreColor, bold, palette)
        backColor = resolveColor(backColor, false, palette)

        // Reverse video here if _one and only one_ of the reverse flags are set:
        val inverseEffect = effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0
        val reverseVideoHere = (reverseVideo || invertCursorTextColor) xor inverseEffect
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }

        // A block cursor's cell is painted opaque (the cursor colour behind, the cell's background colour in
        // front), so a translucent rect over it could not fade it. Blend the two colours by the frame's alpha
        // instead, which fades the block and its inverted glyph together.
        val fadingBlockCursor = cursor != 0 &&
            cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK && mCursorAlpha < 1f
        if (fadingBlockCursor) {
            // The cell without the cursor is this run with the cursor's forced inversion removed. That leaves the
            // run's colours as they are when the cell is already reversed by reverse video or a selection (the
            // cursor's inversion then cancels that reversal), and swaps them when it is not.
            val unCursoredReverseVideoHere = reverseVideo xor inverseEffect
            var unCursoredForeground = foreColor
            var unCursoredBackground = backColor
            if (unCursoredReverseVideoHere != reverseVideoHere) {
                unCursoredForeground = backColor
                unCursoredBackground = foreColor
            }
            backColor = blendColor(unCursoredBackground, cursor, mCursorAlpha)
            foreColor = blendColor(unCursoredForeground, unCursoredBackground, mCursorAlpha)
        }

        val left = startColumn * mFontWidth
        var right = left + runWidthColumns * mFontWidth

        // The run's background, cursor and custom underline are cell-exact and are never scaled; only the
        // glyphs are, and only within a span (drawGlyphs). The rectangles are therefore drawn directly at
        // their cells instead of being pre-divided by a canvas transform.
        // Kept before the cursor block below can shorten "right" (bar cursor): an underline spans the run.
        val runRight = right

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.color = backColor
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }

        if (cursor != 0 && !mEaseActive && !fadingBlockCursor) {
            drawCursorRect(canvas, left, right, y, cursorStyle, cursor)
        }

        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
            if (dim) {
                var red = 0xFF and (foreColor shr 16)
                var green = 0xFF and (foreColor shr 8)
                var blue = 0xFF and foreColor
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                foreColor = 0xFF000000.toInt() + (red shl 16) + (green shl 8) + blue
            }

            mTextPaint.isFakeBoldText = bold
            mTextPaint.isUnderlineText = paintUnderline
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = foreColor

            // The text alignment is the default Paint.Align.LEFT.
            drawGlyphs(canvas, text, startCharIndex, runWidthChars, startColumn, runWidthColumns, y)

            if (customUnderline) {
                // A hyperlink cell with no SGR underline of its own gets a plain single underline in its
                // foreground color. When the cell carries an SGR 58 color, that color strokes the underline
                // instead (a link underline is an underline of the cell like any other).
                val underlineDrawColor = if (coloredUnderline) resolveColor(underlineColor, bold, palette) else foreColor
                // Decorated styles are stroked as stored; everything else is a plain single underline. That
                // covers a link's own underline, and styles that decode to UNDERLINE_STYLE_NONE or to an
                // out-of-range value, which TextStyle defines as a plain underline as well — passing those
                // through unchanged would draw nothing at all.
                val underlineDrawStyle = if (underline && !singleUnderline) underlineStyle else TextStyle.UNDERLINE_STYLE_SINGLE
                drawUnderline(canvas, left, runRight, y, underlineDrawColor, underlineDrawStyle)
            }
        }
    }

    /**
     * Fills the cursor's cell (or its cell-sized eased footprint) with the cursor colour, applying the frame's
     * alpha. Leaves [mTextPaint] opaque, because the glyphs of the same run are drawn with the same paint
     * right after this rect.
     */
    private fun drawCursorRect(canvas: Canvas, left: Float, rightIn: Float, y: Float, cursorShape: Int, cursorColor: Int) {
        var right = rightIn
        mTextPaint.color = cursorColor
        mTextPaint.alpha = (mCursorAlpha * 255f + 0.5f).toInt()
        var cursorHeight = (mFontLineSpacingAndAscent - mFontAscent).toFloat()
        if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            cursorHeight /= 4f
        } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
            right -= (right - left) * 3 / 4f
        }
        canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        mTextPaint.alpha = 255
    }

    /**
     * Draws the cursor's halo as a few expanding rects of low opacity. A [Paint] shadow layer is ignored
     * for [Canvas.drawRect] on a hardware-accelerated canvas, which is what a `View`'s
     * `onDraw` is handed, so the halo is painted explicitly. Callers draw it before the rows so it can
     * never tint a glyph.
     */
    private fun drawCursorHalo(canvas: Canvas, column: Float, row: Float, topRow: Int, cursorShape: Int, color: Int) {
        val steps = 3
        val stepAlpha = (mCursorAlpha * 255f * 0.12f).toInt()
        if (stepAlpha <= 0) return
        val left = column * mFontWidth
        var right = left + mFontWidth
        val y = mFontLineSpacingAndAscent + (row - topRow + 1) * mFontLineSpacing
        var cursorHeight = (mFontLineSpacingAndAscent - mFontAscent).toFloat()
        if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            cursorHeight /= 4f
        } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
            right -= (right - left) * 3 / 4f
        }
        val top = y - cursorHeight
        mTextPaint.color = color
        mTextPaint.alpha = stepAlpha
        for (step in steps downTo 1) {
            val expand = mGlowRadius * step / steps
            canvas.drawRect(left - expand, top - expand, right + expand, y + expand, mTextPaint)
        }
        mTextPaint.alpha = 255
    }

    /**
     * Draws the glyphs of one run. Where the platform reports shaped advances (API 29+), the run is verified
     * against the cell grid: at the first cell boundary the advances miss, the run is cut and the spans are drawn
     * on their own, so a cluster can never push a glyph into the next cell. A run the policy does not cut, and
     * every run when no advances are available, is drawn as a single span.
     */
    private fun drawGlyphs(
        canvas: Canvas, text: CharArray, startCharIndex: Int, runWidthChars: Int, startColumn: Int,
        runWidthColumns: Int, y: Float,
    ) {
        var spanCharIndex = startCharIndex
        var spanStartColumn = startColumn
        var columnsLeft = runWidthColumns
        var charsLeft = runWidthChars
        while (columnsLeft > 0) {
            var spanColumns = columnsLeft
            if (mRunAdvances != null && columnsLeft > 1) {
                ensureRunAdvancesBuffer(charsLeft)
                val advanceCount = mRunAdvances.advances(text, spanCharIndex, charsLeft, mRunAdvancesBuffer)
                val misalignedCell = ShapedRunPolicy.firstMisalignedCell(
                    mRunAdvancesBuffer, advanceCount,
                    columnsLeft, mFontWidth,
                )
                if (misalignedCell > 0) spanColumns = misalignedCell
            }
            val spanChars = charsForColumns(text, spanCharIndex, spanColumns, spanCharIndex + charsLeft)
            if (spanChars <= 0) {
                // Defensive: the slice cannot be walked (degenerate zero-width data); draw the rest and stop.
                drawGlyphSpan(canvas, text, spanCharIndex, charsLeft, spanStartColumn, columnsLeft, y)
                return
            }
            // The policy cut on a cell boundary, which can split a wide cluster. Walk the span's whole clusters so
            // the glyphs are drawn on the cells they actually occupy.
            var spannedColumns = columnsForChars(text, spanCharIndex, spanCharIndex + spanChars)
            if (spannedColumns <= 0) spannedColumns = spanColumns
            drawGlyphSpan(canvas, text, spanCharIndex, spanChars, spanStartColumn, spannedColumns, y)
            spanCharIndex += spanChars
            spanStartColumn += spannedColumns
            charsLeft -= spanChars
            columnsLeft -= spannedColumns
        }
    }

    /**
     * Draws one span onto its own cells. The span's shaped total decides whether the canvas is scaled
     * horizontally, so a span the font measures off its cells still lands inside them (the same 1% tolerance the
     * run loop's per-codepoint check uses). A non-positive cell width cannot be scaled, so the span is drawn at
     * its cell origin instead.
     */
    private fun drawGlyphSpan(
        canvas: Canvas, text: CharArray, startCharIndex: Int, spanChars: Int, startColumn: Int,
        spanColumns: Int, y: Float,
    ) {
        val left = startColumn * mFontWidth
        val measuredWidth = mTextPaint.measureText(text, startCharIndex, spanChars)
        val measuredColumns = if (mFontWidth > 0f) measuredWidth / mFontWidth else spanColumns.toFloat()
        if (measuredColumns > 0f && Math.abs(measuredColumns - spanColumns) > 0.01) {
            // The canvas is scaled about the origin, so the glyph origin is pre-divided to land on the cell.
            canvas.save()
            canvas.scale(spanColumns / measuredColumns, 1f)
            drawGlyphText(canvas, text, startCharIndex, spanChars, left * (measuredColumns / spanColumns), y)
            canvas.restore()
        } else {
            drawGlyphText(canvas, text, startCharIndex, spanChars, left, y)
        }
    }

    /** Draws the glyphs of one span, shaped when the platform can shape them (API 23+). */
    private fun drawGlyphText(canvas: Canvas, text: CharArray, startCharIndex: Int, spanChars: Int, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            canvas.drawTextRun(text, startCharIndex, spanChars, startCharIndex, spanChars, x, y - mFontLineSpacingAndAscent, false, mTextPaint)
        } else {
            canvas.drawText(text, startCharIndex, spanChars, x, y - mFontLineSpacingAndAscent, mTextPaint)
        }
    }

    private fun ensureRunAdvancesBuffer(chars: Int) {
        if (mRunAdvancesBuffer.size < chars) mRunAdvancesBuffer = FloatArray(chars)
    }

    /**
     * Strokes an underline for one text run: the single underline, the decorated styles (double, curly,
     * dotted or dashed) and single underlines that carry an explicit SGR 58 color (which the text paint's
     * own underline cannot be given). `left`/`right` are the run's cell coordinates; the glyphs
     * of an off-grid span may be scaled, but the underline is not. An unrecognized style is stroked as a single
     * underline rather than dropped.
     *
     * Leaves [mTextPaint] in the state the rest of the renderer assumes: the next run paints
     * backgrounds and cursor cells as filled rects with the same paint, so the fill style must come back.
     */
    private fun drawUnderline(canvas: Canvas, left: Float, right: Float, y: Float, color: Int, underlineStyle: Int) {
        val strokeWidth = Math.max(1f, mFontLineSpacing / 14f)
        val baseline = y - mFontLineSpacingAndAscent
        // Roughly where the font draws its own (single) underline: a tenth of an ascent below the baseline.
        // Clamped to the cell, because the next row is drawn later and would paint its background over any
        // decoration that spilled past the cell's bottom edge.
        val decorationHeight = if (underlineStyle == TextStyle.UNDERLINE_STYLE_DOUBLE) {
            strokeWidth * 2f
        } else {
            strokeWidth * 1.5f
        }
        val underlineY = Math.min(baseline + mFontAscent * -0.12f, y - decorationHeight - 0.5f)

        mTextPaint.isUnderlineText = false
        mTextPaint.isFakeBoldText = false
        mTextPaint.textSkewX = 0f
        mTextPaint.isStrikeThruText = false
        mTextPaint.color = color
        mTextPaint.style = Paint.Style.STROKE
        mTextPaint.strokeWidth = strokeWidth
        mTextPaint.pathEffect = null

        when (underlineStyle) {
            TextStyle.UNDERLINE_STYLE_DOUBLE -> {
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint)
                canvas.drawLine(left, underlineY + strokeWidth * 2f, right, underlineY + strokeWidth * 2f, mTextPaint)
            }

            TextStyle.UNDERLINE_STYLE_CURLY -> {
                mUnderlinePath.reset()
                mUnderlinePath.moveTo(left, underlineY)
                val step = strokeWidth * 3f
                var up = true
                var x = left
                while (x < right) {
                    val endX = Math.min(x + step, right)
                    mUnderlinePath.quadTo(
                        Math.min(x + step / 2f, right),
                        underlineY + (if (up) -step else step), endX, underlineY,
                    )
                    up = !up
                    x += step
                }
                canvas.drawPath(mUnderlinePath, mTextPaint)
            }

            TextStyle.UNDERLINE_STYLE_DOTTED -> {
                mTextPaint.pathEffect = mDottedUnderlineEffect
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint)
            }

            TextStyle.UNDERLINE_STYLE_DASHED -> {
                mTextPaint.pathEffect = mDashedUnderlineEffect
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint)
            }

            else -> {
                // The plain single underline is normally drawn by the text paint; this case exists for the
                // SGR 58 colored variants (and for a style the decoder did not recognize), which must be
                // stroked here to get their own color instead of being silently dropped.
                canvas.drawLine(left, underlineY, right, underlineY, mTextPaint)
            }
        }

        mTextPaint.pathEffect = null
        mTextPaint.style = Paint.Style.FILL
        mTextPaint.strokeWidth = 0f
    }

    fun getFontWidth(): Float {
        return mFontWidth
    }

    fun getFontLineSpacing(): Int {
        return mFontLineSpacing
    }

    companion object {
        /** Font features that ligatures-on requests: standard ligatures plus contextual alternates. */
        const val LIGATURES_ON = "+liga,+calt"

        /** Font features that ligatures-off requests. */
        const val LIGATURES_OFF = "-liga,-calt"

        /** Linear blend of two opaque colours: `factor` 0 keeps `from`, 1 returns `to`. */
        private fun blendColor(from: Int, to: Int, factor: Float): Int {
            val red = (((from shr 16) and 0xFF) + ((((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * factor) + 0.5f).toInt()
            val green = (((from shr 8) and 0xFF) + ((((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * factor) + 0.5f).toInt()
            val blue = ((from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * factor) + 0.5f).toInt()
            return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }

        /**
         * How many chars of `text` (from `startCharIndex`) occupy `columns` cells, bounded by
         * `endCharIndex`. Whole grapheme clusters are consumed with the cells of their base char, the same way
         * the run loop walks them, so a span never splits a cluster such as a ZWJ sequence or a flag.
         */
        private fun charsForColumns(text: CharArray, startCharIndex: Int, columns: Int, endCharIndex: Int): Int {
            var charIndex = startCharIndex
            var columnsLeft = columns
            while (charIndex < endCharIndex && columnsLeft > 0) {
                columnsLeft -= Math.max(GraphemeClusters.clusterWidth(text, charIndex, endCharIndex), 0)
                charIndex = GraphemeClusters.clusterEnd(text, charIndex, endCharIndex)
            }
            return charIndex - startCharIndex
        }

        /**
         * How many cells the chars in `[startCharIndex, endCharIndex)` occupy, walking whole clusters. A span cut
         * by the shaped-run policy can therefore be a cell wider than the policy asked for, which keeps the cluster in
         * one draw instead of moving the glyphs after it off the grid.
         */
        private fun columnsForChars(text: CharArray, startCharIndex: Int, endCharIndex: Int): Int {
            var charIndex = startCharIndex
            var columns = 0
            while (charIndex < endCharIndex) {
                columns += Math.max(GraphemeClusters.clusterWidth(text, charIndex, endCharIndex), 0)
                charIndex = GraphemeClusters.clusterEnd(text, charIndex, endCharIndex)
            }
            return columns
        }

        /**
         * Resolves a [TextStyle]-encoded color to an ARGB value: a 24-bit value passes through, a
         * palette index is looked up (after being brightened by 8 when `brightenForBold` is set, the
         * treatment bold text gives the low 8 indices).
         */
        private fun resolveColor(colorIn: Int, brightenForBold: Boolean, palette: IntArray): Int {
            var color = colorIn
            if ((color and 0xff000000.toInt()) == 0xff000000.toInt()) return color
            if (brightenForBold && color in 0..7) color += 8
            return palette[color]
        }
    }
}
