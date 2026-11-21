package com.nuvio.app.controller

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where the ring should sit: an element's bounds in root coordinates and its outline. */
internal data class FocusRingTarget(
    val owner: Any,
    val bounds: Rect,
    val shape: Shape,
)

/**
 * The focused element reports itself here, and [FocusRingOverlay] draws one ring that glides between
 * them. The spatial engine's nodes and Compose-focused elements ([com.nuvio.app.core.ui.nuvioFocusBorder])
 * report into separate slots; the engine wins while one of its nodes is focused.
 */
internal object FocusRingRegistry {
    var engineTarget by mutableStateOf<FocusRingTarget?>(null)
        private set
    var composeTarget by mutableStateOf<FocusRingTarget?>(null)
        private set

    val current: FocusRingTarget? get() = engineTarget ?: composeTarget

    fun reportEngine(target: FocusRingTarget) {
        if (engineTarget != target) engineTarget = target
    }

    fun clearEngine(owner: Any) {
        if (engineTarget?.owner === owner) engineTarget = null
    }

    fun reportCompose(target: FocusRingTarget) {
        if (composeTarget != target) composeTarget = target
    }

    fun clearCompose(owner: Any) {
        if (composeTarget?.owner === owner) composeTarget = null
    }
}

private val GlideWindow = 450.milliseconds

private val FocusRingGlide = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The single focus ring: a solid white outline just outside the focused element, drawn above the
 * app content so no list or card can clip it. Moving focus glides it to the new element; while the
 * same element moves (a list scrolling under it) it follows exactly.
 */
@Composable
fun BoxScope.FocusRingOverlay() {
    val navigating by ControllerManager.isNavigationActive.collectAsState()
    val target = FocusRingRegistry.current
    val visible = navigating && target != null
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "focus_ring_alpha",
    )

    val left = remember { Animatable(0f) }
    val top = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val bottom = remember { Animatable(0f) }
    var shape by remember { mutableStateOf<Shape>(RoundedCornerShape(12.dp)) }
    var placedOwner by remember { mutableStateOf<Any?>(null) }
    // While a glide is under way, updates for the same element retarget it instead of snapping.
    var glideEndsAt by remember { mutableStateOf<TimeMark?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(target, visible) {
        val next = target ?: return@LaunchedEffect
        shape = next.shape
        val b = next.bounds
        val sameElement = placedOwner === next.owner
        placedOwner = next.owner
        val glideRunning = glideEndsAt?.hasPassedNow() == false
        if (!visible || alpha < 0.05f || (sameElement && !glideRunning)) {
            // Appearing, or the same element moving: sit on it exactly.
            left.snapTo(b.left)
            top.snapTo(b.top)
            right.snapTo(b.right)
            bottom.snapTo(b.bottom)
            return@LaunchedEffect
        }
        if (!sameElement) glideEndsAt = TimeSource.Monotonic.markNow() + GlideWindow
        launch { left.animateTo(b.left, FocusRingGlide) }
        launch { top.animateTo(b.top, FocusRingGlide) }
        launch { right.animateTo(b.right, FocusRingGlide) }
        bottom.animateTo(b.bottom, FocusRingGlide)
    }

    Canvas(
        modifier = Modifier
            .matchParentSize()
            .zIndex(19f)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        if (alpha <= 0.01f) return@Canvas
        val strokePx = 3.dp.toPx()
        // A sliver of background between the element and the ring keeps it readable on white buttons.
        val gapPx = 2.5f.dp.toPx()
        val pad = gapPx + strokePx / 2f
        val rect = Rect(
            left = left.value - origin.x - pad,
            top = top.value - origin.y - pad,
            right = right.value - origin.x + pad,
            bottom = bottom.value - origin.y + pad,
        )
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        translate(left = rect.left, top = rect.top) {
            drawOutline(
                outline = shape.createOutline(Size(rect.width, rect.height), layoutDirection, this),
                color = Color.White,
                alpha = alpha,
                style = Stroke(width = strokePx),
            )
        }
    }
}
