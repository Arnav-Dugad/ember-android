package io.github.arnavdugad.ember.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.OverlayEffect
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Photo
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-screen photos: swipe between them, pinch or double-tap to zoom, pan when zoomed, and drag
 * down (or up) to dismiss while the black backdrop fades away.
 */
@Composable
fun PhotoViewer(photos: List<Photo>, initial: Int, name: String, onDismiss: () -> Unit) {
    OverlayEffect(true)
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    val enter = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(220)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val pulled = (abs(drag.value) / (height * 0.45f)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value * (1f - pulled) }.background(Color.Black))
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = drag.value
                val s = lerp(1f, 0.86f, pulled) * lerp(0.94f, 1f, enter.value)
                scaleX = s
                scaleY = s
                alpha = enter.value
            },
        ) { page ->
            val photo = photos[page]
            ZoomableImage(
                photo = photo,
                description = "$name's photo ${page + 1} of ${photos.size}",
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onDismissDrag = { dy -> scope.launch { drag.snapTo(drag.value + dy) } },
                onDismissEnd = { velocity ->
                    scope.launch {
                        if (abs(drag.value) > height * 0.18f || abs(velocity) > 1_600f) {
                            val direction = if (drag.value + velocity * 0.05f >= 0f) 1f else -1f
                            drag.animateTo(direction * height, tween(200))
                            onDismiss()
                        } else {
                            drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).graphicsLayer { alpha = enter.value * (1f - pulled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.Rounded.Close, contentDescription = "Close photos", onClick = onDismiss, style = GlassDefaults.overPhoto(), tint = Color.White)
            Spacer(Modifier.weight(1f))
            if (photos.size > 1) {
                GlassSurface(shape = PillShape, style = GlassDefaults.overPhoto()) {
                    Text(
                        "${pager.currentPage + 1} / ${photos.size}",
                        style = EmberTheme.type.caption,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size = IntSize.Zero

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
}

@Composable
private fun ZoomableImage(
    photo: Photo,
    description: String,
    onZoomChanged: (Boolean) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
) {
    val zoom = remember(photo.id) { ZoomState() }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                zoom.size = size
                detectTapGestures(onDoubleTap = { tap ->
                    // Double tap: zoom to 2.5x around the finger, or back out.
                    val fromScale = zoom.scale
                    val fromOffset = zoom.offset
                    val toScale = if (fromScale > 1.05f) 1f else DOUBLE_TAP_SCALE
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val toOffset = if (toScale == 1f) Offset.Zero else zoom.clamp((center - tap) * (toScale - 1f), toScale)
                    onZoomChanged(toScale > 1f)
                    scope.launch {
                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                            zoom.scale = lerp(fromScale, toScale, t)
                            zoom.offset = Offset(lerp(fromOffset.x, toOffset.x, t), lerp(fromOffset.y, toOffset.y, t))
                        }
                    }
                })
            }
            .pointerInput(photo.id) {
                zoom.size = size
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 0 undecided, 1 pinch/pan, 2 dismiss drag, 3 leave it to the pager.
                    var mode = 0
                    var travelled = Offset.Zero
                    val tracker = VelocityTracker()
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                mode = 1
                                val factor = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true) - Offset(size.width / 2f, size.height / 2f)
                                val newScale = (zoom.scale * factor).coerceIn(1f, MAX_SCALE)
                                // Keep the point between the fingers under the fingers.
                                val moved = centroid + (zoom.offset - centroid) * (newScale / zoom.scale) + pan
                                zoom.offset = zoom.clamp(moved, newScale)
                                zoom.scale = newScale
                                event.changes.forEach { it.consume() }
                            }
                            pressed.size == 1 && zoom.scale > 1.01f -> {
                                mode = 1
                                val change = pressed.first()
                                zoom.offset = zoom.clamp(zoom.offset + change.positionChange(), zoom.scale)
                                change.consume()
                            }
                            pressed.size == 1 && mode != 1 -> {
                                val change = pressed.first()
                                val delta = change.positionChange()
                                if (mode == 0) {
                                    travelled += delta
                                    if (travelled.getDistance() > viewConfiguration.touchSlop) {
                                        mode = if (abs(travelled.y) > abs(travelled.x) * 1.2f) 2 else 3
                                    }
                                }
                                if (mode == 2) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    onDismissDrag(delta.y)
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (mode == 2) onDismissEnd(tracker.calculateVelocity().y)
                    if (zoom.scale < 1.01f) {
                        zoom.scale = 1f
                        zoom.offset = Offset.Zero
                    }
                    onZoomChanged(zoom.scale > 1.01f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        EmberImage(
            photo.url,
            photo.blurHash,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        )
    }
}

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
