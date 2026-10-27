package io.github.arnavdugad.ember.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.ConfettiBurst
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GradientText
import io.github.arnavdugad.ember.core.designsystem.OverlayEffect
import io.github.arnavdugad.ember.core.image.EmberImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.arnavdugad.ember.data.ai.AiConfig

/**
 * "It's a match": both photos fly in from opposite edges and settle into a tilted overlap, a
 * heart pops between them, confetti in both photos' colours and sparks burst out, and the headline
 * springs in on a glass card over the blurred deck. If their like was a super like, the heart
 * lands with a heartbeat.
 */
@Composable
fun MatchCelebration(
    celebration: Celebration,
    myPhotoUrl: String?,
    /** The AI answering for demo profiles, or null when none is set up. */
    ai: AiConfig,
    onMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val match = celebration.match
    val leftX = remember(match.id) { Animatable(-1f) }
    val rightX = remember(match.id) { Animatable(1f) }
    val heart = remember(match.id) { Animatable(0f) }
    val title = remember(match.id) { Animatable(0f) }
    val content = remember(match.id) { Animatable(0f) }
    BackHandler(onBack = onDismiss)
    OverlayEffect(true)

    LaunchedEffect(match.id) {
        haptics.celebrate()
        launch { leftX.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f)) }
        launch {
            delay(60)
            rightX.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
        }
        launch {
            delay(380)
            if (celebration.theySuperLiked) haptics.heartbeat()
            heart.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = 300f))
        }
        launch {
            delay(260)
            title.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
        }
        launch {
            delay(520)
            content.animateTo(1f, tween(380))
        }
    }

    // The overlay swallows every touch so taps can't fall through to the deck's buttons.
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        ConfettiBurst(
            key = match.id,
            modifier = Modifier.fillMaxSize(),
            origin = androidx.compose.ui.geometry.Offset(0.5f, 0.34f),
            photoColors = celebration.confetti,
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            val travel = maxWidth
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(0.6f))
                Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                    FlyingPhoto(myPhotoUrl, null, angle = -9f, offsetX = (-58).dp, progress = leftX.value, travel = travel)
                    FlyingPhoto(match.otherPhotoUrl, match.otherBlurHash, angle = 9f, offsetX = 58.dp, progress = rightX.value, travel = travel)
                    Box(
                        Modifier
                            .size(58.dp)
                            .graphicsLayer {
                                scaleX = heart.value
                                scaleY = heart.value
                            }
                            .dropShadow(CircleShape, Shadow(radius = 18.dp, color = colors.accentAlt.copy(alpha = 0.6f)))
                            .background(colors.accentBrush, CircleShape)
                            .border(3.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.height(28.dp))
                GlassSurface(
                    Modifier.fillMaxWidth().graphicsLayer {
                        alpha = content.value.coerceAtLeast(title.value * 0.6f)
                        translationY = (1f - content.value) * 40f
                    },
                    shape = EmberShapes.card,
                    style = GlassDefaults.chrome(),
                    elevated = true,
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GradientText(
                            if (match.superLike) "Super match!" else "It's a match!",
                            style = EmberTheme.type.display,
                            modifier = Modifier.graphicsLayer {
                                scaleX = title.value
                                scaleY = title.value
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You and ${match.otherName} liked each other.",
                            style = EmberTheme.type.body,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        if (match.otherIsDemo && !ai.hidesDemo) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (ai.ready) {
                                    "${match.otherName} is a demo profile. They'll reply in character with your AI key."
                                } else {
                                    "${match.otherName} is a demo profile, so they won't reply. Add an AI key in Settings and they will."
                                },
                                style = EmberTheme.type.caption,
                                color = colors.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(22.dp))
                        EmberButton("Send a message", onClick = onMessage, icon = Icons.AutoMirrored.Rounded.Send, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        GlassButton("Keep swiping", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlyingPhoto(url: String?, blurHash: String?, angle: Float, offsetX: Dp, progress: Float, travel: Dp) {
    Box(
        Modifier
            .graphicsLayer {
                translationX = (offsetX + travel * progress).toPx()
                rotationZ = angle + progress * angle * 3f
                val s = 1f - kotlin.math.abs(progress) * 0.2f
                scaleX = s
                scaleY = s
            }
            .size(width = 140.dp, height = 190.dp)
            .dropShadow(EmberShapes.panel, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 12.dp)))
            .clip(EmberShapes.panel)
            .border(3.dp, Color.White, EmberShapes.panel),
    ) {
        EmberImage(url, blurHash, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}
