package io.github.arnavdugad.ember.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassStyle
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.LikeNoteKind
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.ProfilePrompt

/** What a heart on a profile points at: one prompt, or one photo. */
@Immutable
sealed interface LikeTarget {
    val kind: LikeNoteKind
    val ref: String

    data class OfPrompt(val prompt: ProfilePrompt) : LikeTarget {
        override val kind get() = LikeNoteKind.Prompt
        override val ref get() = prompt.id
    }

    data class OfPhoto(val photo: Photo) : LikeTarget {
        override val kind get() = LikeNoteKind.Photo
        override val ref get() = photo.id
    }

    /** Only ids the rules accept get a heart (old docs can carry a URL in place of an id). */
    val likeable: Boolean get() = LikeNote.isValidRef(ref)
}

/** The small glass heart on each prompt and photo. */
@Composable
fun LikeHeart(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: GlassStyle = GlassDefaults.surface()) {
    val haptics = EmberTheme.haptics
    Box(
        modifier
            .size(40.dp)
            .pressable(onClick = {
                haptics.tick()
                onClick()
            }, pressedScale = 0.86f)
            .glass(CircleShape, style, LocalHazeState.current),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, contentDescription = contentDescription, tint = EmberTheme.colors.like, modifier = Modifier.size(19.dp))
    }
}

/**
 * Like one prompt or photo, with an optional comment of up to [LikeNote.MAX_TEXT] characters.
 * [target] is kept by the caller while the sheet slides away, so it never empties mid-animation.
 */
@Composable
fun LikeNoteSheet(visible: Boolean, target: LikeTarget?, name: String, onSend: (LikeNote) -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    var comment by remember(target) { mutableStateOf("") }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        if (target == null) return@GlassBottomSheet
        Text(
            if (target is LikeTarget.OfPrompt) "Like $name's prompt" else "Like $name's photo",
            style = EmberTheme.type.headline,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(14.dp))
        when (target) {
            is LikeTarget.OfPrompt -> GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                Column(Modifier.padding(18.dp)) {
                    Text(target.prompt.question, style = EmberTheme.type.label, color = colors.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(target.prompt.answer, style = EmberTheme.type.titleSmall, color = colors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            is LikeTarget.OfPhoto -> EmberImage(
                target.photo.url,
                target.photo.blurHash,
                contentDescription = "$name's photo",
                modifier = Modifier.width(132.dp).height(168.dp).clip(EmberShapes.tile),
            )
        }
        Spacer(Modifier.height(16.dp))
        EmberTextField(
            value = comment,
            onValueChange = { comment = it },
            placeholder = "Add a comment (optional)",
            singleLine = false,
            minLines = 2,
            maxLength = LikeNote.MAX_TEXT,
            imeAction = ImeAction.Done,
        )
        Text(
            "They'll see it with your like. If you match, it starts the chat.",
            style = EmberTheme.type.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        EmberButton(
            if (comment.isBlank()) "Send like" else "Send like with comment",
            onClick = { LikeNote.of(target.kind, target.ref, comment)?.let(onSend) },
            icon = Icons.Rounded.Favorite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** For the Likes you tile: a one-line summary of what they liked. */
internal fun likedWhat(note: LikeNote): String = if (note.kind == LikeNoteKind.Prompt) "Liked your prompt" else "Liked your photo"
