package com.stocktracker.feature.portfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return null
    val results = mutableListOf<TextLayoutResult>()
    action.action?.invoke(results)
    return results.firstOrNull()
}

private val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

/**
 * True when the laid-out text does not cover the whole source string, i.e. characters were
 * dropped off the end.
 *
 * Deliberately not [TextLayoutResult.hasVisualOverflow]: under Robolectric's stub font,
 * `multiParagraph.width` reports the *constraint* width while `size.width` reports the ink
 * width (320 vs 6 for the string "Import"), so `hasVisualOverflow` is true for every text
 * node including ones that fit comfortably. Comparing the last visible character index
 * against the string length asks the question directly and is unaffected by that.
 *
 * Note this measures Robolectric's font metrics, not a real device's: it catches text that is
 * structurally too long for its box, not a one-glyph overhang from a different font.
 */
private fun TextLayoutResult.isTruncated(): Boolean {
    if (lineCount == 0) return false
    val visibleEnd = getLineEnd(lineCount - 1, visibleEnd = true)
    return visibleEnd < layoutInput.text.length || didOverflowHeight
}

/**
 * Fails when any text is silently cut off. Ellipsised text passes: PositionCard and the
 * instrument cards truncate long names on purpose, and the ellipsis is the visual cue that
 * says so. Text laid out with [TextOverflow.Clip] has no such cue, so an overflow there is
 * always a layout bug rather than a design decision.
 */
fun SemanticsNodeInteractionsProvider.assertNoClippedText() {
    onAllNodes(hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
        val layout = node.textLayout() ?: return@forEach
        if (layout.layoutInput.overflow == TextOverflow.Clip && layout.isTruncated()) {
            throw AssertionError("Text is clipped with no ellipsis: \"${layout.layoutInput.text}\"")
        }
    }
}

/** Stricter check for text that must always be readable in full — values, chips, action labels. */
fun SemanticsNodeInteractionsProvider.assertTextNotTruncated(text: String) {
    val nodes = onAllNodes(hasText(text) and hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes()
    if (nodes.isEmpty()) throw AssertionError("No text node found with text \"$text\"")
    nodes.forEach { node ->
        val layout = node.textLayout() ?: return@forEach
        if (layout.isTruncated()) throw AssertionError("Text is truncated: \"$text\"")
    }
}

/** Guards against a visually smaller control shrinking its tap area below the Material minimum. */
fun SemanticsNodeInteractionsProvider.assertMinTouchTarget(text: String, minDp: Dp = 48.dp) {
    val nodes = onAllNodes(hasText(text)).fetchSemanticsNodes()
    if (nodes.isEmpty()) throw AssertionError("No node found with text \"$text\"")
    nodes.forEach { node ->
        val bounds = node.touchBoundsInRoot
        val density = node.layoutInfo.density
        val widthDp = with(density) { bounds.width.toDp() }
        val heightDp = with(density) { bounds.height.toDp() }
        if (widthDp < minDp || heightDp < minDp) {
            throw AssertionError("Touch target for \"$text\" is ${widthDp}x${heightDp}, below $minDp")
        }
    }
}
