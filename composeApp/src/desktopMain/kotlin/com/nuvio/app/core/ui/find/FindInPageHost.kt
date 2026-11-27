package com.nuvio.app.core.ui.find

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.controller.ControllerManager
import com.nuvio.app.controller.ControllerScreenType
import kotlinx.coroutines.delay
import java.awt.Window

/** How often matches are re-read while the bar is open, so they follow scrolling and loading. */
private const val RESCAN_INTERVAL_MS = 150L

private val BarBackground = Color(0xF2202026)
private val BarBorder = Color(0x33FFFFFF)
private val BarText = Color(0xFFF2F2F2)
private val BarMuted = Color(0xFF9A9AA2)
private val NoMatchText = Color(0xFFFF8A80)
private val MatchFill = Color(0x66FFE14D)
private val CurrentMatchFill = Color(0x99FF9632)
private val CurrentMatchStroke = Color(0xFFFF9632)

/** Overlay for [FindInPage]: match highlights plus the find bar. Place it above the app content. */
@Composable
internal fun FindInPageHost(window: Window) {
    // The player owns the keyboard (and its surface may be native), so find closes there.
    LaunchedEffect(Unit) {
        snapshotFlow { ControllerManager.screenContext.value.screenType }
            .collect { if (it == ControllerScreenType.Player && FindInPage.isOpen) FindInPage.close() }
    }
    if (!FindInPage.isOpen) return

    LaunchedEffect(window, FindInPage.query) {
        while (true) {
            val found = runCatching {
                scanForMatches(findSemanticRoots(window), FindInPage.query)
            }.getOrDefault(emptyList())
            FindInPage.publishMatches(found)
            delay(RESCAN_INTERVAL_MS)
        }
    }

    Box(modifier = Modifier.fillMaxSize().testTag(FIND_IN_PAGE_TAG)) {
        MatchHighlights()
        FindBar(modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 20.dp))
    }
}

@Composable
private fun MatchHighlights() {
    val matches = FindInPage.matches
    val current = FindInPage.currentIndex
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pad = 2.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        matches.forEachIndexed { index, match ->
            val bounds = match.visibleBounds
            if (bounds.isEmpty) return@forEachIndexed
            val rect = bounds.inflate(pad)
            val isCurrent = index == current
            drawRoundRect(
                color = if (isCurrent) CurrentMatchFill else MatchFill,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = radius,
            )
            if (isCurrent) {
                drawRoundRect(
                    color = CurrentMatchStroke,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = radius,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun FindBar(modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    val fieldState = rememberTextFieldState(FindInPage.query)

    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect(FindInPage::updateQuery)
    }

    // Each Ctrl+F refocuses the field and selects its text, ready to type over.
    LaunchedEffect(FindInPage.openRequests) {
        fieldState.edit { selectAll() }
        runCatching { focusRequester.requestFocus() }
    }

    val shape = RoundedCornerShape(10.dp)
    val textStyle = TextStyle(color = BarText, fontSize = 14.sp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(BarBackground)
            .border(1.dp, BarBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(220.dp)) {
            if (fieldState.text.isEmpty()) {
                BasicText("Find on page", style = textStyle.copy(color = BarMuted))
            }
            BasicTextField(
                state = fieldState,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = textStyle,
                cursorBrush = SolidColor(BarText),
                modifier = Modifier
                    .width(220.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { FindInPage.isFieldFocused = it.isFocused },
            )
        }
        Spacer(Modifier.width(10.dp))
        MatchCount()
        Spacer(Modifier.width(6.dp))
        BarButton(label = "▲", onClick = FindInPage::previous)
        BarButton(label = "▼", onClick = FindInPage::next)
        BarButton(label = "✕", onClick = FindInPage::close)
    }
}

@Composable
private fun MatchCount() {
    val total = FindInPage.matches.size
    val hasQuery = FindInPage.query.isNotBlank()
    val label = when {
        !hasQuery -> ""
        total == 0 -> "No matches"
        else -> "${FindInPage.currentIndex + 1} of $total"
    }
    BasicText(
        text = label,
        style = TextStyle(
            color = if (hasQuery && total == 0) NoMatchText else BarMuted,
            fontSize = 12.sp,
        ),
    )
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = BarText, fontSize = 12.sp))
    }
}
